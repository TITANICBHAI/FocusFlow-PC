package com.focusflow.testutil

import com.focusflow.data.Database
import kotlin.test.assertEquals
import org.sqlite.SQLiteConnection
import org.sqlite.core.DB

object StatementLeakAssert {
    fun assertStable(repetitions: Int = 100, block: () -> Unit) {
        require(repetitions > 0) { "repetitions must be positive" }

        val before = openStatementCount()
        repeat(repetitions) { block() }
        val after = openStatementCount()

        assertEquals(
            before,
            after,
            "SQLite statement registry changed: before=$before after=$after"
        )
    }

    private fun openStatementCount(): Int {
        val connectionField = Database::class.java.getDeclaredField("conn")
        check(connectionField.trySetAccessible()) { "Cannot access Database.conn for leak assertion" }
        val connection = connectionField.get(null) as? SQLiteConnection
            ?: error("Database must be initialized before checking statement leaks")

        val statementsField = DB::class.java.getDeclaredField("stmts")
        check(statementsField.trySetAccessible()) { "Cannot access SQLite DB.stmts for leak assertion" }
        val statements = statementsField.get(connection.database) as? Set<*>
            ?: error("SQLite DB.stmts is not a statement set")
        return statements.size
    }
}
