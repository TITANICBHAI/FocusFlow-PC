package com.focusflow.ui.launcher

import com.focusflow.services.LaunchFailureCode
import com.focusflow.services.LaunchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherContentTest {
    @Test
    fun elapsedProgressAppearsAtTenSecondsAndShowsTheCurrentDuration() {
        assertEquals("Opening…", launchProgressLabel("Design app", 9_999L))
        assertEquals("Still opening Design app… 10 s", launchProgressLabel("Design app", 10_000L))
        assertEquals("Still opening Design app… 12 s", launchProgressLabel("Design app", 12_999L))
        assertEquals(false, launchCancelVisible(9_999L))
        assertEquals(true, launchCancelVisible(10_000L))
    }

    @Test
    fun failedTileRemainsClickableDuringTheFailureDisplay() {
        val nonRetryable = LaunchState.Failed(
            code = LaunchFailureCode.NotFound,
            reason = "The app executable could not be found.",
            canRetry = false
        )

        assertTrue(launchTileTapEnabled(nonRetryable))
        assertFalse(launchTileTapEnabled(LaunchState.Launching(0L)))
    }

    @Test
    fun typedFailureLabelsStaySpecificAndRetryableFailuresExplainTheAction() {
        val missing = LaunchState.Failed(
            code = LaunchFailureCode.NotFound,
            reason = "The app executable could not be found.",
            canRetry = false
        )
        val blocked = LaunchState.Failed(
            code = LaunchFailureCode.BlockedByRule,
            reason = "Blocked by an always-on rule.",
            canRetry = false
        )
        val retryable = LaunchState.Failed(
            code = LaunchFailureCode.SpawnFailed,
            reason = "Couldn't start this app.",
            canRetry = true
        )

        assertEquals("App not found", launchFailureLabel(missing))
        assertEquals("Blocked by FocusFlow", launchFailureLabel(blocked))
        assertEquals("Couldn't start", launchFailureLabel(retryable))
        assertNull(launchFailureRetryHint(missing))
        assertEquals("Tap to retry", launchFailureRetryHint(retryable))
    }
}
