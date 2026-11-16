package com.focusflow.enforcement

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A child that stays alive until its standard input is closed, on any operating system. */
object BlockUntilStdinClosed {
    @JvmStatic
    fun main(args: Array<String>) {
        while (System.`in`.read() != -1) { /* wait */ }
    }
}

class OwnedProcessLauncherTest {
    private val ownPid = ProcessHandle.current().pid()
    private val javaBin = File(System.getProperty("java.home"), "bin/java").path

    private fun blockingChild() = ProcessBuilder(
        javaBin, "-cp", System.getProperty("java.class.path"), BlockUntilStdinClosed::class.java.name
    )

    @Test
    fun aStartedHelperIsOwnedWhileRunningAndReleasedWhenItEnds() {
        val registry = OwnedProcessRegistry(ownPid)
        val launcher = OwnedProcessLauncher(registry)

        val process = launcher.start(blockingChild(), expectedImage = javaBin)
        try {
            val start = process.toHandle().info().startInstant().map { it.toEpochMilli() }.orElse(null)
            assertNotNull(start, "the platform must report the start time for identity checks")
            assertTrue(registry.isOwned(process.pid(), start, ownPid, javaBin))
            // Same program, different process: never owned.
            assertFalse(registry.isOwned(process.pid() + 100_000L, start, ownPid, javaBin))
        } finally {
            process.outputStream.close()
            process.waitFor()
        }

        val deadline = System.currentTimeMillis() + 5_000L
        while (registry.ownedCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertEquals(0, registry.ownedCount(), "an ended helper must be released")
    }

    @Test
    fun aHelperThatCannotBeStartedLeavesNothingProtectedBehind() {
        val registry = OwnedProcessRegistry(ownPid)
        val launcher = OwnedProcessLauncher(registry)
        val missing = File(System.getProperty("java.io.tmpdir"), "focusflow-no-such-program-${System.nanoTime()}").path

        assertFailsWith<IOException> { launcher.start(ProcessBuilder(missing), expectedImage = missing) }

        assertFalse(registry.isOwned(4242L, System.currentTimeMillis() + 10L, ownPid, missing))
        assertEquals(0, registry.ownedCount())
    }
}
