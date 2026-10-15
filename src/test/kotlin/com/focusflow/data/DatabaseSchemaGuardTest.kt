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
            }
        }
        assertEquals(9, userVersion(dbFile))

        val failed = assertIs<DbInitResult.Failed>(init(dbFile))
        val mismatch = assertIs<SchemaMismatchException>(failed.cause)
        assertEquals("tasks.skipped", mismatch.missingColumn)
    }

    @Test
    fun f14bHealthyVersionNineDatabasePassesSchemaGuard() {
        val dbFile = tempDir.resolve("healthy-v9.db").toFile()
        assertIs<DbInitResult.Ready>(init(dbFile))
        Database.close()
        assertEquals(9, userVersion(dbFile))

        assertIs<DbInitResult.Ready>(init(dbFile))
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
