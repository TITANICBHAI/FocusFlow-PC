package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartOnceTest {
    @Test
    fun onlyTheFirstStartupCallRunsSideEffects() {
        val once = StartOnce()
        var count = 0

        assertEquals(StartOnceResult.Started, once.run { count++ })
        assertEquals(StartOnceResult.AlreadyStarted, once.run { count++ })
        assertEquals(StartOnceResult.AlreadyStarted, once.run { count++ })
        assertEquals(1, count)
    }

    @Test
    fun startupFailureIsRememberedAndSideEffectsAreNotRepeated() {
        val once = StartOnce()
        var count = 0
        val failed = once.run {
            count++
            error("bootstrap failed")
        }

        assertTrue(failed is StartOnceResult.Failed)
        assertEquals(failed, once.run { count++ })
        assertEquals(1, count)
    }
}
