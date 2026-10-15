package com.focusflow.data

import com.focusflow.data.models.DayCompletionStats
import com.focusflow.data.models.DayFocusStats
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class DayQueriesTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var dbFile: Path
    private var previousUserHome: String? = null
    private var previousTelemetry: String? = null

    @BeforeEach
    fun createIsolatedDatabase() {
        Database.resetForTest()
        previousUserHome = System.getProperty("user.home")
        previousTelemetry = System.getProperty("focusflow.telemetry")
        val profileHome = Files.createDirectories(tempDir.resolve("profile-home"))
        System.setProperty("user.home", profileHome.toString())
        System.setProperty("focusflow.telemetry", "off")
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = emptyList(),
            defaultInitialize = { org.sqlite.SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )

        dbFile = tempDir.resolve("day-queries.db")
        assertIs<DbInitResult.Ready>(Database.init(dbFile.toFile(), allowRecovery = false))
    }

    @AfterEach
    fun closeIsolatedDatabase() {
        Database.resetForTest()
        restoreProperty("user.home", previousUserHome)
        restoreProperty("focusflow.telemetry", previousTelemetry)
    }

    @Test
    fun f20a_randomizedDateRangesMatchLegacySqlIncludingBoundaries() {
        val start = LocalDate.of(2025, 2, 26)
        val end = start.plusDays(6)
        val random = Random(0xF20)
        val sessionRows = mutableListOf<SessionFixture>()

        sessionRows += SessionFixture("before-start", start.minusDays(1).atTime(LocalTime.MAX), true, 20)
        sessionRows += SessionFixture("start-midnight", start.atStartOfDay(), true, 25)
        sessionRows += SessionFixture(
            "inside-midnight",
            start.plusDays(1).atStartOfDay().minusNanos(1),
            true,
            30
        )
        sessionRows += SessionFixture("end-last-nano", end.atTime(LocalTime.MAX), true, 35)
        sessionRows += SessionFixture("after-end", end.plusDays(1).atStartOfDay(), true, 40)
        repeat(240) { index ->
            val date = start.plusDays(random.nextInt(-1, 9).toLong())
            val time = date.atStartOfDay().plusNanos(random.nextLong(NANOS_PER_DAY))
            sessionRows += SessionFixture(
                "random-session-$index",
                time,
                completed = random.nextBoolean(),
                actualMinutes = random.nextInt(0, 181)
            )
        }
        insertSessions(sessionRows)

        val taskTimes = buildList {
            add(start.atStartOfDay())
            add(end.atTime(LocalTime.MAX))
            add(end.plusDays(1).atStartOfDay())
            repeat(120) {
                val date = start.plusDays(random.nextInt(-1, 9).toLong())
                add(date.atStartOfDay().plusNanos(random.nextLong(NANOS_PER_DAY)))
            }
        }
        insertCompletedTasks(taskTimes)

        val temptationTimes = buildList {
            add(start.atStartOfDay())
            add(end.atTime(LocalTime.MAX))
            add(end.plusDays(1).atStartOfDay())
            repeat(120) {
                val date = start.plusDays(random.nextInt(-1, 9).toLong())
                add(date.atStartOfDay().plusNanos(random.nextLong(NANOS_PER_DAY)))
            }
        }
        insertTemptations(temptationTimes)

        val startText = start.toString()
        val endText = end.toString()
        assertEquals(
            legacySessionIds(startText, endText, ascending = false),
            Database.getSessionsInDateRange(start, end).map { it.id }
        )
        assertEquals(
            legacySessionIds(startText, endText, ascending = true),
            Database.getSessionsInRange(startText, endText).map { it.id }
        )
        assertEquals(
            legacyCompletedTaskCount(startText, endText),
            Database.getCompletedTasksInRange(startText, endText)
        )
        assertEquals(
            legacyTemptationCount(startText, endText),
            Database.getTemptationsInRange(startText, endText)
        )
        assertEquals(
            legacyTemptationBreakdown(startText, endText).toMap(),
            Database.getTemptationBreakdownInRange(startText, endText).toMap()
        )

        for (date in generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }) {
            assertEquals(
                legacySessionIds(date.toString(), date.toString(), ascending = false),
                Database.getSessionsInDateRange(date, date).map { it.id },
                "session range for $date"
            )
            val expectedMinutes = legacyDailyCompletedMinutes(date)
            Database.updateDailyFocusMinutes(date)
            assertEquals(expectedMinutes, storedDailyFocusMinutes(date), "daily total for $date")
        }
    }

    @Test
    fun f20bRangeAndGroupingQueriesUseExistingTimestampIndexes() {
        val start = LocalDate.of(2025, 1, 1)
        val endExclusive = start.plusDays(8)
        val sessionPlan = explainQueryPlan(
            Database.FOCUS_MINUTES_BY_DAY_SQL,
            start.toString(),
            endExclusive.toString()
        )
        val temptationPlan = explainQueryPlan(
            Database.TEMPTATIONS_IN_RANGE_SQL,
            start.toString(),
            endExclusive.toString()
        )

        assertTrue(
            sessionPlan.any { "idx_sessions_start" in it },
            "expected indexed session range plan, got: $sessionPlan"
        )
        assertTrue(
            temptationPlan.any { "idx_temptation_ts" in it },
            "expected indexed temptation range plan, got: $temptationPlan"
        )
    }

    @Test
    fun f20cGroupedDailyQueriesMatchLegacyResultsAndFillMissingDates() {
        val today = LocalDate.now()
        val sessions = buildList {
            repeat(7) { daysAgo ->
                val date = today.minusDays(daysAgo.toLong())
                add(SessionFixture("completed-$daysAgo", date.atStartOfDay(), true, 15 + daysAgo))
                add(SessionFixture("incomplete-$daysAgo", date.atTime(12, 0), false, 99))
                if (daysAgo % 2 == 0) {
                    add(SessionFixture("zero-$daysAgo", date.atTime(13, 0), true, 0))
                }
            }
        }
        insertSessions(sessions)

        val expectedFocus = (6 downTo 0).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            val (minutes, count) = legacyFocusMinutesByDay(date)
            DayFocusStats(date, minutes, count)
        }
        assertEquals(expectedFocus, Database.getFocusMinutesByDay(7))

        val completionRows = listOf(today, today.minusDays(2), today.minusDays(6))
        withWritableDatabase { connection ->
            connection.prepareStatement(
                "INSERT INTO daily_completions(date, completed_count, total_count, focus_minutes) VALUES (?, ?, ?, ?)"
            ).use { statement ->
                completionRows.forEachIndexed { index, date ->
                    statement.setString(1, date.toString())
                    statement.setInt(2, index + 1)
                    statement.setInt(3, index + 5)
                    statement.setInt(4, index * 20)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        val expectedCompletions = (6 downTo 0).map { daysAgo ->
            val date = today.minusDays(daysAgo.toLong())
            legacyDayCompletion(date)
        }
        assertEquals(expectedCompletions, Database.getRecentDayCompletions(7))
    }

    @Test
    fun f20dTotalFocusMinutesIsZeroForAnEmptySessionTable() {
        assertEquals(0, Database.getTotalFocusMinutesToday())
    }

    private data class SessionFixture(
        val id: String,
        val startTime: LocalDateTime,
        val completed: Boolean,
        val actualMinutes: Int
    )

    private fun insertSessions(rows: List<SessionFixture>) {
        withWritableDatabase { connection ->
            connection.prepareStatement(
                """
                INSERT INTO focus_sessions
                    (id, task_name, start_time, planned_minutes, actual_minutes, completed)
                VALUES (?, 'Test focus', ?, 25, ?, ?)
                """.trimIndent()
            ).use { statement ->
                rows.forEach { row ->
                    statement.setString(1, row.id)
                    statement.setString(2, row.startTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    statement.setInt(3, row.actualMinutes)
                    statement.setInt(4, if (row.completed) 1 else 0)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun insertCompletedTasks(times: List<LocalDateTime>) {
        withWritableDatabase { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tasks(id, title, created_at, completed, completed_at)
                VALUES (?, 'Date query test', ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                times.forEachIndexed { index, time ->
                    statement.setString(1, "task-$index")
                    statement.setString(2, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    val completed = index % 3 != 0
                    statement.setInt(3, if (completed) 1 else 0)
                    statement.setString(
                        4,
                        if (completed) time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) else null
                    )
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun insertTemptations(times: List<LocalDateTime>) {
        withWritableDatabase { connection ->
            connection.prepareStatement(
                "INSERT INTO temptation_log(process_name, display_name, timestamp) VALUES (?, ?, ?)"
            ).use { statement ->
                times.forEachIndexed { index, time ->
                    statement.setString(1, "process-$index.exe")
                    statement.setString(2, listOf("Alpha", "Beta", "Gamma", "Delta")[index % 4])
                    statement.setString(3, time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun legacySessionIds(start: String, end: String, ascending: Boolean): List<String> =
        withWritableDatabase { connection ->
            val order = if (ascending) "ASC" else "DESC"
            connection.prepareStatement(
                "SELECT id FROM focus_sessions WHERE DATE(start_time) BETWEEN ? AND ? ORDER BY start_time $order"
            ).use { statement ->
                statement.setString(1, start)
                statement.setString(2, end)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
            }
        }

    private fun legacyCompletedTaskCount(start: String, end: String): Int =
        legacyInt(
            "SELECT COUNT(*) FROM tasks WHERE completed = 1 AND DATE(completed_at) BETWEEN ? AND ?",
            start,
            end
        )

    private fun legacyTemptationCount(start: String, end: String): Int =
        legacyInt(
            "SELECT COUNT(*) FROM temptation_log WHERE DATE(timestamp) BETWEEN ? AND ?",
            start,
            end
        )

    private fun legacyTemptationBreakdown(start: String, end: String): List<Pair<String, Int>> =
        withWritableDatabase { connection ->
            connection.prepareStatement(
                """
                SELECT display_name, COUNT(*) AS cnt
                FROM temptation_log
                WHERE DATE(timestamp) BETWEEN ? AND ?
                GROUP BY display_name
                ORDER BY cnt DESC
                LIMIT 5
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, start)
                statement.setString(2, end)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString("display_name") to resultSet.getInt("cnt"))
                        }
                    }
                }
            }
        }

    private fun legacyDailyCompletedMinutes(date: LocalDate): Int =
        legacyInt(
            "SELECT COALESCE(SUM(actual_minutes), 0) FROM focus_sessions WHERE DATE(start_time) = ? AND completed = 1 AND actual_minutes > 0",
            date.toString()
        )

    private fun legacyFocusMinutesByDay(date: LocalDate): Pair<Int, Int> =
        withWritableDatabase { connection ->
            connection.prepareStatement(
                "SELECT COALESCE(SUM(actual_minutes), 0), COUNT(*) FROM focus_sessions WHERE DATE(start_time) = ? AND completed = 1"
            ).use { statement ->
                statement.setString(1, date.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) to resultSet.getInt(2) else 0 to 0
                }
            }
        }

    private fun legacyDayCompletion(date: LocalDate): DayCompletionStats =
        withWritableDatabase { connection ->
            connection.prepareStatement(
                "SELECT completed_count, total_count, focus_minutes FROM daily_completions WHERE date = ?"
            ).use { statement ->
                statement.setString(1, date.toString())
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        DayCompletionStats(
                            date,
                            resultSet.getInt("completed_count"),
                            resultSet.getInt("total_count"),
                            resultSet.getInt("focus_minutes")
                        )
                    } else {
                        DayCompletionStats(date, 0, 0, 0)
                    }
                }
            }
        }

    private fun storedDailyFocusMinutes(date: LocalDate): Int? =
        withWritableDatabase { connection ->
            connection.prepareStatement("SELECT focus_minutes FROM daily_completions WHERE date = ?").use {
                it.setString(1, date.toString())
                it.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else null
                }
            }
        }

    private fun legacyInt(sql: String, start: String, end: String? = null): Int =
        withWritableDatabase { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, start)
                end?.let { statement.setString(2, it) }
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else 0
                }
            }
        }

    private fun explainQueryPlan(sql: String, start: String, endExclusive: String): List<String> =
        withWritableDatabase { connection ->
            connection.prepareStatement("EXPLAIN QUERY PLAN $sql").use { statement ->
                statement.setString(1, start)
                statement.setString(2, endExclusive)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(4))
                    }
                }
            }
        }

    private fun <T> withWritableDatabase(block: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use(block)

    private fun restoreProperty(key: String, previousValue: String?) {
        if (previousValue == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, previousValue)
        }
    }

    private companion object {
        const val NANOS_PER_DAY = 86_400_000_000_000L
    }
}
