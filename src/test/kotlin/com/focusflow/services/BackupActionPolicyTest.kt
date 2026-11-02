package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupActionPolicyTest {
    @Test
    fun noPinSetAllowsTheActionWithoutAPrompt() {
        assertFalse(BackupActionPolicy.requiresPin(PinState.NOT_SET))
    }

    @Test
    fun aSetPinIsRequired() {
        assertTrue(BackupActionPolicy.requiresPin(PinState.SET))
    }

    @Test
    fun anUnreadablePinStateIsTreatedAsSetSoTheActionFailsClosed() {
        assertTrue(BackupActionPolicy.requiresPin(PinState.UNKNOWN))
    }
}
