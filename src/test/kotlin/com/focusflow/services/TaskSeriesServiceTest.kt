package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.NativeSqlite
import com.focusflow.data.NativeSqliteTestHooks
import com.focusflow.data.models.Task
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class TaskSeriesServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        AutoBackupService.resetForTest()
        NativeSqlite.testHooksForTest = NativeSqliteTestHooks(
            candidates = emptyList(),
            defaultInitialize = { org.sqlite.SQLiteJDBCLoader.initialize() },
            cacheSuccessfulLoad = true
        )
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
        AutoBackupService.resetForTest()
    }

    private fun d(day: Int, month: Int = 3) = LocalDate.of(2026, month, day)

    /** "Series T" of the plan: a daily series that started Monday 2026-03-02. */
    private fun seriesT() = Task(
        id = "t", title = "Read", scheduledDate = d(2), scheduledTime = "09:00",
        recurring = true, recurringType = "daily", createdAt = LocalDateTime.of(2026, 3, 2, 8, 0)
    )

    private fun copiesOf(parent: String) = Database.getTasks().filter { it.recurringParentId == parent }
    private fun copyOn(parent: String, day: LocalDate) = copiesOf(parent).single { it.scheduledDate == day }
    private fun generate(vararg days: Int) = days.forEach { RecurringTaskService.generateForToday(d(it)) }

    private fun start(name: String) {
        init(tempDir.resolve("$name.db").toFile())
        Database.upsertTask(seriesT())
    }

    @Test
    fun s1DeleteOccurrenceOfACopyDoesNotComeBack() {
        start("s1")
        generate(3)
        val c = copyOn("t", d(3))
        TaskSeriesService.deleteOccurrence(c)

        assertNull(Database.getTaskById(c.id))
        assertEquals(setOf("t"), Database.getRecurringSkipParentIds(d(3)))
        generate(3)
        assertTrue(copiesOf("t").isEmpty(), "the deleted day must not be re-created")
        generate(4)
        assertEquals(listOf(d(4)), copiesOf("t").map { it.scheduledDate })
    }

    @Test
    fun s2DeleteAndStopEndsTheSeriesAndKeepsHistory() {
        start("s2")
        Database.upsertTask(seriesT().copy(completed = true))
        generate(3, 4)
        val c1 = copyOn("t", d(3))
        assertTrue(Database.completeTask(c1.id))
        val c2 = copyOn("t", d(4))

        TaskSeriesService.deleteAndStop(c2, today = d(4))

        val original = Database.getTaskById("t")!!
        assertFalse(original.recurring)
        assertNull(original.recurringType)
        assertNull(Database.getTaskById(c2.id))
        val history = Database.getTaskById(c1.id)!!
        assertTrue(history.completed)
        assertTrue(history.recurring, "history must be left as it was")
        generate(5)
        assertEquals(1, copiesOf("t").size, "no new copy after the series was stopped")
    }

    @Test
    fun s3DeletingTheOriginalStopsTheSeries() {
        start("s3")
        generate(3)
        val c = copyOn("t", d(3))
        TaskSeriesService.deleteOccurrence(seriesT(), today = d(3))

        assertNull(Database.getTaskById("t"))
        assertFalse(Database.getTaskById(c.id)!!.recurring)
        generate(4)
        assertEquals(1, copiesOf("t").size)
    }

    @Test
    fun s4StopRepeatingKeepsEveryRow() {
        start("s4")
        generate(3, 4)
        val c1 = copyOn("t", d(3))
        assertTrue(Database.completeTask(c1.id))
        val c2 = copyOn("t", d(4))

        TaskSeriesService.stopRepeating(c2, today = d(4))

        assertFalse(Database.getTaskById("t")!!.recurring)
        assertNull(Database.getTaskById("t")!!.recurringType)
        assertFalse(Database.getTaskById(c2.id)!!.recurring)
        assertNull(Database.getTaskById(c2.id)!!.recurringType)
        assertTrue(Database.getTaskById(c1.id)!!.recurring)
        assertEquals(3, Database.getTasks().size)
    }

    @Test
    fun s5ThisAndFutureEditUpdatesTheOriginalAndTheNextCopy() {
        init(tempDir.resolve("s5.db").toFile())
        Database.upsertTask(seriesT().copy(completed = true))
        generate(3)
        val c = copyOn("t", d(3))
        val edited = c.copy(title = "Read book", scheduledTime = "19:00", durationMinutes = 40)

        TaskSeriesService.saveEdit(c, edited, SeriesEditScope.THIS_AND_FUTURE, SeriesEdit("daily", null), today = d(3))

        val original = Database.getTaskById("t")!!
        assertEquals("Read book", original.title)
        assertEquals("19:00", original.scheduledTime)
        assertEquals(40, original.durationMinutes)
        assertEquals(d(2), original.scheduledDate)
        assertTrue(original.completed)
        assertEquals(LocalDateTime.of(2026, 3, 2, 8, 0), original.createdAt)
        assertNull(original.recurringParentId)
        assertTrue(original.recurring)
        assertNull(original.recurringUntil)
        val saved = Database.getTaskById(c.id)!!
        assertEquals("Read book", saved.title)
        assertNull(saved.recurringUntil)
        generate(4)
        val next = copyOn("t", d(4))
        assertEquals("Read book", next.title)
        assertEquals("19:00", next.scheduledTime)
        assertEquals(40, next.durationMinutes)
    }

    @Test
    fun s6OnlyThisEditLeavesTheOriginalAndTheNextCopyAlone() {
        start("s6")
        generate(3)
        val c = copyOn("t", d(3))
        val edited = c.copy(title = "Read book", scheduledTime = "19:00", durationMinutes = 40)

        TaskSeriesService.saveEdit(c, edited, SeriesEditScope.ONLY_THIS, null)

        assertEquals("Read", Database.getTaskById("t")!!.title)
        assertEquals("09:00", Database.getTaskById("t")!!.scheduledTime)
        assertEquals("Read book", Database.getTaskById(c.id)!!.title)
        generate(4)
        val next = copyOn("t", d(4))
        assertEquals("Read", next.title)
        assertEquals("09:00", next.scheduledTime)
    }

    @Test
    fun s7MovingACopyLeavesARemovedDayMarkerOnlyWhenTheDateChanges() {
        start("s7")
        generate(3, 4)
        val c = copyOn("t", d(3))
        val dd = copyOn("t", d(4))

        TaskSeriesService.saveEdit(c, c.copy(scheduledDate = d(5)), SeriesEditScope.ONLY_THIS, null)
        TaskSeriesService.saveEdit(dd, dd.copy(scheduledTime = "10:00"), SeriesEditScope.ONLY_THIS, null)

        assertEquals(setOf("t"), Database.getRecurringSkipParentIds(d(3)))
        assertEquals(d(5), Database.getTaskById(c.id)!!.scheduledDate)
        assertTrue(Database.getRecurringSkipParentIds(d(4)).isEmpty(), "a time-only edit must not leave a marker")
        val before = copiesOf("t").size
        generate(3)
        assertEquals(before, copiesOf("t").size, "the day the copy was moved away from must not be re-created")
    }

    @Test
    fun s8EndDateChosenWithThisAndFutureStopsGeneration() {
        start("s8")
        generate(10)
        val c = copyOn("t", d(10))

        TaskSeriesService.saveEdit(c, c, SeriesEditScope.THIS_AND_FUTURE, SeriesEdit("daily", d(12)))

        assertEquals(d(12), Database.getTaskById("t")!!.recurringUntil)
        generate(11, 12, 13)
        assertEquals(listOf(d(10), d(11), d(12)), copiesOf("t").map { it.scheduledDate }.sortedBy { it })
    }

    @Test
    fun s9OrphanCopyDoesNotCrashAndOnlyTheCopyIsSaved() {
        init(tempDir.resolve("s9.db").toFile())
        val c = Task(
            id = "c", title = "Orphan", scheduledDate = d(3), recurring = true,
            recurringType = "daily", recurringParentId = "missing"
        )
        Database.upsertTask(c)

        assertNull(TaskSeriesService.seriesInfo(c))
        TaskSeriesService.saveEdit(c, c.copy(title = "X"), SeriesEditScope.THIS_AND_FUTURE, SeriesEdit("daily", null))

        assertEquals("X", Database.getTaskById("c")!!.title)
        assertNull(Database.getTaskById("missing"))
    }

    @Test
    fun s10UntickingRecurringOnTheOriginalStopsTheSeries() {
        start("s10")
        generate(3)
        val c = copyOn("t", d(3))

        TaskSeriesService.saveEdit(
            seriesT(), seriesT().copy(recurring = false, recurringType = null),
            SeriesEditScope.ONLY_THIS, null, today = d(3)
        )

        assertFalse(Database.getTaskById("t")!!.recurring)
        assertFalse(Database.getTaskById(c.id)!!.recurring)
        generate(4)
        assertEquals(1, copiesOf("t").size)
    }

    @Test
    fun s11ApplyToSeriesCopiesTheDefinitionAndKeepsTheOriginalsIdentity() {
        val done = LocalDateTime.of(2026, 3, 2, 9, 30)
        val created = LocalDateTime.of(2026, 3, 2, 8, 0)
        val template = Task(
            id = "t", title = "A", description = "d0", durationMinutes = 25, scheduledDate = d(2),
            scheduledTime = "09:00", completed = true, completedAt = done, createdAt = created,
            priority = "medium", recurring = true, recurringType = "daily"
        )
        val edited = Task(
            id = "c", title = "B", description = "d1", durationMinutes = 40, scheduledDate = d(9),
            scheduledTime = "19:00", priority = "high", tags = listOf("x"), focusMode = true,
            focusIntensity = "strict", focusBlockedApps = listOf("a.exe"), focusRequirePin = true,
            recurringParentId = "t"
        )

        val result = TaskSeriesService.applyToSeries(template, edited, SeriesEdit("weekdays", d(31)))

        assertEquals("B", result.title); assertEquals("d1", result.description)
        assertEquals(40, result.durationMinutes); assertEquals("19:00", result.scheduledTime)
        assertEquals("high", result.priority); assertEquals(listOf("x"), result.tags)
        assertTrue(result.focusMode); assertEquals("strict", result.focusIntensity)
        assertEquals(listOf("a.exe"), result.focusBlockedApps); assertTrue(result.focusRequirePin)
        assertEquals("weekdays", result.recurringType); assertEquals(d(31), result.recurringUntil)
        assertEquals("t", result.id); assertEquals(d(2), result.scheduledDate)
        assertTrue(result.completed); assertEquals(done, result.completedAt)
        assertEquals(created, result.createdAt); assertTrue(result.recurring); assertNull(result.recurringParentId)
    }

    @Test
    fun s12PureHelpers() {
        assertEquals(SeriesRole.NONE, TaskSeriesService.roleOf(Task(id = "p", title = "p")))
        assertEquals(SeriesRole.TEMPLATE, TaskSeriesService.roleOf(Task(id = "t", title = "t", recurring = true)))
        assertEquals(SeriesRole.COPY, TaskSeriesService.roleOf(Task(id = "c", title = "c", recurring = true, recurringParentId = "t")))
        assertEquals(SeriesRole.COPY, TaskSeriesService.roleOf(Task(id = "c", title = "c", recurring = false, recurringParentId = "t")))

        val start = d(2)
        assertNull(TaskSeriesService.endDateForChip(start, EndChip.NEVER))
        assertEquals(d(8), TaskSeriesService.endDateForChip(start, EndChip.ONE_WEEK))
        assertEquals(d(1, 4), TaskSeriesService.endDateForChip(start, EndChip.ONE_MONTH))
        assertEquals(d(1, 6), TaskSeriesService.endDateForChip(start, EndChip.THREE_MONTHS))

        assertEquals(EndDateResult.Valid(null), TaskSeriesService.resolveEndDate("", start))
        assertEquals(EndDateResult.Valid(d(31)), TaskSeriesService.resolveEndDate("2026-03-31", start))
        assertEquals(EndDateResult.Valid(d(31)), TaskSeriesService.resolveEndDate("  2026-03-31  ", start))
        assertEquals(EndDateResult.Valid(d(2)), TaskSeriesService.resolveEndDate("2026-03-02", start))
        assertEquals(EndDateResult.Invalid(EndDateProblem.BEFORE_START), TaskSeriesService.resolveEndDate("2026-03-01", start))
        assertEquals(EndDateResult.Invalid(EndDateProblem.BAD_FORMAT), TaskSeriesService.resolveEndDate("31/03/2026", start))
    }

    @Test
    fun s13SeriesInfoCountsOccurrences() {
        start("s13")
        generate(3, 4, 5)
        val original = Database.getTaskById("t")!!
        val copy = copiesOf("t").first()
        val plain = Task(id = "plain", title = "Plain")
        Database.upsertTask(plain)

        val fromOriginal = assertNotNull(TaskSeriesService.seriesInfo(original))
        val fromCopy = assertNotNull(TaskSeriesService.seriesInfo(copy))
        assertEquals("t", fromOriginal.template.id)
        assertEquals("t", fromCopy.template.id)
        assertEquals(4, fromOriginal.occurrences)
        assertEquals(4, fromCopy.occurrences)
        assertNull(TaskSeriesService.seriesInfo(plain))
    }

    private fun init(dbFile: java.io.File) {
        assertIs<DbInitResult.Ready>(
            Database.init(
                dbFile = dbFile,
                policy = com.focusflow.data.InitPolicy(
                    busyTimeoutMs = 200, maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 2_000
                ),
                allowRecovery = false
            )
        )
    }
}
