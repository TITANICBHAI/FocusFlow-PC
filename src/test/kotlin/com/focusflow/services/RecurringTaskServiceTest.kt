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

        assertEquals(10, userVersion(dbFile))
        val migrated = Database.getTasks().associateBy { it.id }
        assertEquals(existingTasks.map { it.id }.toSet(), migrated.keys)
        assertNull(migrated.getValue("legacy-template").recurringParentId)
        assertNull(migrated.getValue("ordinary-task").recurringParentId)
        assertTrue(Database.getRecurringTemplates().single().id == "legacy-template")
    }

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
