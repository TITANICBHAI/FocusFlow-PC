package com.focusflow.data

import com.focusflow.data.models.FocusSession
import com.focusflow.testutil.TempHome
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

        assertEquals(2, Database.reconcileOrphanFocusSessions(dryRun = true))
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

        assertEquals(1, Database.reconcileOrphanFocusSessions(dryRun = false))
        assertEquals(0, Database.reconcileOrphanFocusSessions(dryRun = false))

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
        TempHome.withHome { home ->
            val dbFile = home.resolve("focusflow.db").toFile()
            assertIs<DbInitResult.Ready>(
                Database.init(dbFile = dbFile, allowRecovery = false)
            )
            test()
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
