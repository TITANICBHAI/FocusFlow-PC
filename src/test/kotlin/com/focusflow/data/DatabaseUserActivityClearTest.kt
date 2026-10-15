package com.focusflow.data

import com.focusflow.testutil.TempHome
import com.focusflow.services.AutoBackupService
import com.focusflow.services.ClearUserActivityService
import com.focusflow.services.GlobalPin
import com.focusflow.services.PinState
import com.focusflow.services.PinVerificationResult
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DatabaseUserActivityClearTest {

    @Test
    fun f22aFailureOnTheLastActivityDeleteLeavesAllFourAreasUnchanged() = withDatabase { dbFile ->
        seedActivityAndProtectedData(dbFile)
        installTemptationDeleteFailure(dbFile)

        assertFailsWith<SQLException> {
            Database.clearUserActivity()
        }

        assertActivityCounts(dbFile, expected = 1)
        assertProtectedDataCounts(dbFile)
    }

    @Test
    fun f22bSuccessfulClearDeletesOnlyApprovedActivityAreas() = withDatabase { dbFile ->
        seedActivityAndProtectedData(dbFile)

        Database.clearUserActivity()

        assertActivityCounts(dbFile, expected = 0)
        assertProtectedDataCounts(dbFile)
        assertEquals("keep-user", settingValue(dbFile, "user_name"))
        assertEquals("test-hash", settingValue(dbFile, "global_pin_hash"))
    }

    @Test
    fun f22cBackupFailurePreventsAnyActivityDeletion() = withDatabase { dbFile ->
        seedActivityAndProtectedData(dbFile)
        var backupAttempts = 0

        val result = ClearUserActivityService.clearAfterVerifiedBackup {
            backupAttempts++
            AutoBackupService.BackupResult.VerificationFailed
        }

        assertEquals(1, backupAttempts)
        assertIs<ClearUserActivityService.Result.BackupFailed>(result)
        assertActivityCounts(dbFile, expected = 1)
        assertProtectedDataCounts(dbFile)
    }

    @Test
    fun f22dUnconfiguredGlobalPinPreventsBackupAndActivityClear() = withDatabase { dbFile ->
        seedActivityAndProtectedData(dbFile)
        removeGlobalPinHash(dbFile)
        assertEquals(PinState.NOT_SET, GlobalPin.state())
        assertEquals(
            PinVerificationResult.UNAVAILABLE,
            GlobalPin.verifyConfiguredResult("anything")
        )
        var backupAttempts = 0

        val result = ClearUserActivityService.clearAfterVerifiedBackup {
            backupAttempts++
            AutoBackupService.BackupResult.Success(
                file = dbFile.resolveSibling("unverified-test-backup.db").toFile(),
                hash = "test-hash",
                sizeBytes = 0
            )
        }

        assertEquals(0, backupAttempts, "backup must not run when no Global PIN is configured")
        assertEquals(
            ClearUserActivityService.Result.PinCheckFailed(PinState.NOT_SET),
            result
        )
        assertActivityCounts(dbFile, expected = 1)
        assertProtectedDataCounts(dbFile, expectedSettings = 1)
    }

    @Test
    fun f22eVerifiedBackupContainsActivityBeforeTheClear() = withDatabase { dbFile ->
        seedActivityAndProtectedData(dbFile)
        AutoBackupService.resetForTest()

        try {
            val result = assertIs<ClearUserActivityService.Result.Cleared>(
                ClearUserActivityService.clearAfterVerifiedBackup()
            )

            assertTrue(result.backupFile.isFile, "verified backup file must exist")
            assertIs<AutoBackupService.BackupVerification.Verified>(
                AutoBackupService.verifyBackup(result.backupFile)
            )
            assertActivityCounts(result.backupFile.toPath(), expected = 1)
            assertActivityCounts(dbFile, expected = 0)
            assertProtectedDataCounts(dbFile, expectedSettings = 3)
            assertEquals("test-hash", settingValue(dbFile, "global_pin_hash"))
        } finally {
            AutoBackupService.resetForTest()
        }
    }

    private fun withDatabase(block: (Path) -> Unit) {
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow").resolve("focusflow.db")
            assertIs<DbInitResult.Ready>(Database.init(dbFile.toFile(), allowRecovery = false))
            block(dbFile)
        }
    }

    private fun seedActivityAndProtectedData(dbFile: Path) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                listOf(
                    """
                    INSERT INTO focus_sessions (id, task_name, start_time, planned_minutes)
                    VALUES ('session-1', 'Focus test', '2026-10-15T10:00:00', 25)
                    """.trimIndent(),
                    """
                    INSERT INTO daily_completions (date, completed_count, total_count, focus_minutes)
                    VALUES ('2026-10-15', 1, 1, 25)
                    """.trimIndent(),
                    """
                    INSERT INTO tasks (id, title, created_at)
                    VALUES ('task-1', 'Activity test', '2026-10-15T10:00:00')
                    """.trimIndent(),
                    """
                    INSERT INTO daily_notes (date, content, mood, updated_at)
                    VALUES ('2026-10-15', 'Activity note', 4, '2026-10-15T10:00:00')
                    """.trimIndent(),
                    """
                    INSERT INTO temptation_log (process_name, display_name, timestamp)
                    VALUES ('example.exe', 'Example', '2026-10-15T10:00:00')
                    """.trimIndent(),
                    """
                    INSERT INTO settings (key, value) VALUES
                    ('user_name', 'keep-user'),
                    ('global_pin_hash', 'test-hash')
                    """.trimIndent(),
                    """
                    INSERT INTO block_rules (id, process_name, display_name)
                    VALUES ('rule-1', 'example.exe', 'Example')
                    """.trimIndent(),
                    """
                    INSERT INTO block_schedules (
                        id, name, days_of_week, start_hour, start_minute, end_hour, end_minute
                    ) VALUES ('schedule-1', 'Weekdays', '1,2,3,4,5', 9, 0, 17, 0)
                    """.trimIndent(),
                    """
                    INSERT INTO network_cutoff_rules (id, pattern, mode, enabled)
                    VALUES ('network-rule-1', 'example.com', 'DOMAIN', 1)
                    """.trimIndent(),
                    """
                    INSERT INTO daily_allowances (process_name, display_name, allowance_minutes)
                    VALUES ('example.exe', 'Example', 60)
                    """.trimIndent(),
                    """
                    INSERT INTO daily_usage (date, process_name, seconds_used)
                    VALUES ('2026-10-15', 'example.exe', 120)
                    """.trimIndent(),
                    """
                    INSERT INTO habits (id, name, created_at)
                    VALUES ('habit-1', 'Keep habit', '2026-10-15')
                    """.trimIndent(),
                    """
                    INSERT INTO habit_entries (habit_id, date, done)
                    VALUES ('habit-1', '2026-10-15', 1)
                    """.trimIndent(),
                    """
                    INSERT INTO custom_block_presets (id, name, created_at)
                    VALUES ('custom-preset-1', 'Keep preset', '2026-10-15T10:00:00')
                    """.trimIndent(),
                    """
                    INSERT INTO focus_launcher_presets (id, name, created_at)
                    VALUES ('launcher-preset-1', 'Keep launcher preset', '2026-10-15T10:00:00')
                    """.trimIndent()
                ).forEach(statement::executeUpdate)
            }
        }
    }

    private fun installTemptationDeleteFailure(dbFile: Path) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TRIGGER fail_activity_clear
                    BEFORE DELETE ON temptation_log
                    BEGIN
                        SELECT RAISE(ABORT, 'injected activity clear failure');
                    END
                    """.trimIndent()
                )
            }
        }
    }

    private fun removeGlobalPinHash(dbFile: Path) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM settings WHERE key = 'global_pin_hash'")
            }
        }
    }

    private fun assertActivityCounts(dbFile: Path, expected: Int) {
        listOf(
            "focus_sessions",
            "daily_completions",
            "tasks",
            "daily_notes",
            "temptation_log"
        ).forEach { table ->
            assertEquals(expected, rowCount(dbFile, table), "$table rows")
        }
    }

    private fun assertProtectedDataCounts(dbFile: Path, expectedSettings: Int = 2) {
        mapOf(
            "settings" to expectedSettings,
            "block_rules" to 1,
            "block_schedules" to 1,
            "network_cutoff_rules" to 1,
            "daily_allowances" to 1,
            "daily_usage" to 1,
            "habits" to 1,
            "habit_entries" to 1,
            "custom_block_presets" to 1,
            "focus_launcher_presets" to 1
        ).forEach { (table, expected) ->
            assertEquals(expected, rowCount(dbFile, table), "$table rows must be preserved")
        }
    }

    private fun rowCount(dbFile: Path, table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                    check(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }

    private fun settingValue(dbFile: Path, key: String): String? =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.prepareStatement("SELECT value FROM settings WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString(1) else null
                }
            }
        }
}
