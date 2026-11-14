package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NuclearHoldsTest {
    @Test
    fun enforcementStartsWithTheFirstHoldAndStopsWithTheLast() {
        val holds = NuclearHolds()

        val sessionOn = holds.setSession(true)
        assertTrue(sessionOn.started)
        assertFalse(sessionOn.stopped)

        val manualOn = holds.setManual(true)
        assertFalse(manualOn.started)
        assertTrue(manualOn.manualChanged)

        val sessionOff = holds.setSession(false)
        assertFalse(sessionOff.stopped, "a manual hold keeps enforcement running")
        assertTrue(holds.snapshot().active)

        val manualOff = holds.setManual(false)
        assertTrue(manualOff.stopped)
        assertFalse(holds.snapshot().active)
    }

    @Test
    fun endingASessionNeverClearsTheManualHold() {
        val holds = NuclearHolds()
        holds.setManual(true)
        holds.setSession(true)

        holds.setSession(false)

        assertEquals(NuclearHolds.Snapshot(manual = true, session = false), holds.snapshot())
    }

    @Test
    fun releasingAnUnheldSessionIsHarmless() {
        val holds = NuclearHolds()
        val release = holds.setSession(false)

        assertFalse(release.started)
        assertFalse(release.stopped)
        assertFalse(release.sessionChanged)
    }

    @Test
    fun repeatingTheSameChangeReportsNoChange() {
        val holds = NuclearHolds()
        holds.setManual(true)

        val again = holds.setManual(true)

        assertFalse(again.manualChanged)
        assertFalse(again.started)
    }

    @Test
    fun clearAllDropsBothHolds() {
        val holds = NuclearHolds()
        holds.setManual(true)
        holds.setSession(true)

        val cleared = holds.clearAll()

        assertTrue(cleared.stopped)
        assertEquals(NuclearHolds.Snapshot(manual = false, session = false), holds.snapshot())
    }
}
