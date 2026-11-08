package com.focusflow.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The quick session lengths on the Focus Launcher screen. */
class FocusLauncherDurationsTest {
    private val labels get() = FocusLauncherDurations.presets.map { it.first }

    @Test
    fun noLimitIsTheLastPresetSoItSitsRightBeforeCustom() {
        assertEquals("No limit", labels.last())
        assertNull(FocusLauncherDurations.presets.last().second)
    }

    @Test
    fun theShortChoicesComeFirstInAscendingOrder() {
        assertEquals(listOf("30 min", "1 hour", "2 hours", "4 hours", "No limit"), labels)
        val minutes = FocusLauncherDurations.presets.mapNotNull { it.second }
        assertEquals(minutes.sorted(), minutes)
    }

    @Test
    fun theScreenOpensOnThirtyMinutesNotAnOpenEndedSession() {
        val selected = FocusLauncherDurations.presets[FocusLauncherDurations.defaultIndex]
        assertEquals("30 min", selected.first)
        assertEquals(30, selected.second)
    }

    @Test
    fun exactlyOnePresetHasNoTimeLimit() {
        assertEquals(1, FocusLauncherDurations.presets.count { it.second == null })
    }
}
