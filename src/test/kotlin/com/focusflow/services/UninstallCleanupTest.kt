package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What happens to the scheduled tasks when the installer's check gives its answer. */
class UninstallCleanupTest {
    private fun withFakeRemoval(block: (calls: () -> Int) -> Unit) {
        var calls = 0
        UninstallCleanup.removeForTest = { calls++ }
        try {
            block { calls }
        } finally {
            UninstallCleanup.resetForTest()
        }
    }

    @Test
    fun anAllowedRemovalOfADirectInstallCleansUp() = withFakeRemoval { calls ->
        assertTrue(UninstallCleanup.runIfNeeded(UninstallCheck.EXIT_ALLOW, isDirectInstall = true))
        assertEquals(1, calls())
    }

    @Test
    fun aDeniedRemovalLeavesTheTasksAlone() = withFakeRemoval { calls ->
        assertFalse(UninstallCleanup.runIfNeeded(UninstallCheck.EXIT_DENY, isDirectInstall = true))
        assertEquals(0, calls())
    }

    @Test
    fun anyOtherOutcomeCleansUpBecauseTheInstallerLetsTheRemovalContinue() = withFakeRemoval { calls ->
        // main.wxs refuses only on exactly EXIT_DENY, so every other code means "go ahead".
        assertTrue(UninstallCleanup.runIfNeeded(1, isDirectInstall = true))
        assertEquals(1, calls())
    }

    @Test
    fun theStoreBuildIsLeftAlone() = withFakeRemoval { calls ->
        assertFalse(UninstallCleanup.runIfNeeded(UninstallCheck.EXIT_ALLOW, isDirectInstall = false))
        assertEquals(0, calls())
    }

    @Test
    fun aFailingCleanupNeverStopsTheUninstall() {
        UninstallCleanup.removeForTest = { error("Task Scheduler is not answering") }
        try {
            assertFalse(UninstallCleanup.runIfNeeded(UninstallCheck.EXIT_ALLOW, isDirectInstall = true))
        } finally {
            UninstallCleanup.resetForTest()
        }
    }
}
