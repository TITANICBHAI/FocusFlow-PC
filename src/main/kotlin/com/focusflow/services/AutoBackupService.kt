package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.EnforcementLog
import kotlinx.coroutines.*
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * AutoBackupService — snapshot and verification for database backups.
 *
 * Backups use SQLite VACUUM INTO, then a read-only quick_check and SHA-256
 * sidecar verification. Hashless legacy backups are reported as Unverified.
 * A metadata sidecar records timestamp, size, and hash.
 *
 * Direct restore is intentionally unavailable: [restoreBackup] is retained
 * only as a legacy API guarded by a compile-time deprecation error. A supported
 * recovery workflow requires separate owner approval.
 */
object AutoBackupService {

    private const val MAX_BACKUPS = 7
    private const val LAST_BACKUP_STATUS_KEY = "last_backup_status"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: start() writes on the Compose application thread; stop() reads on the
    // "FocusFlow-Shutdown" daemon thread (Main.kt). Without @Volatile the shutdown
    // thread may see a stale null and skip the cancel, leaving the 6-hour loop running.
    @Volatile private var job: Job? = null
    @Volatile internal var snapshotWriterForTest: ((File) -> Unit)? = null
    @Volatile internal var warningSinkForTest: ((String, Throwable?) -> Unit)? = null

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

    sealed class BackupVerification {
        data class Verified(val hash: String) : BackupVerification()
        object Unverified : BackupVerification()
        data class Invalid(val reason: String) : BackupVerification()
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
    @Synchronized
    fun runBackupNow(): BackupResult {
        val src = File(dbPath)
        if (!src.exists()) return completeBackup(BackupResult.NoDatabase)

        val dir = backupDir
        val timestamp = uniqueTimestamp(LocalDateTime.now().format(TIMESTAMP_FORMAT), dir)
        val destDb = File(dir, "focusflow_$timestamp.db")
        val destHash = sidecarFile(destDb, "sha256")
        val destMeta = sidecarFile(destDb, "meta.json")

        return try {
            // Layer 1: safe snapshot via VACUUM INTO.
            // Unlike a raw file copy, VACUUM INTO produces a fully consistent,
            // WAL-free SQLite file — it flushes WAL pages into the snapshot and
            // never captures a torn state even under concurrent writes.
            val injectedSnapshotWriter = snapshotWriterForTest
            if (injectedSnapshotWriter == null) {
                Database.vacuumInto(destDb.absolutePath)
            } else {
                injectedSnapshotWriter(destDb)
            }

            // A matching digest only proves the file has not changed since it
            // was hashed. Check SQLite's own integrity before creating sidecars.
            val quickCheckFailure = sqliteQuickCheckFailure(destDb)
            if (quickCheckFailure != null) {
                deleteBackupArtifacts(destDb)
                return completeBackup(BackupResult.VerificationFailed, quickCheckFailure)
            }

            val hash = sha256(destDb)
            destHash.writeText(hash)

            // Layer 2: verify hash and SQLite integrity immediately.
            val verificationFailure = when (val verification = verifyBackup(destDb)) {
                is BackupVerification.Verified ->
                    if (verification.hash == hash) null else "SHA-256 changed during verification"
                BackupVerification.Unverified -> "SHA-256 sidecar disappeared during verification"
                is BackupVerification.Invalid -> verification.reason
            }
            if (verificationFailure != null) {
                deleteBackupArtifacts(destDb)
                return completeBackup(
                    BackupResult.VerificationFailed,
                    verificationFailure
                )
            }

            // Write human-readable metadata
            destMeta.writeText(buildMetaJson(timestamp, destDb.length(), hash))

            pruneOldBackups()
            completeBackup(BackupResult.Success(destDb, hash, destDb.length()))
        } catch (cancelled: CancellationException) {
            deleteBackupArtifacts(destDb)
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            deleteBackupArtifacts(destDb)
            throw fatal
        } catch (e: Throwable) {
            deleteBackupArtifacts(destDb)
            completeBackup(BackupResult.Error(e.message ?: "Unknown error"))
        }
    }

    private fun runBackupIfNeeded() {
        val today = LocalDate.now().format(DATE_FORMAT)
        val todayBackup = backupDir.listFiles { f ->
            f.name.startsWith("focusflow_$today") && f.name.endsWith(".db")
        }?.maxByOrNull { it.lastModified() }

        if (todayBackup == null) {
            runBackupNow()
        } else {
            // Keep any unverified/corrupt file for inspection; make a fresh
            // verified snapshot rather than silently deleting an existing backup.
            when (val verification = verifyBackup(todayBackup)) {
                is BackupVerification.Verified -> persistBackupStatus("success")
                BackupVerification.Unverified -> {
                    warn("Today's backup has no hash sidecar; creating a verified backup.")
                    runBackupNow()
                }
                is BackupVerification.Invalid -> {
                    warn("Today's backup failed verification; creating a fresh backup: ${verification.reason}")
                    runBackupNow()
                }
            }
        }
    }

