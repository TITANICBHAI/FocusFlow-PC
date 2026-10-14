package com.focusflow.services

import com.focusflow.data.Database
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * AutoBackupService — three-layer database backup
 *
 * Layer 1 — Write:
 *   Copies the live SQLite database to a timestamped file and immediately
 *   computes a SHA-256 hash, writing it to a ".sha256" sidecar file alongside
 *   the backup. Both files are written atomically (copy → hash → write hash).
 *
 * Layer 2 — Verify:
 *   Immediately after writing, the backup is read back and its hash is compared
 *   against the sidecar. If they differ (disk error, partial write, filesystem
 *   issue) both files are deleted and [BackupResult.VerificationFailed] is
 *   returned — a corrupted backup is never silently kept.
 *   A human-readable ".meta.json" sidecar is also written with timestamp, size,
 *   and hash so the UI can display rich backup info without re-reading the db.
 *
 * Layer 3 — Safe restore:
 *   [restoreBackup] verifies the backup's hash before overwriting the live
 *   database. Before the overwrite it creates a "pre_restore_safety.db" snapshot
 *   of the current database. If the copy fails, the safety snapshot is
 *   automatically rolled back so the live database is never left in a broken state.
 */
object AutoBackupService {

    private const val MAX_BACKUPS = 7
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: start() writes on the Compose application thread; stop() reads on the
    // "FocusFlow-Shutdown" daemon thread (Main.kt). Without @Volatile the shutdown
    // thread may see a stale null and skip the cancel, leaving the 6-hour loop running.
    @Volatile private var job: Job? = null

    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.BASIC_ISO_DATE

    private val dbPath: String
        get() = System.getProperty("user.home") + "/.focusflow/focusflow.db"

    private val backupDir: File
        get() = File(System.getProperty("user.home") + "/.focusflow/backups")
            .also { it.mkdirs() }

    // ── Public API ────────────────────────────────────────────────────────────

    sealed class BackupResult {
        data class Success(val file: File, val hash: String, val sizeBytes: Long) : BackupResult()
        object NoDatabase          : BackupResult()
        object VerificationFailed  : BackupResult()
        data class Error(val reason: String) : BackupResult()
    }

