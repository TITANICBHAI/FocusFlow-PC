package com.focusflow.data

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class DatabaseTransactionTest {
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
    fun stackOverflowRollsBackFirstWriteAndConnectionRemainsAutocommit() {
        val dbFile = tempDir.resolve("throwable-rollback.db").toFile()
        init(dbFile)
        val failure = StackOverflowError("injected F10-a failure")

        val thrown = assertFailsWith<StackOverflowError> {
            Database.inTransaction {
                Database.setSetting("f10-a-partial", "must-rollback")
                throw failure
            }
        }

        assertSame(failure, thrown)
        assertNull(readSetting(dbFile, "f10-a-partial"))

        Database.setSetting("f10-a-after", "autocommitted")
        assertEquals("autocommitted", readSetting(dbFile, "f10-a-after"))
    }

    @Test
    fun sqlExceptionRollsBackWrites() {
        val dbFile = tempDir.resolve("sql-rollback.db").toFile()
        init(dbFile)

        assertFailsWith<SQLException> {
            Database.inTransaction {
                Database.setSetting("f10-b-partial", "must-rollback")
                throw SQLException("injected F10-b failure")
            }
        }

        assertNull(readSetting(dbFile, "f10-b-partial"))
    }

    @Test
    fun immediateTransactionFailsBusyWithoutPartialWrite() {
        val dbFile = tempDir.resolve("busy-transaction.db").toFile()
        init(dbFile, busyTimeoutMs = 50)

        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { lockConnection ->
            lockConnection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val failure = assertFailsWith<SQLException> {
                    Database.inTransaction {
                        Database.setSetting("f10-c-partial", "must-not-run")
                    }
                }

                assertEquals(SqliteFailureKind.BUSY, classifySqliteFailure(failure))
                assertNull(readSetting(dbFile, "f10-c-partial"))
            } finally {
                lockConnection.createStatement().use { it.execute("ROLLBACK") }
            }
        }
    }

    @Test
    fun openingVersionTenFixtureAndEmptyDatabaseReachSameSchema() {
        val generatedV10 = tempDir.resolve("generated-v10.db").toFile()
        init(generatedV10)
        Database.close()
        assertEquals(10, userVersion(generatedV10))

        val versionTenFixture = tempDir.resolve("fixture-v10.db").toFile()
        Files.copy(generatedV10.toPath(), versionTenFixture.toPath())

        Database.resetForTest()
        init(versionTenFixture)
        val fixtureSchema = schemaSnapshot(versionTenFixture)

        Database.resetForTest()
        val emptyDatabase = tempDir.resolve("empty.db").toFile()
        init(emptyDatabase)
        val emptySchema = schemaSnapshot(emptyDatabase)

        assertEquals(10, fixtureSchema.userVersion)
        assertEquals(10, emptySchema.userVersion)
        assertEquals(emptySchema.tables, fixtureSchema.tables)
    }

    private fun init(dbFile: java.io.File, busyTimeoutMs: Int = 200) {
        assertIs<DbInitResult.Ready>(
            Database.init(
                dbFile = dbFile,
                policy = InitPolicy(
                    busyTimeoutMs = busyTimeoutMs,
                    maxAttempts = 1,
                    backoffMs = emptyList(),
                    totalDeadlineMs = 2_000
                ),
                allowRecovery = false
            )
        )
    }

    private fun readSetting(dbFile: java.io.File, key: String): String? =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            connection.prepareStatement("SELECT value FROM settings WHERE key = ?").use { statement ->
                statement.setString(1, key)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getString(1) else null
                }
            }
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

    private fun schemaSnapshot(dbFile: java.io.File): SchemaSnapshot =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { connection ->
            val tableNames = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
                ).use { resultSet ->
                    buildList {
                        while (resultSet.next()) add(resultSet.getString(1))
                    }
                }
            }
            val tables = tableNames.associateWith { tableName ->
                val quotedName = tableName.replace("\"", "\"\"")
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA table_info(\"$quotedName\")").use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    SchemaColumn(
                                        cid = resultSet.getInt("cid"),
                                        name = resultSet.getString("name"),
                                        type = resultSet.getString("type"),
                                        notNull = resultSet.getInt("notnull"),
                                        defaultValue = resultSet.getString("dflt_value"),
                                        primaryKey = resultSet.getInt("pk")
                                    )
                                )
                            }
                        }
                    }
                }
            }
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getInt(1)
                }
            }
            SchemaSnapshot(version, tables)
        }

    private data class SchemaSnapshot(
        val userVersion: Int,
        val tables: Map<String, List<SchemaColumn>>,
    )

    private data class SchemaColumn(
        val cid: Int,
        val name: String,
        val type: String,
        val notNull: Int,
        val defaultValue: String?,
        val primaryKey: Int,
    )
}
