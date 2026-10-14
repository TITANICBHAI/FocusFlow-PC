package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElevationRelaunchServiceTest {

    @Test
    fun nuclearModeAloneDoesNotBlockAdministratorRelaunch() {
        assertFalse(
            hasElevationBlockingSession(
                focusSessionActive = false,
                focusLauncherActive = false,
                nuclearModeActive = true
            )
        )
    }

    @Test
    fun focusAndLauncherSessionsStillBlockAdministratorRelaunch() {
        assertTrue(
            hasElevationBlockingSession(
                focusSessionActive = true,
                focusLauncherActive = false,
                nuclearModeActive = false
            )
        )
        assertTrue(
            hasElevationBlockingSession(
                focusSessionActive = true,
                focusLauncherActive = false,
                nuclearModeActive = true
            )
        )
        assertTrue(
            hasElevationBlockingSession(
                focusSessionActive = false,
                focusLauncherActive = true,
                nuclearModeActive = false
            )
        )
    }
}
