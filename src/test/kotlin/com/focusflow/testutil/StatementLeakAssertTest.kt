package com.focusflow.testutil

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import java.nio.file.Path
import java.sql.Statement
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteConnection

class StatementLeakAssertTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun detectsUnclosedStatementsAndAcceptsClosedStatementCycles() {
        TempHome.withHome {
            val dbFile = tempDir.resolve("statement-leak-assert.db").toFile()
            assertIs<DbInitResult.Ready>(Database.init(dbFile = dbFile, allowRecovery = false))

            val connection = currentConnection()
            StatementLeakAssert.assertStable(repetitions = 10) {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { resultSet ->
                        assertTrue(resultSet.next())
                    }
                }
            }

            var leaked: Statement? = null
            try {
                assertFailsWith<AssertionError> {
                    StatementLeakAssert.assertStable(repetitions = 1) {
                        leaked = connection.createStatement()
                        leaked?.execute("SELECT 1")
                    }
                }
            } finally {
                leaked?.close()
            }

            StatementLeakAssert.assertStable(repetitions = 1) { }
        }
    }

    private fun currentConnection(): SQLiteConnection {
        val connectionField = Database::class.java.getDeclaredField("conn")
        check(connectionField.trySetAccessible())
        return connectionField.get(null) as? SQLiteConnection
            ?: error("Database connection was not initialized")
    }
}
