package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DatabaseUnavailableException
import com.focusflow.data.SettingWriteException
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class PinSettingsUnavailableTest {
    private lateinit var unavailableStore: SettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        unavailableStore = UnavailableSettingsStore()
        Database.settingsStoreForTest = unavailableStore
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    @Test
    fun f8eGlobalPinExposesUnknownAndDeniesVerificationAndClear() {
        assertEquals(PinState.UNKNOWN, GlobalPin.state())
        assertTrue(GlobalPin.isSet())
        assertFalse(GlobalPin.verify("any-attempt"))
        assertEquals(PinVerificationResult.UNAVAILABLE, GlobalPin.verifyResult("any-attempt"))
        assertEquals(PinVerificationResult.UNAVAILABLE, GlobalPin.clearResult("any-attempt"))
        assertEquals(PinPolicy.SETTINGS_READ_ERROR, PinPolicy.errorMessage(
            GlobalPin.verifyResult("any-attempt"),
            "Incorrect PIN."
        ))
        assertFalse(GlobalPin.clear("any-attempt"))
        assertFailsWith<SettingWriteException> { GlobalPin.set("valid-pin-123") }
    }

    @Test
    fun f8eSessionPinExposesUnknownAndDeniesVerificationAndClear() {
        assertEquals(PinState.UNKNOWN, SessionPin.state())
        assertTrue(SessionPin.isSet())
        assertFalse(SessionPin.verify("any-attempt"))
        assertEquals(PinVerificationResult.UNAVAILABLE, SessionPin.verifyResult("any-attempt"))
        assertEquals(PinVerificationResult.UNAVAILABLE, SessionPin.clearResult("any-attempt"))
        assertFalse(SessionPin.clear("any-attempt"))
        assertFailsWith<SettingWriteException> { SessionPin.set("valid-pin-123") }
        assertFailsWith<SettingWriteException> { SessionPin.autoGenerate() }
    }

    @Test
    fun f8eNuclearPinExposesUnknownAndDeniesVerificationAndClear() {
        assertEquals(PinState.UNKNOWN, NuclearPin.state())
        assertTrue(NuclearPin.isSet())
        assertFalse(NuclearPin.verify("any-attempt"))
        assertEquals(PinVerificationResult.UNAVAILABLE, NuclearPin.verifyResult("any-attempt"))
        assertEquals(PinVerificationResult.UNAVAILABLE, NuclearPin.clearResult("any-attempt"))
        assertFalse(NuclearPin.clear("any-attempt"))
        assertFailsWith<SettingWriteException> { NuclearPin.set("1234") }
    }

    private class UnavailableSettingsStore : SettingsStore {
        private val failure = DatabaseUnavailableException(null)

        override fun readSetting(key: String) =
            Database.SettingRead.Unreadable(failure)

        override fun writeSetting(key: String, value: String) =
            Database.SettingWrite.Failed(failure)

        override fun writeSettings(pairs: Map<String, String>) =
            Database.SettingWrite.Failed(failure)
    }
}
