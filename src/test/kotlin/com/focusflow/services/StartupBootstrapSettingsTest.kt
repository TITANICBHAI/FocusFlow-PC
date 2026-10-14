package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class StartupBootstrapSettingsTest {
    @BeforeEach
    fun setUp() {
        Database.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    @Test
    fun unreadableAlwaysOnValueEnablesEnforcementUntilConfirmed() {
        Database.settingsStoreForTest = UnavailableSettingsStore()

        val restored = alwaysOnEnabledFromSetting(Database.readSetting("always_on_enforcement"))

        assertTrue(restored)
    }

    @Test
    fun absentAlwaysOnValueRemainsDisabled() {
        Database.settingsStoreForTest = UnavailableSettingsStore(
            Database.SettingRead.Absent
        )

        val restored = alwaysOnEnabledFromSetting(Database.readSetting("always_on_enforcement"))

        assertFalse(restored)
    }

    private class UnavailableSettingsStore(
        private val read: Database.SettingRead =
            Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))
    ) : SettingsStore {
        override fun readSetting(key: String) = read
        override fun writeSetting(key: String, value: String) = Database.SettingWrite.Ok
        override fun writeSettings(pairs: Map<String, String>) = Database.SettingWrite.Ok
    }
}
