package com.focusflow.data

import com.focusflow.AppPaths
import com.focusflow.data.models.FocusSession
import com.focusflow.services.AutoBackupService
import com.focusflow.services.DatabaseRepairService
import com.focusflow.testutil.TempHome
import java.io.IOException
import java.sql.DriverManager
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrphanFocusSessionReconciliationTest {

    @Test
    fun oldUnfinishedSessionDoesNotSatisfyTheTwelveHourGate() = withDatabase {
        val now = LocalDateTime.of(2026, 10, 15, 12, 0)
        Database.insertSession(session("old", now.minusHours(12).minusSeconds(1)))

        assertFalse(Database.hasUnfinishedFocusSession(now))
    }

    @Test
    fun dryRunReportsOrphansWithoutChangingTheirRows() = withDatabase {
        val now = LocalDateTime.of(2026, 10, 15, 12, 0)
        Database.insertSession(session("older", now.minusDays(3)))
        Database.insertSession(session("newer", now.minusHours(1)))

        val dryRun = assertIs<DatabaseRepairService.Outcome.DryRun<*>>(
            DatabaseRepairService.reconcileOrphanFocusSessions(dryRun = true)
        )
        assertEquals(2, dryRun.value)
        assertTrue(AutoBackupService.listBackups().isEmpty(), "A dry-run must not create a backup")
        val rows = Database.getRecentSessions(10).associateBy { it.id }
        assertEquals(2, rows.size)
        assertTrue(rows.values.all { it.endTime == null && !it.interrupted })
    }

    @Test
    fun reconciliationMarksRowsAtStartTimeAndIsIdempotent() = withDatabase {
        val now = LocalDateTime.of(2026, 10, 15, 12, 0)
        val orphanStart = now.minusDays(3)
        Database.insertSession(session("orphan", orphanStart))
        Database.insertSession(session("completed", now.minusDays(2), completed = true))

        AutoBackupService.snapshotWriterForTest = {
            throw IOException("injected backup failure")
        }
        assertIs<DatabaseRepairService.Outcome.BackupFailed>(
            DatabaseRepairService.reconcileOrphanFocusSessions(dryRun = false)
        )
        assertTrue(
            Database.getRecentSessions(10).all { it.endTime == null && !it.interrupted },
            "A failed backup must leave session rows unchanged"
        )

        AutoBackupService.snapshotWriterForTest = null
        val applied = assertIs<DatabaseRepairService.Outcome.Applied<*>>(
            DatabaseRepairService.reconcileOrphanFocusSessions(dryRun = false)
        )
        assertEquals(1, applied.value)
        assertIs<AutoBackupService.BackupVerification.Verified>(
            AutoBackupService.verifyBackup(applied.backupFile)
        )
        val snapshotOrphan = DriverManager.getConnection(
            "jdbc:sqlite:${applied.backupFile.absolutePath}"
        ).use { connection ->
            connection.prepareStatement(
                "SELECT end_time, interrupted FROM focus_sessions WHERE id = ?"
            ).use { statement ->
                statement.setString(1, "orphan")
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getString("end_time") to resultSet.getInt("interrupted")
                }
            }
        }
        assertEquals(null, snapshotOrphan.first)
        assertEquals(0, snapshotOrphan.second, "The verified snapshot must precede reconciliation")
        assertEquals(0, assertIs<DatabaseRepairService.Outcome.Applied<*>>(
            DatabaseRepairService.reconcileOrphanFocusSessions(dryRun = false)
        ).value)

        val rows = Database.getRecentSessions(10).associateBy { it.id }
        val repaired = assertNotNull(rows["orphan"])
        assertEquals(orphanStart, repaired.endTime)
        assertTrue(repaired.interrupted)
        assertEquals(null, rows["completed"]?.endTime)
        assertFalse(rows.getValue("completed").interrupted)
    }

    @Test
    fun sessionAtTheTwelveHourBoundaryStillSatisfiesTheGate() = withDatabase {
        val now = LocalDateTime.of(2026, 10, 15, 12, 0)
        Database.insertSession(session("boundary", now.minusHours(12)))

        assertTrue(Database.hasUnfinishedFocusSession(now))
    }

    private fun withDatabase(test: () -> Unit) {
        TempHome.withHome { _ ->
            AutoBackupService.resetForTest()
            AppPaths.dataDir.mkdirs()
            assertIs<DbInitResult.Ready>(
                Database.init(dbFile = AppPaths.dbFile, allowRecovery = false)
            )
            try {
                test()
            } finally {
                AutoBackupService.resetForTest()
            }
        }
    }

    private fun session(
        id: String,
        startTime: LocalDateTime,
        completed: Boolean = false
    ) = FocusSession(
        id = id,
        taskId = null,
        taskName = "Test session",
        startTime = startTime,
        endTime = null,
        plannedMinutes = 25,
        actualMinutes = 0,
        completed = completed,
        interrupted = false
    )
}
