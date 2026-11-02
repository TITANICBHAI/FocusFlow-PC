package com.focusflow.services

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.SQLException
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

/**
 * Makes sure a restore can never leave protections weaker than they were just before it.
 *
 * The restored database keeps everything from the backup. Then every protection the user had
 * BEFORE the restore is copied back in whenever it is stricter, so a restore can only ADD
 * protections, never remove them. Loosening something on purpose still happens in the
 * PIN-gated screens, never through a restore.
 *
 * Known gaps (listed in Security/plan.md): the emergency-break budget, per-task focus settings
 * and edits to the times of an existing schedule are not merged.
 * PIN hashes are copied as opaque strings and are never logged.
 */
internal object RestoreProtectionMerge {

    sealed interface MergeOutcome {
        object Merged : MergeOutcome
        data class Failed(val reason: String) : MergeOutcome
    }

    /** In these statements `pre` is the database as it was just before the restore, `main` is the restored one. */
    private val mergeStatements = listOf(
        // Switches that were ON stay ON.
        """INSERT OR REPLACE INTO main.settings(key, value)
           SELECT key, 'true' FROM pre.settings
           WHERE key IN ('always_on_enforcement','nuclear_mode','keyword_blocker_enabled','vpn_block_enabled')
             AND value = 'true'""",
        // A PIN that was set stays set (the current hash wins over the backup's).
        """INSERT OR REPLACE INTO main.settings(key, value)
           SELECT key, value FROM pre.settings
           WHERE key IN ('global_pin_hash','nuclear_pin_hash') AND value <> ''""",
        // A standalone block that ends later than the restored one replaces it.
        """INSERT OR REPLACE INTO main.settings(key, value)
           SELECT key, value FROM pre.settings
           WHERE key IN ('standalone_block_processes','standalone_block_until','standalone_block_start')
             AND CAST((SELECT value FROM pre.settings WHERE key = 'standalone_block_until') AS INTEGER) >
                 CAST(COALESCE((SELECT value FROM main.settings WHERE key = 'standalone_block_until'), '0') AS INTEGER)""",
        // Block rules: add the ones the backup lacks, switch the ones that were on back on.
        """INSERT OR IGNORE INTO main.block_rules(id, process_name, display_name, enabled, block_network)
           SELECT id, process_name, display_name, enabled, block_network FROM pre.block_rules""",
        """UPDATE main.block_rules SET enabled = 1
           WHERE enabled = 0 AND process_name IN (SELECT process_name FROM pre.block_rules WHERE enabled = 1)""",
        """UPDATE main.block_rules SET block_network = 1
           WHERE block_network = 0 AND process_name IN (SELECT process_name FROM pre.block_rules WHERE block_network = 1)""",
        // Schedules: add the missing ones, switch the ones that were on back on.
        """INSERT OR IGNORE INTO main.block_schedules(id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, enabled, process_names)
           SELECT id, name, days_of_week, start_hour, start_minute, end_hour, end_minute, enabled, process_names FROM pre.block_schedules""",
        """UPDATE main.block_schedules SET enabled = 1
           WHERE enabled = 0 AND id IN (SELECT id FROM pre.block_schedules WHERE enabled = 1)""",
        // Daily allowances: add the missing ones, and the smaller limit always wins.
        """INSERT OR IGNORE INTO main.daily_allowances(process_name, display_name, allowance_minutes)
           SELECT process_name, display_name, allowance_minutes FROM pre.daily_allowances""",
        """UPDATE main.daily_allowances AS a
           SET allowance_minutes = MIN(a.allowance_minutes,
               (SELECT p.allowance_minutes FROM pre.daily_allowances AS p WHERE p.process_name = a.process_name))
           WHERE a.process_name IN (SELECT process_name FROM pre.daily_allowances)""",
        // Network cut-off rules: add the missing ones, switch the ones that were on back on.
        """INSERT OR IGNORE INTO main.network_cutoff_rules(id, pattern, mode, target_process, target_display_name, enabled)
           SELECT id, pattern, mode, target_process, target_display_name, enabled FROM pre.network_cutoff_rules""",
        """UPDATE main.network_cutoff_rules SET enabled = 1
           WHERE enabled = 0 AND id IN (SELECT id FROM pre.network_cutoff_rules WHERE enabled = 1)"""
    )

