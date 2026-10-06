package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionActivityGateTest {
    @Test
    fun resumingWhileStillLockedDoesNotReactivateTracking() {
        val gate = SessionActivityGate()

        assertEquals(SessionActivityChange(true), gate.setLocked(true))
        assertNull(gate.setSuspended(true))
        assertNull(gate.setSuspended(false))
        assertEquals(true, gate.isInactive())
        assertEquals(SessionActivityChange(false), gate.setLocked(false))
    }
}
