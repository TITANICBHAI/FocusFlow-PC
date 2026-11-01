package com.focusflow.data

import com.focusflow.data.models.Task
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

/** Schema v11: repeating-task end date, removed-day markers and the series queries. */
class DatabaseSeriesTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = emptyList(),
            defaultInitialize = { org.sqlite.SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    private val d0302 = LocalDate.of(2026, 3, 2)

    @Test
    fun b1VersionTenDatabaseUpgradesToElevenAndKeepsRows() {
        val dbFile = tempDir.resolve("v10-to-v11.db").toFile()
        assertIs<DbInitResult.Ready>(init(dbFile))
        Database.upsertTask(series("t"))
        Database.upsertTask(Task(id = "plain", title = "Plain"))
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE tasks DROP COLUMN recurring_until")
                statement.execute("DROP TABLE recurring_skips")
                statement.execute("PRAGMA user_version=10")
            }
        }
        assertEquals(10, userVersion(dbFile))

        assertIs<DbInitResult.Ready>(init(dbFile))
        assertEquals(11, userVersion(dbFile))
        assertEquals(setOf("t", "plain"), Database.getTasks().map { it.id }.toSet())
        assertTrue(columnNames(dbFile, "tasks").contains("recurring_until"))
        Database.addRecurringSkip("t", d0302) // the table exists again
        assertEquals(setOf("t"), Database.getRecurringSkipParentIds(d0302))
    }

    @Test
    fun b2EndDateRoundTripsThroughUpsertAndRead() {
        assertIs<DbInitResult.Ready>(init(tempDir.resolve("b2.db").toFile()))
        val until = LocalDate.of(2026, 5, 1)
        Database.upsertTask(series("with-end").copy(recurringUntil = until))
        Database.upsertTask(series("no-end"))

        assertEquals(until, Database.getTaskById("with-end")!!.recurringUntil)
        assertNull(Database.getTaskById("no-end")!!.recurringUntil)
        val all = Database.getTasks().associateBy { it.id }
        assertEquals(until, all.getValue("with-end").recurringUntil)
        assertNull(all.getValue("no-end").recurringUntil)
    }

    @Test
    fun b3GetTaskByIdReturnsNullForAnUnknownId() {
        assertIs<DbInitResult.Ready>(init(tempDir.resolve("b3.db").toFile()))
        assertNull(Database.getTaskById("nope"))
    }

    @Test
    fun b4CountSeriesOccurrencesCountsOriginalAndItsCopiesOnly() {
        assertIs<DbInitResult.Ready>(init(tempDir.resolve("b4.db").toFile()))
        Database.upsertTask(series("t"))
        repeat(3) { Database.upsertTask(copy("c$it", "t", d0302.plusDays(it + 1L))) }
        Database.upsertTask(copy("other-copy", "u", d0302))
        Database.upsertTask(Task(id = "plain", title = "Plain"))

        assertEquals(4, Database.countSeriesOccurrences("t"))
        assertEquals(1, Database.countSeriesOccurrences("plain"))
        assertEquals(0, Database.countSeriesOccurrences("missing"))
    }

    @Test
    fun b5RemovedDayMarkersAreIdempotentAndPerDate() {
        assertIs<DbInitResult.Ready>(init(tempDir.resolve("b5.db").toFile()))
        val d1 = LocalDate.of(2026, 3, 3)
        val d2 = LocalDate.of(2026, 3, 4)
        Database.addRecurringSkip("t", d1)
        Database.addRecurringSkip("t", d1)
        Database.addRecurringSkip("u", d1)
        Database.addRecurringSkip("t", d2)

        assertEquals(setOf("t", "u"), Database.getRecurringSkipParentIds(d1))
        assertEquals(setOf("t"), Database.getRecurringSkipParentIds(d2))
        assertTrue(Database.getRecurringSkipParentIds(LocalDate.of(2026, 3, 5)).isEmpty())
    }

    @Test
    fun b6StopRecurringSeriesOnlyTouchesTheOriginalAndOpenCopiesFromTheGivenDate() {
        assertIs<DbInitResult.Ready>(init(tempDir.resolve("b6.db").toFile()))
        Database.upsertTask(series("t"))
        Database.upsertTask(copy("c0301", "t", LocalDate.of(2026, 3, 1)))
        Database.upsertTask(copy("c0302", "t", LocalDate.of(2026, 3, 2)).copy(completed = true))
        Database.upsertTask(copy("c0303", "t", LocalDate.of(2026, 3, 3)))
        Database.upsertTask(copy("c0304", "t", LocalDate.of(2026, 3, 4)))
        Database.upsertTask(copy("c0303s", "t", LocalDate.of(2026, 3, 3)).copy(skipped = true))
        Database.upsertTask(copy("u0303", "u", LocalDate.of(2026, 3, 3)))
        val rowsBefore = Database.getTasks().size

        Database.stopRecurringSeries("t", LocalDate.of(2026, 3, 3))

        fun stopped(id: String) = Database.getTaskById(id)!!.let { !it.recurring && it.recurringType == null }
        fun untouched(id: String) = Database.getTaskById(id)!!.let { it.recurring && it.recurringType == "daily" }
        assertTrue(stopped("t"))
        assertTrue(stopped("c0303"))
        assertTrue(stopped("c0304"))
        assertTrue(untouched("c0301"), "past open copy must be untouched")
        assertTrue(untouched("c0302"), "completed copy must be untouched")
        assertTrue(untouched("c0303s"), "skipped copy must be untouched")
        assertTrue(untouched("u0303"), "another series must be untouched")
        assertEquals(rowsBefore, Database.getTasks().size, "nothing may be deleted")
    }

    @Test
    fun b7ClearFunctionsAlsoEmptyTheRemovedDayMarkers() {
        assertIs<DbInitResult.Ready>(init(tempDir.resolve("b7.db").toFile()))
        Database.addRecurringSkip("t", d0302)
        Database.clearAllTasks()
        assertTrue(Database.getRecurringSkipParentIds(d0302).isEmpty())

        Database.addRecurringSkip("t", d0302)
        Database.clearUserActivity()
        assertTrue(Database.getRecurringSkipParentIds(d0302).isEmpty())
    }

    @Test
    fun b8SchemaGuardRequiresTheEndDateColumn() {
        val dbFile = tempDir.resolve("b8.db").toFile()
        assertIs<DbInitResult.Ready>(init(dbFile))
        Database.close()
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE tasks DROP COLUMN recurring_until")
                statement.execute("PRAGMA user_version=11")
            }
        }
        val failed = assertIs<DbInitResult.Failed>(init(dbFile))
        val mismatch = assertIs<SchemaMismatchException>(failed.cause)
        assertEquals("tasks.recurring_until", mismatch.missingColumn)
    }

    private fun series(id: String) = Task(
        id = id, title = "Read", scheduledDate = d0302, scheduledTime = "09:00",
        recurring = true, recurringType = "daily"
    )

    private fun copy(id: String, parent: String, date: LocalDate) = Task(
        id = id, title = "Read", scheduledDate = date, scheduledTime = "09:00",
        recurring = true, recurringType = "daily", recurringParentId = parent
    )

    private fun init(dbFile: java.io.File): DbInitResult = Database.init(
        dbFile = dbFile,
        policy = InitPolicy(busyTimeoutMs = 200, maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 2_000),
        allowRecovery = false
    )

    private fun userVersion(dbFile: java.io.File): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rs -> assertTrue(rs.next()); rs.getInt(1) }
            }
        }

    private fun columnNames(dbFile: java.io.File, table: String): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { rs ->
                    val names = mutableSetOf<String>()
                    while (rs.next()) names += rs.getString("name")
                    names
                }
            }
        }
}
