package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the watchdog is told to stand down: only for a Quit the user chose and was allowed to make. */
class WatchdogPauseTest {
    private fun withFakePause(block: (calls: () -> Int) -> Unit) {
        var calls = 0
        WatchdogPause.pauseForTest = { calls++ }
        try {
            block { calls }
        } finally {
            WatchdogPause.resetForTest()
        }
    }

    @Test
    fun aQuitThatPassedTheQuitProtectionsPausesTheWatchdog() = withFakePause { calls ->
        assertTrue(WatchdogPause.pauseIfDeliberateQuit(skipQuitAuthorization = false, preserveNuclearMode = false))
        assertEquals(1, calls())
    }

    @Test
    fun theRestartAsAdministratorHandOffKeepsTheWatchdogOn() = withFakePause { calls ->
        // A new FocusFlow is taking over; if it fails to start, the watchdog is the safety net.
        assertFalse(WatchdogPause.pauseIfDeliberateQuit(skipQuitAuthorization = true, preserveNuclearMode = true))
        assertEquals(0, calls())
    }

    @Test
    fun anyShutdownThatSkipsTheQuitProtectionsKeepsTheWatchdogOn() = withFakePause { calls ->
        assertFalse(WatchdogPause.pauseIfDeliberateQuit(skipQuitAuthorization = true, preserveNuclearMode = false))
        assertEquals(0, calls())
    }

    @Test
    fun aFailingPauseNeverBlocksTheQuit() {
        WatchdogPause.pauseForTest = { error("Task Scheduler is not answering") }
        try {
            assertFalse(WatchdogPause.pauseIfDeliberateQuit(skipQuitAuthorization = false, preserveNuclearMode = false))
        } finally {
            WatchdogPause.resetForTest()
        }
    }
}
