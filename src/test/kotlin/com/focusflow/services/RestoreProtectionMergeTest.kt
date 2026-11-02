package com.focusflow.services

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.sqlite.SQLiteDataSource

class RestoreProtectionMergeTest {
    private val dir: File = createTempDirectory("focusflow-merge-test-").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Same columns as the real tables in Database.kt (migrations v1-v10).
    private val schema = listOf(
        "CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE block_rules (id TEXT PRIMARY KEY, process_name TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, enabled INTEGER DEFAULT 1, block_network INTEGER DEFAULT 0, source TEXT DEFAULT 'manual')",
        "CREATE TABLE block_schedules (id TEXT PRIMARY KEY, name TEXT NOT NULL, days_of_week TEXT NOT NULL, start_hour INTEGER NOT NULL, start_minute INTEGER NOT NULL, end_hour INTEGER NOT NULL, end_minute INTEGER NOT NULL, enabled INTEGER DEFAULT 1, process_names TEXT DEFAULT '')",
        "CREATE TABLE daily_allowances (process_name TEXT PRIMARY KEY, display_name TEXT NOT NULL, allowance_minutes INTEGER NOT NULL)",
        "CREATE TABLE network_cutoff_rules (id TEXT PRIMARY KEY, pattern TEXT NOT NULL, mode TEXT NOT NULL, target_process TEXT, target_display_name TEXT, enabled INTEGER DEFAULT 1)"
    )

