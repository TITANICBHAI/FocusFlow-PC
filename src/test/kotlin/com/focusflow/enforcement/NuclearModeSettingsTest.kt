package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class NuclearModeSettingsTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        NuclearMode.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun unreadableNuclearFlagIsNotTreatedAsDisabled() {
        store.read = Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))

        val read = Database.readSetting("nuclear_mode")
        assertEquals(NuclearMode.PersistedState.UNREADABLE, NuclearMode.persistedState(read))
    }

    @Test
    fun absentAndExplicitlyDisabledNuclearFlagsRemainDisabled() {
        store.read = Database.SettingRead.Absent
        assertEquals(
            NuclearMode.PersistedState.DISABLED,
            NuclearMode.persistedState(Database.readSetting("nuclear_mode"))
        )

        store.read = Database.SettingRead.Present("false")
        assertEquals(
            NuclearMode.PersistedState.DISABLED,
            NuclearMode.persistedState(Database.readSetting("nuclear_mode"))
        )
    }

    @Test
    fun malformedNuclearFlagIsNotTreatedAsDisabled() {
        store.read = Database.SettingRead.Present("unknown")

        assertEquals(
            NuclearMode.PersistedState.UNREADABLE,
            NuclearMode.persistedState(Database.readSetting("nuclear_mode"))
        )
    }

    private class MutableSettingsStore : SettingsStore {
        var read: Database.SettingRead = Database.SettingRead.Absent
        override fun readSetting(key: String) = read
        override fun writeSetting(key: String, value: String) = Database.SettingWrite.Ok
        override fun writeSettings(pairs: Map<String, String>) = Database.SettingWrite.Ok
    }
}
