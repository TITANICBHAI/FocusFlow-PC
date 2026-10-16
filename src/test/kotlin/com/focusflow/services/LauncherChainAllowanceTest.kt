package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class LauncherChainAllowanceTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        LauncherChainAllowance.resetForTest()
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        LauncherChainAllowance.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun settingDefaultsOffAndCannotBeEnabledWithoutGlobalPin() {
        val initial = LauncherChainAllowance.refresh()
        assertFalse(initial.enabled)
        assertFalse(initial.everEnabled)
        assertTrue(initial.readable)

        assertEquals(
            LauncherChainAllowance.EnableResult.PIN_NOT_CONFIGURED,
            LauncherChainAllowance.enable("not-a-pin")
        )
        assertFalse(LauncherChainAllowance.refresh().enabled)
    }

    @Test
    fun enablingAndEntryRequireAValidConfiguredGlobalPin() {
        GlobalPin.set("valid-global-pin")
        assertEquals(
            LauncherChainAllowance.EnableResult.INCORRECT_PIN,
            LauncherChainAllowance.enable("wrong-global-pin")
        )
        assertEquals(
            LauncherChainAllowance.EnableResult.ENABLED,
            LauncherChainAllowance.enable("valid-global-pin")
        )

        assertEquals(
            LauncherChainAllowance.EntryResult.INCORRECT_PIN,
            LauncherChainAllowance.verifyEntryPin("wrong-global-pin")
        )
        assertEquals(
            LauncherChainAllowance.EntryResult.PIN_ACCEPTED,
            LauncherChainAllowance.verifyEntryPin("valid-global-pin")
        )
        assertEquals("true", store.values["launcher_chain_allowance_enabled"])
        assertEquals("true", store.values["launcher_chain_allowance_ever_enabled"])
    }

    @Test
    fun freeDisablePreservesHistoricalStatusAndUsesStrictAllowList() {
        GlobalPin.set("valid-global-pin")
        assertEquals(
            LauncherChainAllowance.EnableResult.ENABLED,
            LauncherChainAllowance.enable("valid-global-pin")
        )

        assertTrue(LauncherChainAllowance.disable())
        val after = LauncherChainAllowance.refresh()
        assertFalse(after.enabled)
        assertTrue(after.everEnabled)
        assertTrue(after.readable)
        assertEquals(
            LauncherChainAllowance.EntryResult.STRICT_ALLOW_LIST,
            LauncherChainAllowance.verifyEntryPin("anything")
        )
    }

    @Test
    fun removingGlobalPinForcesAllowanceOffButKeepsLiveStatusVisible() {
        GlobalPin.set("valid-global-pin")
        assertEquals(
            LauncherChainAllowance.EnableResult.ENABLED,
            LauncherChainAllowance.enable("valid-global-pin")
        )

        assertEquals(
            PinVerificationResult.ACCEPTED,
            GlobalPin.clearResult("valid-global-pin")
        )
        val after = LauncherChainAllowance.refresh()
        assertFalse(after.enabled)
        assertTrue(after.everEnabled)
        assertEquals("false", store.values["launcher_chain_allowance_enabled"])
    }

    @Test
    fun unreadableStoreFailsClosedForEntryAndEnable() {
        GlobalPin.set("valid-global-pin")
        assertEquals(
            LauncherChainAllowance.EnableResult.ENABLED,
            LauncherChainAllowance.enable("valid-global-pin")
        )
        store.readable = false

        val state = LauncherChainAllowance.refresh()
        assertFalse(state.enabled)
        assertFalse(state.readable)
        assertTrue(state.everEnabled)
        assertEquals(
            LauncherChainAllowance.EntryResult.UNAVAILABLE,
            LauncherChainAllowance.verifyEntryPin("valid-global-pin")
        )
        assertEquals(
            LauncherChainAllowance.EnableResult.STORAGE_UNAVAILABLE,
            LauncherChainAllowance.enable("valid-global-pin")
        )
    }

    @Test
    fun failedDisableIsOffInMemoryAndReportsUnavailable() {
        GlobalPin.set("valid-global-pin")
        assertEquals(
            LauncherChainAllowance.EnableResult.ENABLED,
            LauncherChainAllowance.enable("valid-global-pin")
        )
        store.writable = false

        assertFalse(LauncherChainAllowance.disable())
        assertFalse(LauncherChainAllowance.isEnabled)
        assertFalse(LauncherChainAllowance.state.value.readable)
        assertTrue(LauncherChainAllowance.state.value.everEnabled)
    }

    private class MutableSettingsStore : SettingsStore {
        val values = mutableMapOf<String, String>()
        var readable = true
        var writable = true

        override fun readSetting(key: String): Database.SettingRead =
            if (!readable) {
                Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))
            } else {
                values[key]?.let(Database.SettingRead::Present) ?: Database.SettingRead.Absent
            }

        override fun writeSetting(key: String, value: String): Database.SettingWrite {
            if (!writable) return Database.SettingWrite.Failed(IllegalStateException("injected write failure"))
            values[key] = value
            return Database.SettingWrite.Ok
        }

        override fun writeSettings(pairs: Map<String, String>): Database.SettingWrite {
            if (!writable) return Database.SettingWrite.Failed(IllegalStateException("injected write failure"))
            values.putAll(pairs)
            return Database.SettingWrite.Ok
        }
    }
}