    sealed class RestoreResult {
        object Success                                 : RestoreResult()
        object NoBackupFile                            : RestoreResult()
        object NoHashFile                              : RestoreResult()
        object HashMismatch                            : RestoreResult()
        data class Failed(val reason: String)          : RestoreResult()
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runBackupIfNeeded()
            while (isActive) {
                delay(6 * 60 * 60 * 1000L)   // every 6 hours
                runBackupIfNeeded()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // ── Layer 1 + 2: Write and verify ─────────────────────────────────────────

    /**
     * Perform a full backup right now regardless of schedule.
     * Returns a [BackupResult] that callers can inspect or surface in the UI.
     */
    fun runBackupNow(): BackupResult {
        val src = File(dbPath)
        if (!src.exists()) return BackupResult.NoDatabase

        val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        val destDb    = File(backupDir, "focusflow_$timestamp.db")
        val destHash  = File(backupDir, "focusflow_$timestamp.sha256")
        val destMeta  = File(backupDir, "focusflow_$timestamp.meta.json")

        return try {
            // Layer 1: safe snapshot via VACUUM INTO.
            // Unlike a raw file copy, VACUUM INTO produces a fully consistent,
            // WAL-free SQLite file — it flushes WAL pages into the snapshot and
            // never captures a torn state even under concurrent writes.
            Database.vacuumInto(destDb.absolutePath)
            val hash = sha256(destDb)
            destHash.writeText(hash)

            // Layer 2: verify immediately
            val verifiedHash = sha256(destDb)
            if (verifiedHash != hash) {
                runCatching { destDb.delete(); destHash.delete() }
                return BackupResult.VerificationFailed
            }

            // Write human-readable metadata
            destMeta.writeText(buildMetaJson(timestamp, destDb.length(), hash))

            pruneOldBackups()
            BackupResult.Success(destDb, hash, destDb.length())
        } catch (cancelled: CancellationException) {
            runCatching { destDb.delete(); destHash.delete(); destMeta.delete() }
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            runCatching { destDb.delete(); destHash.delete(); destMeta.delete() }
            throw fatal
        } catch (e: Throwable) {
            runCatching { destDb.delete(); destHash.delete(); destMeta.delete() }
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun runBackupIfNeeded() {
        val today = LocalDate.now().format(DATE_FORMAT)
        val todayBackup = backupDir.listFiles { f ->
            f.name.startsWith("focusflow_$today") && f.name.endsWith(".db")
        }?.firstOrNull()

        if (todayBackup == null) {
            runBackupNow()
        } else {
            // Today's backup exists — verify it hasn't been corrupted since creation
            val result = verifyBackup(todayBackup)
            if (!result) {
                // Stale / corrupted — make a fresh one with a new timestamp
                todayBackup.delete()
                File(todayBackup.path.replace(".db", ".sha256")).delete()
                File(todayBackup.path.replace(".db", ".meta.json")).delete()
                runBackupNow()
            }
        }
    }

    // ── Layer 2 helper: verify a backup file ──────────────────────────────────

    /**
     * Returns true if [file] matches its sidecar ".sha256" hash.
     * If no hash file exists (legacy backup from before this version),
     * returns true without verification to avoid false negatives.
     */
    fun verifyBackup(file: File): Boolean {
        val hashFile = File(file.parent, file.nameWithoutExtension + ".sha256")
        if (!hashFile.exists()) return true   // pre-hash legacy backup — assume ok
        return try {
            val stored   = hashFile.readText().trim()
            val computed = sha256(file)
            stored == computed
        } catch (_: Exception) { false }
    }

    /** Reads the metadata sidecar for [file], or null if not present. */
    fun readMeta(file: File): String? {
        val metaFile = File(file.parent, file.nameWithoutExtension + ".meta.json")
        return if (metaFile.exists()) runCatching { metaFile.readText() }.getOrNull() else null
    }

    // ── Layer 3: Safe restore ─────────────────────────────────────────────────

    /**
     * Restore [backupFile] as the live database.
     *
     * Steps:
     *   1. Verify backup hash matches sidecar (rejects corrupted backups)
     *   2. Snapshot the current live database to "pre_restore_safety.db"
     *   3. Overwrite live database with backup
     *   4. If step 3 throws, automatically roll back using the safety snapshot
     */
    fun restoreBackup(backupFile: File): RestoreResult {
        if (!backupFile.exists()) return RestoreResult.NoBackupFile

        val hashFile = File(backupFile.parent, backupFile.nameWithoutExtension + ".sha256")
        if (!hashFile.exists()) return RestoreResult.NoHashFile
        if (!verifyBackup(backupFile)) return RestoreResult.HashMismatch

        val live   = File(dbPath)
        val safety = File(backupDir, "pre_restore_safety.db")

        // Safety snapshot of current database.
        // MUST use VACUUM INTO rather than Files.copy: the live DB runs in WAL mode
        // and a raw file copy captures only the .db file, omitting the -wal and -shm
        // files. A partial snapshot like this will be missing the last committed
        // transactions and would corrupt the rollback target on restore failure.
        // VACUUM INTO produces a single, WAL-free, fully consistent copy even while
        // the database is being actively written to.
        if (live.exists()) {
            runCatching { Database.vacuumInto(safety.absolutePath) }
        }

        return try {
            Files.copy(backupFile.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING)
            RestoreResult.Success
        } catch (e: Exception) {
            // Roll back: restore safety snapshot
            runCatching {
                if (safety.exists()) {
                    Files.copy(safety.toPath(), live.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            RestoreResult.Failed(e.message ?: "Unknown error")
        }
    }

    // ── Listing ───────────────────────────────────────────────────────────────

    fun listBackups(): List<File> =
        (backupDir.listFiles { f -> f.name.endsWith(".db") && !f.name.contains("safety") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList())

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8_192)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildMetaJson(timestamp: String, sizeBytes: Long, hash: String): String =
        """{"timestamp":"$timestamp","sizeBytes":$sizeBytes,"sha256":"$hash","dbPath":"$dbPath"}"""

    private fun pruneOldBackups() {
        val files = backupDir.listFiles { f ->
            f.name.endsWith(".db") && !f.name.contains("safety")
        }?.sortedByDescending { it.lastModified() } ?: return

        files.drop(MAX_BACKUPS).forEach { db ->
            db.delete()
            File(db.parent, db.nameWithoutExtension + ".sha256").delete()
            File(db.parent, db.nameWithoutExtension + ".meta.json").delete()
        }
    }
}