    // ── Layer 2 helper: verify a backup file ──────────────────────────────────

    /**
     * Verifies both the hash sidecar and SQLite integrity. A legacy backup with
     * no hash sidecar is explicitly Unverified rather than treated as trusted.
     */
    fun verifyBackup(file: File): BackupVerification {
        if (!file.isFile) return BackupVerification.Invalid("Backup file is missing")
        val hashFile = sidecarFile(file, "sha256")
        if (!hashFile.isFile) return BackupVerification.Unverified
        return try {
            val stored = hashFile.readText().trim()
            if (stored.isBlank()) {
                return BackupVerification.Invalid("SHA-256 sidecar is empty")
            }
            val computed = sha256(file)
            if (stored != computed) {
                return BackupVerification.Invalid("SHA-256 mismatch")
            }
            val quickCheckFailure = sqliteQuickCheckFailure(file)
            if (quickCheckFailure != null) {
                BackupVerification.Invalid(quickCheckFailure)
            } else {
                BackupVerification.Verified(computed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            BackupVerification.Invalid(failure.message ?: "Backup verification failed")
        }
    }

    /** Reads the metadata sidecar for [file], or null if not present. */
    fun readMeta(file: File): String? {
        val metaFile = sidecarFile(file, "meta.json")
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
    @Deprecated(
        message = "Unsafe direct restore is disabled; use an owner-approved recovery workflow.",
        level = DeprecationLevel.ERROR
    )
    fun restoreBackup(backupFile: File): RestoreResult {
        if (!backupFile.exists()) return RestoreResult.NoBackupFile

        val hashFile = sidecarFile(backupFile, "sha256")
        if (!hashFile.exists()) return RestoreResult.NoHashFile
        if (verifyBackup(backupFile) !is BackupVerification.Verified) {
            return RestoreResult.HashMismatch
        }

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

    private fun completeBackup(
        result: BackupResult,
        verificationFailure: String? = null
    ): BackupResult {
        val status = when (result) {
            is BackupResult.Success -> "success"
            BackupResult.NoDatabase -> "no_database"
            BackupResult.VerificationFailed -> "verification_failed"
            is BackupResult.Error -> "error"
        }
        if (result !is BackupResult.Success) {
            val detail = verificationFailure ?: when (result) {
                BackupResult.NoDatabase -> "database file is missing"
                BackupResult.VerificationFailed -> "SQLite or hash verification failed"
                is BackupResult.Error -> result.reason
                is BackupResult.Success -> error("Success cannot be a failure")
            }
            warn("Backup did not complete ($status): $detail")
        }
        persistBackupStatus(status)
        return result
    }

    private fun persistBackupStatus(status: String) {
        when (val write = Database.writeSetting(LAST_BACKUP_STATUS_KEY, status)) {
            Database.SettingWrite.Ok -> Unit
            is Database.SettingWrite.Failed ->
                warn("Could not persist $LAST_BACKUP_STATUS_KEY", write.cause)
        }
    }

    private fun warn(message: String, cause: Throwable? = null) {
        val sink = warningSinkForTest
        if (sink == null) {
            EnforcementLog.warn("AutoBackupService", message, cause)
        } else {
            sink(message, cause)
        }
    }

    private fun uniqueTimestamp(base: String, dir: File): String {
        var candidate = base
        var suffix = 1
        while (listOf("db", "sha256", "meta.json").any { extension ->
                File(dir, "focusflow_$candidate.$extension").exists()
            }) {
            candidate = "${base}_${suffix.toString().padStart(2, '0')}"
            suffix++
        }
        return candidate
    }

    private fun sidecarFile(file: File, extension: String): File =
        File(file.parentFile, "${file.nameWithoutExtension}.$extension")

    private fun sqliteQuickCheckFailure(file: File): String? {
        return try {
            val config = SQLiteConfig().apply {
                setBusyTimeout(1_000)
                setReadOnly(true)
            }
            val dataSource = SQLiteDataSource(config).apply {
                url = "jdbc:sqlite:${file.absolutePath}"
            }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { results ->
                        if (!results.next()) {
                            return "SQLite quick_check returned no rows"
                        }
                        var problem: String? = null
                        do {
                            val message = results.getString(1).orEmpty()
                            if (!message.equals("ok", ignoreCase = true)) {
                                problem = message
                            }
                        } while (results.next())
                        problem?.let { "SQLite quick_check reported: $it" }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            "SQLite quick_check failed: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }

    private fun deleteBackupArtifacts(file: File) {
        listOf(file, sidecarFile(file, "sha256"), sidecarFile(file, "meta.json"))
            .forEach { artifact ->
                try {
                    Files.deleteIfExists(artifact.toPath())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: VirtualMachineError) {
                    throw fatal
                } catch (failure: Throwable) {
                    warn("Could not remove incomplete backup artifact ${artifact.name}", failure)
                }
            }
    }

    @Synchronized
    internal fun resetForTest() {
        job?.cancel()
        job = null
        snapshotWriterForTest = null
        warningSinkForTest = null
    }

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
