package com.focusflow.services

import com.focusflow.AppPaths
import com.focusflow.data.Database
import com.focusflow.data.NativeSqlite
import com.focusflow.data.NativeSqliteStatus
import com.focusflow.enforcement.EnforcementLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Keeps a copy of the database next to it (protection-snapshot.db), refreshed about once a minute
 * while FocusFlow runs. If the next start finds a database WITHOUT a PIN, Always-On or Nuclear
 * Mode although the snapshot has one of them (the file was deleted, replaced or reset while
 * FocusFlow was closed), the protections are merged back in — same merge as a backup restore, so
 * it only ever ADDS protections. A notification tells the user.
 *
 * Limits (also in Security/plan.md): this does not stop someone editing the database while the
 * app runs, and anyone who deletes both files while FocusFlow is closed still wins. Changes made
 * in the last minute before the app stopped are not in the snapshot.
 */
object ProtectionSnapshotService {
    private const val TAG = "ProtectionSnapshot"
    private const val REFRESH_INTERVAL_MS = 60_000L

    /** Counts the protections that matter after a deletion: a PIN, Always-On, or Nuclear Mode. */
    internal const val PROTECTED_STATE_SQL =
        "SELECT COUNT(*) FROM settings WHERE " +
            "(key IN ('always_on_enforcement','nuclear_mode') AND value = 'true') OR " +
            "(key IN ('global_pin_hash','nuclear_pin_hash') AND value <> '')"

    val snapshotFile: File get() = File(AppPaths.dataDir, "protection-snapshot.db")

    @Volatile private var startupCheckDone = false
    @Volatile private var restoredNotice = false
    private val started = AtomicBoolean(false)

    /** True when [dbFile] exists, opens, and holds at least one PIN / Always-On / Nuclear setting. */
    fun hasProtections(dbFile: File): Boolean {
        if (!dbFile.isFile) return false
        return try {
            if (NativeSqlite.ensureLoaded() !is NativeSqliteStatus.Loaded) return false
            val config = SQLiteConfig().apply {
                setBusyTimeout(3_000)
                setReadOnly(true)
            }
            val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:${dbFile.absolutePath}" }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(PROTECTED_STATE_SQL).use { rows -> rows.next() && rows.getInt(1) > 0 }
                }
            }
        } catch (failure: Exception) {
            false
        }
    }

    /** The live database has no protections but the snapshot does. */
    fun shouldUseSnapshotForWizard(): Boolean =
        runCatching { hasProtections(snapshotFile) && !hasProtections(AppPaths.dbFile) }.getOrDefault(false)

    /** Call once, right after the database opened and before anything reads protection settings. */
    fun restoreIfNeeded() {
        try {
            val snapshot = snapshotFile
            if (hasProtections(snapshot) && !hasProtections(AppPaths.dbFile)) {
                when (val outcome = RestoreProtectionMerge.apply(AppPaths.dbFile, snapshot)) {
                    RestoreProtectionMerge.MergeOutcome.Merged -> {
                        restoredNotice = true
                        EnforcementLog.warn(TAG, "The database had no protections; restored the last known ones")
                    }
                    is RestoreProtectionMerge.MergeOutcome.Failed ->
                        EnforcementLog.warn(TAG, "Could not restore protections: ${outcome.reason}")
                }
            }
        } catch (failure: Throwable) {
            EnforcementLog.warn(TAG, "Protection restore check failed", failure)
        } finally {
            startupCheckDone = true
        }
    }

    /** Writes a fresh snapshot. Does nothing until [restoreIfNeeded] ran, so an empty database never overwrites it. */
    fun refresh(): Boolean {
        if (!startupCheckDone || !Database.isReady) return false
        val target = snapshotFile
        val temporary = File(target.parentFile, "protection-snapshot.tmp")
        return try {
            temporary.delete()
            val config = SQLiteConfig().apply {
                setBusyTimeout(5_000)
                setReadOnly(true)
            }
            val dataSource = SQLiteDataSource(config).apply { url = "jdbc:sqlite:${AppPaths.dbFile.absolutePath}" }
            val quoted = temporary.absolutePath.replace("'", "''")
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute("VACUUM INTO '$quoted'") }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (failure: Exception) {
            EnforcementLog.warn(TAG, "Could not refresh the protection snapshot", failure)
            false
        } finally {
            runCatching { temporary.delete() }
        }
    }

    /** Starts the background refresh (once) and shows the "restored" notice if one is due. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val worker = Thread({
            try {
                refresh()
                Thread.sleep(20_000L)
                if (restoredNotice) {
                    restoredNotice = false
                    runCatching {
                        SystemTrayManager.showNotification(
                            "Protections restored",
                            "FocusFlow's database was missing or had been reset. Your last known protections were put back."
                        )
                    }
                }
                while (true) {
                    refresh()
                    Thread.sleep(REFRESH_INTERVAL_MS)
                }
            } catch (_: InterruptedException) {
                // Process is ending.
            }
        }, "ProtectionSnapshot")
        worker.isDaemon = true
        worker.start()
    }
}
