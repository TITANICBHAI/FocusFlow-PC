package com.focusflow.enforcement

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundEventHubTest {
    @Test
    fun broadcastsUnknownForegroundAndStopsAfterListenerRemoval() {
        val hub = ForegroundEventHub()
        val seen = mutableListOf<ForegroundEvent>()
        val id = hub.addListener(seen::add)

        val unknown = ForegroundEvent(exe = null, pid = 0L, monoNs = 42L)
        hub.publish(unknown)
        hub.removeListener(id)
        hub.publish(ForegroundEvent(exe = "sample.exe", pid = 9L, monoNs = 50L))

        assertEquals(listOf(unknown), seen)
    }
}