    private fun connect(file: File): Connection =
        SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }.connection

    private fun newDb(name: String, vararg statements: String): File {
        val file = File(dir, name)
        connect(file).use { connection ->
            connection.createStatement().use { statement ->
                (schema + statements.toList()).forEach { statement.executeUpdate(it) }
            }
        }
        return file
    }

    private fun rows(file: File, sql: String): List<List<String?>> =
        connect(file).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { results ->
                    val columns = results.metaData.columnCount
                    val all = mutableListOf<List<String?>>()
                    while (results.next()) all += (1..columns).map { results.getString(it) }
                    all
                }
            }
        }

    private fun settings(file: File): Map<String, String> =
        rows(file, "SELECT key, value FROM settings").associate { it[0]!! to it[1]!! }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun strongerCurrentProtectionsSurviveARestoreOfAWeakerBackup() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('always_on_enforcement','true'), ('nuclear_mode','true'), ('keyword_blocker_enabled','false'), " +
                "('global_pin_hash','abc'), ('nuclear_pin_hash',''), ('standalone_block_until','9999999999999'), " +
                "('standalone_block_processes','x.exe'), ('standalone_block_start','0')",
            "INSERT INTO block_rules(id, process_name, display_name, enabled, block_network) VALUES ('r1','chrome.exe','Chrome',1,1), ('r2','discord.exe','Discord',1,0)",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',30), ('tiktok.exe','TikTok',10)",
            "INSERT INTO block_schedules VALUES ('s1','Work','1,2,3',9,0,17,0,1,'a.exe')",
            "INSERT INTO network_cutoff_rules(id, pattern, mode, enabled) VALUES ('n1','x.com','DOMAIN',1)"
        )
        val restored = newDb(
            "restored.db",
            "INSERT INTO settings VALUES ('always_on_enforcement','false'), ('global_pin_hash','')",
            "INSERT INTO block_rules(id, process_name, display_name, enabled, block_network) VALUES ('r9','chrome.exe','Chrome',0,0)",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',120)"
        )

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        val merged = settings(restored)
        assertEquals("true", merged["always_on_enforcement"])
        assertEquals("true", merged["nuclear_mode"])
        assertEquals(null, merged["keyword_blocker_enabled"])
        assertEquals("abc", merged["global_pin_hash"])
        assertEquals(null, merged["nuclear_pin_hash"])
        assertEquals("9999999999999", merged["standalone_block_until"])
        assertEquals("x.exe", merged["standalone_block_processes"])
        assertEquals(
            listOf(listOf<String?>("chrome.exe", "1", "1"), listOf<String?>("discord.exe", "1", "0")),
            rows(restored, "SELECT process_name, enabled, block_network FROM block_rules ORDER BY process_name")
        )
        assertEquals(
            listOf(listOf<String?>("tiktok.exe", "10"), listOf<String?>("youtube.exe", "30")),
            rows(restored, "SELECT process_name, allowance_minutes FROM daily_allowances ORDER BY process_name")
        )
        assertEquals(listOf(listOf<String?>("1")), rows(restored, "SELECT enabled FROM block_schedules WHERE id = 's1'"))
        assertEquals(listOf(listOf<String?>("1")), rows(restored, "SELECT enabled FROM network_cutoff_rules WHERE id = 'n1'"))
    }

    @Test
    fun aStrongerBackupIsNeverLoosened() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('global_pin_hash',''), ('standalone_block_until','100')",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',90)"
        )
        val restored = newDb(
            "restored.db",
            "INSERT INTO settings VALUES ('global_pin_hash','backuppin'), ('always_on_enforcement','true'), ('standalone_block_until','5000')",
            "INSERT INTO daily_allowances VALUES ('youtube.exe','YouTube',20)"
        )

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        val merged = settings(restored)
        assertEquals("backuppin", merged["global_pin_hash"])
        assertEquals("true", merged["always_on_enforcement"])
        assertEquals("5000", merged["standalone_block_until"])
        assertEquals(listOf(listOf<String?>("20")), rows(restored, "SELECT allowance_minutes FROM daily_allowances"))
    }

    @Test
    fun anUnprotectedCurrentDatabaseLeavesTheRestoreExactlyAsItWas() {
        val current = newDb("current.db")
        val restored = newDb("restored.db", "INSERT INTO settings VALUES ('always_on_enforcement','false')")

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        assertEquals(mapOf("always_on_enforcement" to "false"), settings(restored))
    }

    @Test
    fun keywordAndVpnListsAreUnionedWithoutDuplicates() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('blocked_keywords','alpha, Beta'), ('vpn_custom_processes','v1.exe')"
        )
        val restored = newDb(
            "restored.db",
            "INSERT INTO settings VALUES ('blocked_keywords','beta,gamma')"
        )

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        val merged = settings(restored)
        assertEquals("beta,gamma,alpha", merged["blocked_keywords"])
        assertEquals("v1.exe", merged["vpn_custom_processes"])
    }

    @Test
    fun theSafetyBackupFileIsNeverChanged() {
        val current = newDb(
            "current.db",
            "INSERT INTO settings VALUES ('always_on_enforcement','true')"
        )
        val restored = newDb("restored.db")
        val before = sha256(current)

        assertIs<RestoreProtectionMerge.MergeOutcome.Merged>(RestoreProtectionMerge.apply(restored, current))

        assertEquals(before, sha256(current))
        assertEquals(listOf("current.db", "restored.db"), dir.list().orEmpty().sorted())
    }

    @Test
    fun aMissingOrUnreadableSourceFailsAndLeavesTheRestoredDatabaseUntouched() {
        val restored = newDb("restored.db", "INSERT INTO settings VALUES ('always_on_enforcement','false')")
        val garbage = File(dir, "garbage.db").apply { writeText("this is not a database ".repeat(40)) }

        assertIs<RestoreProtectionMerge.MergeOutcome.Failed>(
            RestoreProtectionMerge.apply(restored, File(dir, "missing.db"))
        )
        assertIs<RestoreProtectionMerge.MergeOutcome.Failed>(RestoreProtectionMerge.apply(restored, garbage))
        assertEquals(mapOf("always_on_enforcement" to "false"), settings(restored))
    }

    @Test
    fun aMissingRestoredDatabaseIsNotCreatedByTheMerge() {
        val current = newDb("current.db", "INSERT INTO settings VALUES ('always_on_enforcement','true')")
        val restored = File(dir, "restored.db")

        assertIs<RestoreProtectionMerge.MergeOutcome.Failed>(RestoreProtectionMerge.apply(restored, current))
        assertEquals(false, restored.exists())
    }
}
