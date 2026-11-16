package com.focusflow.enforcement

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemCommandTest {
    private fun javaCommand(main: Class<*>) = listOf(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("java.class.path"), main.name
    )

    private val started = mutableListOf<Process>()
    private val start: (ProcessBuilder) -> Process = { it.start().also { p -> started += p } }

    @Test
    fun aCommandThatFinishesReportsItsExitCodeAndOutput() {
        val result = SystemCommand.run(javaCommand(PrintHello::class.java), timeoutSec = 30, start = start)

        assertTrue(result.ok)
        assertEquals(0, result.exitCode)
        assertTrue("hello from child" in result.output)
    }

    @Test
    fun aCommandThatOutlastsItsTimeoutIsKilledAndReported() {
        val result = SystemCommand.run(javaCommand(SleepForAMinute::class.java), timeoutSec = 1, start = start)

        assertTrue(result.timedOut)
        assertFalse(result.ok)
        assertNull(result.exitCode)
        assertTrue(started.single().waitFor(5, TimeUnit.SECONDS), "the process must be dead after the timeout")
    }

    @Test
    fun aCommandThatCannotBeStartedIsAFailureNotACrash() {
        val result = SystemCommand.run(listOf("nope"), timeoutSec = 1, start = { error("no such file") })

        assertFalse(result.ok)
        assertFalse(result.timedOut)
        assertNull(result.exitCode)
    }
}
