package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedProcessRegistryTest {
    private val ownPid = 100L
    private val powerShell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
    private var now = 1_000_000L

    private fun registry(maxAgeMs: Long = 600_000L) =
        OwnedProcessRegistry(ownPid = ownPid, nowMs = { now }, maxAgeMs = maxAgeMs)

    @Test
    fun aRegisteredHelperIsOwnedWhenPidStartTimeAndImageAllMatch() {
        val registry = registry()
        val token = registry.beginSpawn(powerShell)
        registry.completeSpawn(token, pid = 4242L, startMs = 5_000L)

        assertTrue(registry.isOwned(4242L, 5_000L, ownPid, powerShell))
    }

    @Test
    fun anotherInstanceOfTheSameExecutableIsNotOwned() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)

        // A user-launched PowerShell: same image, different PID.
        assertFalse(registry.isOwned(4243L, 5_100L, 999L, powerShell))
    }

    @Test
    fun aReusedPidWithADifferentStartTimeIsNotOwned() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)

        assertFalse(registry.isOwned(4242L, 9_000L, 999L, powerShell))
    }

    @Test
    fun aRegisteredPidRunningADifferentImageIsNotOwned() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)

        assertFalse(registry.isOwned(4242L, 5_000L, ownPid, "C:\\Users\\me\\Downloads\\powershell.exe"))
    }

    @Test
    fun anUnknownImagePathFailsClosed() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)

        assertFalse(registry.isOwned(4242L, 5_000L, ownPid, null))
    }

    @Test
    fun imageComparisonIgnoresCaseAndSlashDirection() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)

        assertTrue(
            registry.isOwned(4242L, 5_000L, ownPid, "c:/windows/system32/windowspowershell/v1.0/POWERSHELL.EXE")
        )
    }

    @Test
    fun anUnreadableStartTimeStillRequiresThePidAndImageToMatch() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = null)

        assertTrue(registry.isOwned(4242L, null, ownPid, powerShell))
        assertFalse(registry.isOwned(4243L, null, ownPid, powerShell))
    }

    @Test
    fun aReleasedHelperIsNoLongerOwned() {
        val registry = registry()
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)
        registry.release(4242L)

        assertFalse(registry.isOwned(4242L, 5_000L, ownPid, powerShell))
        assertEquals(0, registry.ownedCount())
    }

    @Test
    fun anEntryThatWasNeverReleasedExpires() {
        val registry = registry(maxAgeMs = 60_000L)
        registry.completeSpawn(registry.beginSpawn(powerShell), pid = 4242L, startMs = 5_000L)

        now += 60_001L

        assertFalse(registry.isOwned(4242L, 5_000L, ownPid, powerShell))
    }

    @Test
    fun aChildOfThisProcessIsProtectedWhileItsSpawnIsStillBeingRegistered() {
        val registry = registry()
        registry.beginSpawn(powerShell)

        // The OS has created the child but completeSpawn() has not run yet.
        assertTrue(registry.isOwned(7777L, now + 5L, ownPid, powerShell))
    }

    @Test
    fun theInFlightWindowDoesNotProtectAProcessWithAnotherParent() {
        val registry = registry()
        registry.beginSpawn(powerShell)

        assertFalse(registry.isOwned(7777L, now + 5L, 999L, powerShell))
    }

    @Test
    fun theInFlightWindowDoesNotProtectAnotherImage() {
        val registry = registry()
        registry.beginSpawn(powerShell)

        assertFalse(registry.isOwned(7777L, now + 5L, ownPid, "C:\\Windows\\System32\\cmd.exe"))
    }

    @Test
    fun theInFlightWindowDoesNotProtectAProcessThatWasAlreadyRunningBeforeTheSpawn() {
        val registry = registry()
        registry.beginSpawn(powerShell)

        // Child of this process, same image, but started long before this spawn began.
        assertFalse(registry.isOwned(7777L, now - 60_000L, ownPid, powerShell))
    }

    @Test
    fun theInFlightWindowClosesWhenTheSpawnCompletesOrFails() {
        val registry = registry()
        val completed = registry.beginSpawn(powerShell)
        registry.completeSpawn(completed, pid = 4242L, startMs = now + 1L)
        val aborted = registry.beginSpawn(powerShell)
        registry.abortSpawn(aborted)

        assertFalse(registry.isOwned(7777L, now + 5L, ownPid, powerShell))
        assertTrue(registry.isOwned(4242L, now + 1L, ownPid, powerShell))
    }

    @Test
    fun anAbandonedInFlightSpawnExpires() {
        val registry = registry(maxAgeMs = 60_000L)
        registry.beginSpawn(powerShell)

        now += 60_001L

        assertFalse(registry.isOwned(7777L, now, ownPid, powerShell))
    }
}
