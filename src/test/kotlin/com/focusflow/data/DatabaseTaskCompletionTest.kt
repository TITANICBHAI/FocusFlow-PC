package com.focusflow.data

import com.focusflow.testutil.TempHome
import java.nio.file.Path
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTaskCompletionTest {

    @Test
    fun f21aCompletingTheSameTaskTwiceOnlyCountsTheFirstCompletion() = withDatabase { dbFile ->
        insertIncompleteTask(dbFile, "repeat-task")

        assertTrue(Database.completeTask("repeat-task"))
        val firstState = taskCompletion(dbFile, "repeat-task")
        assertNotNull(firstState)
        assertTrue(firstState.completed)
        assertNotNull(firstState.completedAt)
        assertEquals(1, totalCompletedCount(dbFile))

        assertFalse(Database.completeTask("repeat-task"))

        assertEquals(firstState, taskCompletion(dbFile, "repeat-task"))
        assertEquals(1, totalCompletedCount(dbFile))
    }

    @Test
    fun f21bCompletingAnUnknownTaskDoesNotCreateADailyCompletion() = withDatabase { dbFile ->
        assertFalse(Database.completeTask("missing-task"))

        assertNull(taskCompletion(dbFile, "missing-task"))
        assertEquals(0, totalCompletedCount(dbFile))
    }

    @Test
    fun f21cFirstCompletionMarksAnExistingTaskAndIncrementsTheCountOnce() = withDatabase { dbFile ->
        insertIncompleteTask(dbFile, "first-completion")
        assertEquals(0, totalCompletedCount(dbFile))

        assertTrue(Database.completeTask("first-completion"))

        val completed = taskCompletion(dbFile, "first-completion")
        assertNotNull(completed)
        assertTrue(completed.completed)
        assertNotNull(completed.completedAt)
        assertEquals(1, totalCompletedCount(dbFile))
    }

    private fun withDatabase(block: (Path) -> Unit) {
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow").resolve("focusflow.db")
            assertIs<DbInitResult.Ready>(Database.init(dbFile.toFile(), allowRecovery = false))
            block(dbFile)
        }
    }

    private fun insertIncompleteTask(dbFile: Path, id: String) {
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tasks (id, title, created_at, completed, completed_at)
                VALUES (?, ?, ?, 0, NULL)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, "Completion test")
                statement.setString(3, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                statement.executeUpdate()
            }
        }
    }

    private fun taskCompletion(dbFile: Path, id: String): TaskCompletion? =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                "SELECT completed, completed_at FROM tasks WHERE id = ?"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        TaskCompletion(
                            completed = resultSet.getInt("completed") == 1,
                            completedAt = resultSet.getString("completed_at")
                        )
                    } else {
                        null
                    }
                }
            }
        }

    private fun totalCompletedCount(dbFile: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COALESCE(SUM(completed_count), 0) FROM daily_completions"
                ).use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else 0
                }
            }
        }

    private data class TaskCompletion(
        val completed: Boolean,
        val completedAt: String?
    )
}
