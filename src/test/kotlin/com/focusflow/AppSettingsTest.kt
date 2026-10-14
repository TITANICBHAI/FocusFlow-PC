package com.focusflow

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class AppSettingsTest {
    @BeforeEach
    fun setUp() {
        Database.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    @Test
    fun nextOpenCountDistinguishesAbsentUnreadableAndMalformedValues() {
        assertEquals(1, nextAppOpenCount(Database.SettingRead.Absent))
        assertEquals(8, nextAppOpenCount(Database.SettingRead.Present("7")))
        assertNull(nextAppOpenCount(Database.SettingRead.Present("not-a-count")))
        assertNull(
            nextAppOpenCount(
                Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))
            )
        )
    }

    @Test
    fun unavailableAppOpenCountIsNotTreatedAsAFirstLaunchDefault() {
        Database.resetForTest()
        Database.settingsStoreForTest = UnavailableStore()

        val read = Database.readSetting("app_open_count")
        assertNull(nextAppOpenCount(read))
    }

    private class UnavailableStore : SettingsStore {
        override fun readSetting(key: String) =
            Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))

        override fun writeSetting(key: String, value: String) =
            Database.SettingWrite.Failed(IllegalStateException("injected write failure"))

        override fun writeSettings(pairs: Map<String, String>) =
            Database.SettingWrite.Failed(IllegalStateException("injected write failure"))
    }
}
