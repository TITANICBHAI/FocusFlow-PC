package com.focusflow.services

import com.focusflow.AppPaths
import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.NativeSqlite
import com.focusflow.data.NativeSqliteTestHooks
import com.focusflow.data.models.Task
import com.focusflow.testutil.TempHome
import java.nio.file.Path
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class RecurringTaskServiceTest {
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

    @Test
    fun f6aDailyWeekdayAndWeeklyTemplatesGenerateOneCopyPerEligibleDay() {
        val dbFile = tempDir.resolve("recurring-generation.db").toFile()
        init(dbFile)

        val firstDay = LocalDate.of(2026, 3, 2) // Monday
        val templates = listOf(
            recurringTask("daily-template", "Daily", "daily", scheduledDate = null),
            recurringTask("weekday-template", "Weekday", "weekdays", scheduledDate = null),
            recurringTask("weekly-template", "Weekly", "weekly", scheduledDate = firstDay.minusWeeks(1))
        )
        templates.forEach(Database::upsertTask)

        repeat(30) { offset ->
            RecurringTaskService.generateForToday(firstDay.plusDays(offset.toLong()))
        }

        val copies = Database.getTasks().filter { it.recurringParentId != null }
        val expectedWeekdays = (0 until 30).count { offset ->
            firstDay.plusDays(offset.toLong()).dayOfWeek !in
                listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }
        val expectedWeekly = (0 until 30).count { offset ->
            firstDay.plusDays(offset.toLong()).dayOfWeek == firstDay.dayOfWeek
        }

        assertEquals(30, copies.count { it.recurringParentId == "daily-template" })
        assertEquals(expectedWeekdays, copies.count { it.recurringParentId == "weekday-template" })
        assertEquals(expectedWeekly, copies.count { it.recurringParentId == "weekly-template" })
        assertEquals(3, Database.getRecurringTemplates().size)
        assertEquals(
            copies.size,
            copies.map { it.recurringParentId to it.scheduledDate }.toSet().size,
            "Each template should produce no more than one copy on a date"
        )
    }

    @Test
    fun f6bCallingGenerationTwiceForOneDateDoesNotAddAnotherCopy() {
        val dbFile = tempDir.resolve("recurring-idempotent.db").toFile()
        init(dbFile)
        Database.upsertTask(recurringTask("daily-template", "Daily", "daily", scheduledDate = null))
        val date = LocalDate.of(2026, 4, 8)

        RecurringTaskService.generateForToday(date)
        val countAfterFirstCall = Database.getTasks().size
        RecurringTaskService.generateForToday(date)

        assertEquals(countAfterFirstCall, Database.getTasks().size)
        assertEquals(1, Database.getTasksForDate(date).count { it.recurringParentId == "daily-template" })
    }

    @Test
    fun f6cRepairDryRunIsReadOnlyAndMarkingIsNonDestructiveAndIdempotent() {
        TempHome.withHome {
            AppPaths.dataDir.mkdirs()
            init(AppPaths.dbFile)
            val oldDate = LocalDate.of(2020, 1, 2)
            val tasks = listOf(
                recurringTask(
                    "template",
                    "  Reading ",
                    "daily",
                    scheduledDate = null,
                    scheduledTime = "08:00",
                    createdAt = LocalDateTime.of(2020, 1, 1, 8, 0)
                ),
                recurringTask(
                    "copy-1",
                    "reading",
                    "DAILY",
                    scheduledDate = oldDate,
                    scheduledTime = "08:00",
                    createdAt = LocalDateTime.of(2020, 1, 2, 8, 0)
                ),
                recurringTask(
                    "copy-2",
                    "READING ",
                    "Daily",
                    scheduledDate = oldDate.plusDays(1),
                    scheduledTime = "08:00",
                    createdAt = LocalDateTime.of(2020, 1, 3, 8, 0)
                ),
                recurringTask(
                    "separate-template",
                    "Reading",
                    "weekly",
                    scheduledDate = LocalDate.of(2019, 12, 31),
                    scheduledTime = "08:00",
                    createdAt = LocalDateTime.of(2019, 12, 31, 8, 0)
                )
            )
            tasks.forEach(Database::upsertTask)

            val dryRun = assertIs<DatabaseRepairService.Outcome.DryRun<*>>(
                DatabaseRepairService.repairRecurringTaskParents()
            ).value as Database.RecurringTaskRepairResult
            assertTrue(dryRun.dryRun)
            assertEquals(1, dryRun.duplicateGroups)
            assertEquals(2, dryRun.rowsToMark)
            assertEquals(0, dryRun.rowsMarked)
            assertEquals(4, Database.getTasks().size)
            assertTrue(Database.getTasks().all { it.recurringParentId == null })
            assertTrue(AutoBackupService.listBackups().isEmpty(), "A dry-run must not create a backup")

            AutoBackupService.snapshotWriterForTest = {
                throw IOException("injected backup failure")
            }
            val blocked = assertIs<DatabaseRepairService.Outcome.BackupFailed>(
                DatabaseRepairService.repairRecurringTaskParents(dryRun = false)
            )
            assertIs<AutoBackupService.BackupResult.Error>(blocked.result)
            assertTrue(Database.getTasks().all { it.recurringParentId == null })

            AutoBackupService.snapshotWriterForTest = null
            val applied = assertIs<DatabaseRepairService.Outcome.Applied<*>>(
                DatabaseRepairService.repairRecurringTaskParents(dryRun = false)
            )
            val appliedResult = applied.value as Database.RecurringTaskRepairResult
            assertEquals(2, appliedResult.rowsMarked)
            assertIs<AutoBackupService.BackupVerification.Verified>(
                AutoBackupService.verifyBackup(applied.backupFile)
            )
            val copiedRowsBeforeRepair = DriverManager.getConnection(
                "jdbc:sqlite:${applied.backupFile.absolutePath}"
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM tasks WHERE recurring_parent_id IS NULL"
                    ).use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getInt(1)
                    }
                }
            }
            assertEquals(4, copiedRowsBeforeRepair, "The verified snapshot must precede the repair")

            val afterRepair = Database.getTasks().associateBy { it.id }
            assertEquals("template", afterRepair.getValue("copy-1").recurringParentId)
            assertEquals("template", afterRepair.getValue("copy-2").recurringParentId)
            assertNull(afterRepair.getValue("template").recurringParentId)
            assertEquals(4, afterRepair.size, "Repair must mark rows, never delete them")
            assertEquals(2, Database.getRecurringTemplates().size)

            val repeated = assertIs<DatabaseRepairService.Outcome.Applied<*>>(
                DatabaseRepairService.repairRecurringTaskParents(dryRun = false)
            ).value as Database.RecurringTaskRepairResult
            assertEquals(0, repeated.rowsMarked)

            val today = LocalDate.of(2026, 4, 8)
            RecurringTaskService.generateForToday(today)
            val countAfterGeneration = Database.getTasks().size
            RecurringTaskService.generateForToday(today)
            assertEquals(countAfterGeneration, Database.getTasks().size)
        }
    }

    @Test
    fun f6dVersionNineMigrationPreservesRowsAndLeavesParentIdsNull() {
        val dbFile = tempDir.resolve("recurring-v9-migration.db").toFile()
        init(dbFile)
        val existingTasks = listOf(
            recurringTask("legacy-template", "Legacy recurring", "daily", scheduledDate = null),
            Task(id = "ordinary-task", title = "Ordinary")
        )
        existingTasks.forEach(Database::upsertTask)
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE tasks DROP COLUMN recurring_parent_id")
                statement.execute("PRAGMA user_version=9")
            }
        }

        assertEquals(9, userVersion(dbFile))
        init(dbFile)

        assertEquals(11, userVersion(dbFile))
        val migrated = Database.getTasks().associateBy { it.id }
        assertEquals(existingTasks.map { it.id }.toSet(), migrated.keys)
        assertNull(migrated.getValue("legacy-template").recurringParentId)
        assertNull(migrated.getValue("ordinary-task").recurringParentId)
        assertTrue(Database.getRecurringTemplates().single().id == "legacy-template")
    }

    @Test
    fun g1CopiesAreNotCreatedBeforeTheTemplateStartDate() {
        init(tempDir.resolve("g1.db").toFile())
        Database.upsertTask(recurringTask("t1", "Read", "daily", scheduledDate = LocalDate.of(2026, 3, 10)))
        listOf(9, 10, 11).forEach { RecurringTaskService.generateForToday(LocalDate.of(2026, 3, it)) }
        assertEquals(listOf(LocalDate.of(2026, 3, 11)), copiesOf("t1").map { it.scheduledDate })
    }

    @Test
    fun g2CopiesStopAfterTheEndDateInclusive() {
        init(tempDir.resolve("g2.db").toFile())
        Database.upsertTask(
            recurringTask("t2", "Read", "daily", scheduledDate = LocalDate.of(2026, 3, 1))
                .copy(recurringUntil = LocalDate.of(2026, 3, 5))
        )
        (2..8).forEach { RecurringTaskService.generateForToday(LocalDate.of(2026, 3, it)) }
        assertEquals((2..5).map { LocalDate.of(2026, 3, it) }, copiesOf("t2").map { it.scheduledDate }.sortedBy { it })
    }

    @Test
    fun g3RemovedDayMarkerBlocksOnlyThatDate() {
        init(tempDir.resolve("g3.db").toFile())
        Database.upsertTask(recurringTask("t3", "Read", "daily", scheduledDate = null))
        Database.addRecurringSkip("t3", LocalDate.of(2026, 3, 3))
        listOf(2, 3, 4).forEach { RecurringTaskService.generateForToday(LocalDate.of(2026, 3, it)) }
        assertEquals(
            listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 4)),
            copiesOf("t3").map { it.scheduledDate }.sortedBy { it }
        )
    }

    @Test
    fun g4WeeklyTemplateWithoutDateUsesCreationWeekday() {
        init(tempDir.resolve("g4.db").toFile())
        // createdAt 2025-01-01 is a Wednesday.
        Database.upsertTask(recurringTask("t4", "Read", "weekly", scheduledDate = null))
        (0 until 14).forEach { RecurringTaskService.generateForToday(LocalDate.of(2026, 3, 2).plusDays(it.toLong())) }
        assertEquals(
            listOf(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 11)),
            copiesOf("t4").map { it.scheduledDate }.sortedBy { it }
        )
    }

    @Test
    fun g5GeneratedCopiesNeverCarryAnEndDate() {
        init(tempDir.resolve("g5.db").toFile())
        val until = LocalDate.of(2026, 3, 31)
        Database.upsertTask(recurringTask("t5", "Read", "daily", scheduledDate = null).copy(recurringUntil = until))
        RecurringTaskService.generateForToday(LocalDate.of(2026, 3, 2))
        val copy = copiesOf("t5").single()
        assertEquals("t5", copy.recurringParentId)
        assertTrue(copy.recurring)
        assertNull(copy.recurringUntil)
        assertEquals(until, Database.getTaskById("t5")!!.recurringUntil)
    }

    @Test
    fun g6ShouldGenerateOnRuleTable() {
        val mon = LocalDate.of(2026, 3, 2)
        val sat = LocalDate.of(2026, 3, 7)
        fun t(type: String?, date: LocalDate? = null, until: LocalDate? = null) =
            Task(id = "x", title = "x", recurring = true, recurringType = type, scheduledDate = date, recurringUntil = until)
        assertFalse(RecurringTaskService.shouldGenerateOn(t("weekdays"), sat))
        assertTrue(RecurringTaskService.shouldGenerateOn(t("weekdays"), mon))
        val ends = t("daily", until = LocalDate.of(2026, 3, 5))
        assertTrue(RecurringTaskService.shouldGenerateOn(ends, LocalDate.of(2026, 3, 5)))
        assertFalse(RecurringTaskService.shouldGenerateOn(ends, LocalDate.of(2026, 3, 6)))
        val starts = t("daily", date = LocalDate.of(2026, 3, 10))
        assertFalse(RecurringTaskService.shouldGenerateOn(starts, LocalDate.of(2026, 3, 9)))
        assertTrue(RecurringTaskService.shouldGenerateOn(starts, LocalDate.of(2026, 3, 10)))
        assertFalse(RecurringTaskService.shouldGenerateOn(t("monthly"), mon))
        assertFalse(RecurringTaskService.shouldGenerateOn(t(null), mon))
    }

    private fun repeatCopy(id: String, day: Int, completed: Boolean = false, skipped: Boolean = false) = Task(
        id = id, title = "Read", scheduledDate = LocalDate.of(2026, 3, day), recurring = true,
        recurringType = "daily", recurringParentId = "t", completed = completed, skipped = skipped
    )

    @Test
    fun e1ExpireClosesOnlyOpenRepeatCopiesOlderThanTheCutoff() {
        init(tempDir.resolve("e1.db").toFile())
        listOf(
            repeatCopy("r1", 1), repeatCopy("r2", 2, completed = true), repeatCopy("r3", 3, skipped = true),
            repeatCopy("r4", 8), repeatCopy("r5", 9), repeatCopy("r6", 10),
            Task(id = "plain", title = "Plain", scheduledDate = LocalDate.of(2026, 3, 1)),
            Task(id = "original", title = "Read", scheduledDate = LocalDate.of(2026, 3, 1), recurring = true, recurringType = "daily")
        ).forEach(Database::upsertTask)

        assertEquals(2, Database.expireStaleSeriesCopies(LocalDate.of(2026, 3, 9)))

        fun skipped(id: String) = Database.getTaskById(id)!!.skipped
        assertTrue(skipped("r1")); assertTrue(skipped("r4"))
        assertFalse(skipped("r2")); assertTrue(Database.getTaskById("r2")!!.completed)
        assertTrue(skipped("r3"))   // it already was
        assertFalse(skipped("r5")); assertFalse(skipped("r6"))
        assertFalse(skipped("plain")); assertFalse(skipped("original"))
        assertEquals(0, Database.expireStaleSeriesCopies(LocalDate.of(2026, 3, 9)))
    }

    @Test
    fun e2ServiceKeepsYesterdayOpenAsAOneDayGrace() {
        init(tempDir.resolve("e2.db").toFile())
        listOf(7, 8, 9, 10).forEach { Database.upsertTask(repeatCopy("r$it", it)) }

        assertEquals(2, RecurringTaskService.expireStaleCopies(today = LocalDate.of(2026, 3, 10)))

        assertTrue(Database.getTaskById("r7")!!.skipped); assertTrue(Database.getTaskById("r8")!!.skipped)
        assertFalse(Database.getTaskById("r9")!!.skipped); assertFalse(Database.getTaskById("r10")!!.skipped)
    }

    @Test
    fun e3GenerationAloneNeverClosesMissedRepeats() {
        init(tempDir.resolve("e3.db").toFile())
        Database.upsertTask(recurringTask("t", "Read", "daily", scheduledDate = LocalDate.of(2026, 3, 2)))
        Database.upsertTask(repeatCopy("stale", 1))

        RecurringTaskService.generateForToday(LocalDate.of(2026, 3, 10))

        assertFalse(Database.getTaskById("stale")!!.skipped)
    }

    private fun copiesOf(parentId: String): List<Task> =
        Database.getTasks().filter { it.recurringParentId == parentId }

    private fun recurringTask(
        id: String,
        title: String,
        recurringType: String,
        scheduledDate: LocalDate?,
        scheduledTime: String? = "09:00",
        createdAt: LocalDateTime = LocalDateTime.of(2025, 1, 1, 8, 0)
    ): Task = Task(
        id = id,
        title = title,
        scheduledDate = scheduledDate,
        scheduledTime = scheduledTime,
        recurring = true,
        recurringType = recurringType,
        createdAt = createdAt
    )

    private fun init(dbFile: java.io.File) {
        assertIs<DbInitResult.Ready>(
            Database.init(
                dbFile = dbFile,
                policy = com.focusflow.data.InitPolicy(
                    busyTimeoutMs = 200,
                    maxAttempts = 1,
                    backoffMs = emptyList(),
                    totalDeadlineMs = 2_000
                ),
                allowRecovery = false
            )
        )
    }

    private fun userVersion(dbFile: java.io.File): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }
}
