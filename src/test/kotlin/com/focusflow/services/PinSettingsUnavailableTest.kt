package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DatabaseUnavailableException
import com.focusflow.data.SettingWriteException
import com.focusflow.data.SettingsStore
import kotlin.test.Test
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
    fun globalPinTreatsUnreadableHashAsSetAndDeniesVerification() {
        assertTrue(GlobalPin.isSet())
        assertFalse(GlobalPin.verify("any-attempt"))
        assertFalse(GlobalPin.clear("any-attempt"))
        assertFailsWith<SettingWriteException> { GlobalPin.set("valid-pin-123") }
    }

    @Test
    fun sessionPinTreatsUnreadableHashAsSetAndDeniesVerification() {
        assertTrue(SessionPin.isSet())
        assertFalse(SessionPin.verify("any-attempt"))
        assertFalse(SessionPin.clear("any-attempt"))
        assertFailsWith<SettingWriteException> { SessionPin.set("valid-pin-123") }
        assertFailsWith<SettingWriteException> { SessionPin.autoGenerate() }
    }

    @Test
    fun nuclearPinTreatsUnreadableHashAsSetAndDeniesVerification() {
        assertTrue(NuclearPin.isSet())
        assertFalse(NuclearPin.verify("any-attempt"))
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
