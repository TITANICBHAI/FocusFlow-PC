package com.focusflow.enforcement

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskkillTest {
    private val ownPid = 100L
    private val exe = "C:\\Windows\\System32\\taskkill.exe"

    private fun run(
        pids: List<Long>,
        results: (List<String>) -> Boolean = { true }
    ): Pair<Boolean, List<List<String>>> {
        val commands = mutableListOf<List<String>>()
        val ok = Taskkill.killPids(
            pids = pids,
            ownPid = ownPid,
            windows = true,
            taskkillExe = exe,
            runner = { command -> commands += command; results(command) }
        )
        return ok to commands
    }

    @Test
    fun severalProcessesAreEndedWithOneTaskkillCommandByProcessId() {
        val (ok, commands) = run(listOf(5L, 6L, 7L))

        assertTrue(ok)
        assertEquals(listOf(listOf(exe, "/F", "/PID", "5", "/PID", "6", "/PID", "7")), commands)
    }

    @Test
    fun duplicatesInvalidIdsAndTheEnforcingProcessItselfAreDropped() {
        val (_, commands) = run(listOf(5L, 5L, 0L, -3L, ownPid, 6L))

        assertEquals(listOf(listOf(exe, "/F", "/PID", "5", "/PID", "6")), commands)
    }

    @Test
    fun nothingToKillRunsNothing() {
        val (ok, commands) = run(listOf(ownPid, 0L))

        assertFalse(ok)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aLargeBatchIsSplitSoNoCommandLineGrowsWithoutLimit() {
        val (_, commands) = run((1L..45L).toList().map { it + 1_000L })

        assertEquals(listOf(20, 20, 5), commands.map { (it.size - 2) / 2 })
    }

    @Test
    fun aFailedChunkIsReportedButTheRemainingChunksStillRun() {
        var calls = 0
        val (ok, commands) = run((1L..45L).toList().map { it + 1_000L }) { calls++ != 0 }

        assertFalse(ok)
        assertEquals(3, commands.size)
    }

    /** A child that stays alive until its standard input is closed, on any operating system. */
    private fun blockingChild() = ProcessBuilder(
        File(System.getProperty("java.home"), "bin/java").path,
        "-cp", System.getProperty("java.class.path"), BlockUntilStdinClosed::class.java.name
    ).start()

    @Test
    fun onOtherPlatformsTheProcessesAreEndedDirectly() {
        if (System.getProperty("os.name").lowercase().contains("windows")) return
        val child = blockingChild()
        try {
            assertTrue(Taskkill.killPids(listOf(child.pid()), windows = false))
            assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "the child must have been ended")
        } finally {
            child.destroyForcibly()
        }
    }
}
