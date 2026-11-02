package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmergencyBreakPolicyTest {
    @Test
    fun aSetPinIsRequired() = assertTrue(EmergencyBreakPolicy.requiresPin(PinState.SET))

    @Test
    fun anUnreadablePinStateCountsAsSet() = assertTrue(EmergencyBreakPolicy.requiresPin(PinState.UNKNOWN))

    @Test
    fun noPinMeansNoPrompt() = assertFalse(EmergencyBreakPolicy.requiresPin(PinState.NOT_SET))
}
