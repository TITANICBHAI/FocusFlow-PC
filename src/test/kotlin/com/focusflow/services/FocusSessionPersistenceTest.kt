package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.models.FocusSession
import com.focusflow.testutil.TempHome
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FocusSessionPersistenceTest {

    @AfterEach
    fun restoreSessionTestHooks() {
        if (FocusSessionService.state.value.isActive) {
            FocusSessionService.end(completed = false)
        }
        FocusSessionService.sessionWriterForTest = { Database.insertSession(it) }
        FocusSessionService.sessionWriteFailureLoggerForTest = { operation, failure ->
            com.focusflow.enforcement.EnforcementLog.warn(
                "FocusSessionService",
                "Could not persist $operation focus session",
                failure
            )
        }
    }

    @Test
    @Order(1)
    fun endWaitsForStartWriteBeforeSavingFinalSession() = withDatabase {
        val startEntered = CountDownLatch(1)
        val releaseStart = CompletableDeferred<Unit>()
        val endEntered = CountDownLatch(1)
        val endFinished = CountDownLatch(1)
        FocusSessionService.sessionWriterForTest = { session ->
            if (session.endTime == null) {
                startEntered.countDown()
                releaseStart.await()
                Database.insertSession(session)
            } else {
                endEntered.countDown()
                Database.insertSession(session)
                endFinished.countDown()
            }
        }

        try {
            FocusSessionService.start("ordered-write", 25)
            assertTrue(startEntered.await(3, TimeUnit.SECONDS), "start write did not begin")

            FocusSessionService.end(completed = false)
            val endStartedBeforeStartFinished = endEntered.await(150, TimeUnit.MILLISECONDS)
            releaseStart.complete(Unit)

            assertTrue(endFinished.await(3, TimeUnit.SECONDS), "end write did not finish")
            assertFalse(endStartedBeforeStartFinished, "end write began before the start write completed")
            assertNotNull(
                Database.getRecentSessions(10).firstOrNull { it.taskName == "ordered-write" }?.endTime,
                "the final persisted row must retain the end time"
            )
        } finally {
            releaseStart.complete(Unit)
        }
    }

    @Test
    @Order(2)
    fun failedSessionWriteIsReported() = withDatabase {
        val startLogged = CountDownLatch(1)
        val endSaved = CountDownLatch(1)
        val loggedFailure = AtomicReference<Pair<String, Throwable>?>(null)
        val failure = IOException("injected session write failure")
        FocusSessionService.sessionWriterForTest = { session ->
            if (session.endTime == null) {
                throw failure
            } else {
                Database.insertSession(session)
                endSaved.countDown()
            }
        }
        FocusSessionService.sessionWriteFailureLoggerForTest = { operation, cause ->
            if (operation == "start") {
                loggedFailure.set(operation to cause)
                startLogged.countDown()
            }
        }

        try {
            FocusSessionService.start("failed-write", 25)

            assertTrue(startLogged.await(3, TimeUnit.SECONDS), "failed start write was not logged")
            assertEquals("start", loggedFailure.get()?.first)
            assertEquals(failure, loggedFailure.get()?.second)
            FocusSessionService.end(completed = false)
            assertTrue(endSaved.await(3, TimeUnit.SECONDS), "the session end row was not persisted")
        } finally {
            if (FocusSessionService.state.value.isActive) {
                FocusSessionService.end(completed = false)
            }
        }
    }

    @Test
    @Order(3)
    fun disposeAllowsPendingEndWriteToFinish() = withDatabase {
        val startEntered = CountDownLatch(1)
        FocusSessionService.sessionWriterForTest = { session ->
            if (session.endTime == null) {
                startEntered.countDown()
            }
            delay(250)
            Database.insertSession(session)
        }

        FocusSessionService.start("dispose-write", 25)
        assertTrue(startEntered.await(3, TimeUnit.SECONDS), "start write did not begin")
        FocusSessionService.end(completed = false)
        FocusSessionService.dispose()

        val stored = Database.getRecentSessions(10).firstOrNull { it.taskName == "dispose-write" }
        assertNotNull(stored, "pending session write was cancelled during dispose")
        assertNotNull(stored.endTime, "dispose returned before the end write completed")
    }

    private fun withDatabase(test: () -> Unit) {
        TempHome.withHome { home ->
            assertIs<DbInitResult.Ready>(
                Database.init(dbFile = home.resolve("focusflow.db").toFile(), allowRecovery = false)
            )
            test()
        }
    }
}
