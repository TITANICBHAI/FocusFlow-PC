package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionPinInputTest {
    private val validHash = "b5ea5d8e9af9d2e998f065a5df094db7f0cdfc85b3f63a6326160629a5341a6d"

    @Test
    fun normalizeRemovesWhitespaceAndHyphens() {
        assertEquals("ABC123", SessionPinInput.normalize(" A\tB-\nC 1-2\r3 "))
    }

    @Test
    fun matchesCorrectPinWithOrWithoutGrouping() {
        assertTrue(SessionPinInput.matches("aB2cD3eF", validHash))
        assertTrue(SessionPinInput.matches("aB2c-D3eF", validHash))
        assertTrue(SessionPinInput.matches("aB2c D3eF", validHash))
    }

    @Test
    fun rejectsIncorrectOrBlankPin() {
        assertFalse(SessionPinInput.matches("aB2cD3eG", validHash))
        assertFalse(SessionPinInput.matches(" \t-\n ", validHash))
    }

    @Test
    fun missingOrBlankStoredHashFailsClosed() {
        assertFalse(SessionPinInput.matches("aB2cD3eF", null))
        assertFalse(SessionPinInput.matches("aB2cD3eF", ""))
        assertFalse(SessionPinInput.matches("aB2cD3eF", " \t"))
    }
}
