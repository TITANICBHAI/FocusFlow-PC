package com.focusflow.data

import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class DatabaseSchemaGuardTest {
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

    @Test
    fun f14aVersionNineDatabaseMissingRequiredColumnFailsWithTypedMismatch() {
        val dbFile = tempDir.resolve("missing-required-column-v9.db").toFile()
        assertIs<DbInitResult.Ready>(init(dbFile))
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE tasks RENAME COLUMN skipped TO skipped_legacy")
                statement.execute("PRAGMA user_version=9")
            }
        }
        assertEquals(9, userVersion(dbFile))

        val failed = assertIs<DbInitResult.Failed>(init(dbFile))
        val mismatch = assertIs<SchemaMismatchException>(failed.cause)
        assertEquals("tasks.skipped", mismatch.missingColumn)
    }

    @Test
    fun f14bHealthyVersionNineDatabaseUpgradesAndPassesSchemaGuard() {
        val dbFile = tempDir.resolve("healthy-v9.db").toFile()
        assertIs<DbInitResult.Ready>(init(dbFile))
        Database.close()

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE tasks DROP COLUMN recurring_parent_id")
                statement.execute("PRAGMA user_version=9")
            }
        }
        assertEquals(9, userVersion(dbFile))
        assertIs<DbInitResult.Ready>(init(dbFile))
        assertEquals(10, userVersion(dbFile))
    }

    @Test
    fun f14bSchemaGuardCoversEveryLegacySwallowedAddColumnTarget() {
        val legacyTargets = listOf(
            "tasks" to "skipped",
            "tasks" to "focus_mode",
            "tasks" to "focus_intensity",
            "daily_completions" to "total_count",
            "daily_completions" to "focus_minutes",
            "block_rules" to "source",
            "tasks" to "focus_blocked_apps",
            "tasks" to "focus_require_pin"
        )

        legacyTargets.forEachIndexed { index, (table, column) ->
            Database.resetForTest()
            val dbFile = tempDir.resolve("missing-legacy-column-$index.db").toFile()
            try {
                assertIs<DbInitResult.Ready>(init(dbFile))
                Database.close()

                DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "ALTER TABLE \"$table\" RENAME COLUMN \"$column\" TO \"${column}_legacy\""
                        )
                        statement.execute("PRAGMA user_version=9")
                    }
                }

                val failed = assertIs<DbInitResult.Failed>(init(dbFile))
                val mismatch = assertIs<SchemaMismatchException>(failed.cause)
                assertEquals("$table.$column", mismatch.missingColumn)
            } finally {
                Database.resetForTest()
            }
        }
    }

    @Test
    fun f14cNewMigrationColumnHelperIsIdempotent() {
        val dbFile = tempDir.resolve("guarded-column.db").toFile()
        assertIs<DbInitResult.Ready>(init(dbFile))

        Database.inTransaction {
            Database.addColumnIfMissing("tasks", "f14_guard_test", "TEXT")
            Database.addColumnIfMissing("tasks", "f14_guard_test", "TEXT")
        }

        val matchingColumns = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(\"tasks\")").use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            if (resultSet.getString("name") == "f14_guard_test") add(resultSet.getString("name"))
                        }
                    }
                }
            }
        }
        assertEquals(listOf("f14_guard_test"), matchingColumns)
    }

    private fun init(dbFile: java.io.File): DbInitResult =
        Database.init(
            dbFile = dbFile,
            policy = InitPolicy(
                busyTimeoutMs = 200,
                maxAttempts = 1,
                backoffMs = emptyList(),
                totalDeadlineMs = 2_000
            ),
            allowRecovery = false
        )

    private fun userVersion(dbFile: java.io.File): Int =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    assertEquals(true, resultSet.next())
                    resultSet.getInt(1)
                }
            }
        }
}
