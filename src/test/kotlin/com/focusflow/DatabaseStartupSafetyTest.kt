package com.focusflow

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.DbStartupState
import com.focusflow.data.InitPolicy
import com.focusflow.testutil.TempHome
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.CancellationException
import javax.sql.DataSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DatabaseStartupSafetyTest {

    @Test
    fun nonFatalLinkageErrorBecomesFailedStateAndRepeatedInitRemainsFailed() {
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow/focusflow.db").toFile()
            val linkageFailure = UnsatisfiedLinkError("injected SQLite loader failure")
            Database.dataSourceFactoryForTest = { throwingDataSource(linkageFailure) }

            val first = Database.init(dbFile, oneAttempt)
            assertSame(linkageFailure, assertIs<DbInitResult.Failed>(first).cause)
            assertIs<DbStartupState.Failed>(Database.startupState.value)

            val repeated = Database.init(dbFile, oneAttempt)
            assertSame(linkageFailure, assertIs<DbInitResult.Failed>(repeated).cause)
            assertIs<DbStartupState.Failed>(Database.startupState.value)
        }
    }

    @Test
    fun virtualMachineErrorIsRethrownAfterFailedStateIsPublished() {
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow/focusflow.db").toFile()
            val fatal = OutOfMemoryError("injected fatal allocation failure")
            Database.dataSourceFactoryForTest = { throwingDataSource(fatal) }

            assertSame(fatal, assertFailsWith<OutOfMemoryError> {
                Database.init(dbFile, oneAttempt)
            })
            val state = assertIs<DbStartupState.Failed>(Database.startupState.value)
            assertSame(fatal, state.cause)
        }
    }

    @Test
    fun startupCallerMapsUnexpectedThrowableButRethrowsCancellationAndFatalErrors() = runTest {
        val dbFile = File("unused-test-database.db")
        val unexpected = AssertionError("injected nonfatal initializer failure")
        val (result, threw) = initDatabaseSafely(dbFile) { throw unexpected }

        assertTrue(threw)
        assertSame(unexpected, assertIs<DbInitResult.Failed>(result).cause)

        val cancelled = CancellationException("test cancellation")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            initDatabaseSafely(dbFile) { throw cancelled }
        })

        val fatal = OutOfMemoryError("test fatal error")
        assertSame(fatal, assertFailsWith<OutOfMemoryError> {
            initDatabaseSafely(dbFile) { throw fatal }
        })
    }

    private fun throwingDataSource(failure: Throwable): DataSource {
        val handler = InvocationHandler { _, method, _ ->
            if (method.name == "getConnection") throw failure
            throw UnsupportedOperationException("Unexpected DataSource call: ${method.name}")
        }
        return Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
            handler
        ) as DataSource
    }

    private companion object {
        val oneAttempt = InitPolicy(maxAttempts = 1, backoffMs = emptyList(), totalDeadlineMs = 1_000)
    }
}