    /**
     * Copies [preRestoreDatabase] to a temporary file (so the verified safety backup itself is
     * never opened for writing or changed), merges from the copy into [restoredDatabase], and
     * deletes the copy. The merge is one transaction: on any failure nothing is changed.
     * Call it only while FocusFlow's own connection to [restoredDatabase] is closed.
     */
    fun apply(restoredDatabase: File, preRestoreDatabase: File): MergeOutcome {
        if (!restoredDatabase.isFile) return MergeOutcome.Failed("the restored database file is missing")
        if (!preRestoreDatabase.isFile) return MergeOutcome.Failed("the pre-restore copy is missing")
        val copy = try {
            Files.createTempFile("focusflow-merge-", ".db").toFile()
        } catch (failure: Exception) {
            return MergeOutcome.Failed("could not create a temporary file")
        }
        return try {
            Files.copy(preRestoreDatabase.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val config = SQLiteConfig().apply { setBusyTimeout(5_000) }
            val dataSource = SQLiteDataSource(config).apply {
                url = "jdbc:sqlite:${restoredDatabase.absolutePath}"
            }
            dataSource.connection.use { connection -> mergeInto(connection, copy) }
            MergeOutcome.Merged
        } catch (failure: Exception) {
            MergeOutcome.Failed(describe(failure))
        } finally {
            deleteQuietly(copy)
        }
    }

    /** Runs the whole merge on an open connection to the RESTORED database. */
    internal fun mergeInto(connection: Connection, preRestoreCopy: File) {
        val quotedPath = preRestoreCopy.absolutePath.replace("'", "''")
        connection.createStatement().use { statement ->
            statement.execute("ATTACH DATABASE '$quotedPath' AS pre")
            try {
                statement.execute("BEGIN IMMEDIATE")
                try {
                    mergeStatements.forEach { statement.executeUpdate(it) }
                    mergeCommaList(connection, "blocked_keywords")
                    mergeCommaList(connection, "vpn_custom_processes")
                    statement.execute("COMMIT")
                } catch (failure: Throwable) {
                    runCatching { statement.execute("ROLLBACK") }
                    throw failure
                }
            } finally {
                runCatching { statement.execute("DETACH DATABASE pre") }
            }
        }
    }

    /** Comma-separated lists (blocked keywords, custom VPN processes): keep the union, restored order first. */
    private fun mergeCommaList(connection: Connection, key: String) {
        fun read(schema: String): List<String> =
            connection.prepareStatement("SELECT value FROM $schema.settings WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        rows.getString(1).orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }
                }
            }

        val before = read("pre")
        if (before.isEmpty()) return
        val restored = read("main")
        val seen = restored.map { it.lowercase() }.toMutableSet()
        val merged = restored.toMutableList()
        for (item in before) {
            if (seen.add(item.lowercase())) merged += item
        }
        if (merged.size == restored.size) return
        connection.prepareStatement("INSERT OR REPLACE INTO main.settings(key, value) VALUES (?, ?)").use { statement ->
            statement.setString(1, key)
            statement.setString(2, merged.joinToString(","))
            statement.executeUpdate()
        }
    }

    /** Short, path-free description that is safe to show in the restore notice. */
    private fun describe(failure: Exception): String {
        val detail = if (failure is SQLException) failure.message else null
        return listOfNotNull(failure.javaClass.simpleName, detail).joinToString(": ")
    }

    private fun deleteQuietly(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal"))
            .forEach { runCatching { it.delete() } }
    }
}
