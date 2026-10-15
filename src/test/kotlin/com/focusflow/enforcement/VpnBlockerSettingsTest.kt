package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.data.SettingReadException
import com.focusflow.data.SettingWriteException
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class VpnBlockerSettingsTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        VpnBlocker.resetForTest()
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        VpnBlocker.resetForTest()
        Database.resetForTest()
    }

    @Test
    fun unavailableStoreDoesNotCacheDefaultsOrOverwriteRestoredState() {
        assertTrue(VpnBlocker.isEnabled, "unknown VPN state must fail closed")
        assertEquals(emptyList(), VpnBlocker.getCustomProcesses())
        assertFailsWith<SettingReadException> {
            VpnBlocker.addCustomProcess("new-vpn")
        }
        assertEquals(0, store.writeCalls)

        store.reads["vpn_block_enabled"] = Database.SettingRead.Absent
        store.reads["vpn_custom_processes"] = Database.SettingRead.Present("saved-vpn.exe")
        assertFalse(VpnBlocker.isEnabled, "unreadable enable fallback must not be cached")
        assertEquals(listOf("saved-vpn.exe"), VpnBlocker.getCustomProcesses())

        store.reads.clear()
        assertFalse(VpnBlocker.isEnabled, "a prior readable setting is retained")
        assertEquals(listOf("saved-vpn.exe"), VpnBlocker.getCustomProcesses())

        assertFailsWith<SettingWriteException> {
            VpnBlocker.addCustomProcess("another-vpn")
        }
        assertEquals(listOf("saved-vpn.exe"), VpnBlocker.getCustomProcesses())
    }

    @Test
    fun backgroundRefreshRecoversAfterUnreadableReadsAndKeepsLastKnownGoodValues() {
        VpnBlocker.refreshSettingsFromDatabase()
        assertTrue(VpnBlocker.isEnabledForEnforcement)
        assertFalse(VpnBlocker.isVpnProcess("saved-vpn.exe"))

        store.reads["vpn_block_enabled"] = Database.SettingRead.Present("true")
        store.reads["vpn_custom_processes"] =
            Database.SettingRead.Present("saved-vpn.exe")
        VpnBlocker.refreshSettingsFromDatabase()
        assertTrue(VpnBlocker.isVpnProcess("saved-vpn.exe"))

        store.reads.clear()
        VpnBlocker.refreshSettingsFromDatabase()
        assertTrue(VpnBlocker.isVpnProcess("saved-vpn.exe"))
    }

    @Test
    fun failedEnableWriteIsReportedWithoutReplacingTheCachedValue() {
        store.reads["vpn_block_enabled"] = Database.SettingRead.Present("true")
        VpnBlocker.refreshSettingsFromDatabase()
        assertTrue(VpnBlocker.isEnabled)

        assertFailsWith<SettingWriteException> {
            VpnBlocker.isEnabled = false
        }

        assertTrue(VpnBlocker.isEnabled)
    }

    @Test
    fun enforcementHotPathDoesNotReadDatabaseWhenCacheIsUninitialized() {
        assertTrue(VpnBlocker.isVpnProcess("openvpn.exe"))
        assertFalse(VpnBlocker.isVpnProcess("custom-vpn.exe"))

        assertEquals(0, store.readCalls)
    }

    private class MutableSettingsStore : SettingsStore {
        private val failure = IllegalStateException("injected settings store unavailable")
        val reads = mutableMapOf<String, Database.SettingRead>()
        var writeCalls = 0
        var readCalls = 0

        override fun readSetting(key: String): Database.SettingRead {
            readCalls++
            return reads[key] ?: Database.SettingRead.Unreadable(failure)
        }

        override fun writeSetting(key: String, value: String): Database.SettingWrite {
            writeCalls++
            return Database.SettingWrite.Failed(failure)
        }

        override fun writeSettings(pairs: Map<String, String>): Database.SettingWrite {
            writeCalls++
            return Database.SettingWrite.Failed(failure)
        }
    }
}
