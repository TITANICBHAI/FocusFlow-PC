package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

class UninstallProtectionSettingsTest {
    private lateinit var store: MutableSettingsStore

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        store = MutableSettingsStore()
        Database.settingsStoreForTest = store
    }

    @AfterEach
    fun tearDown() {
        Database.resetForTest()
    }

    @Test
    fun unreadableProtectionSettingsCreateABlockingRequirement() {
        store.unreadableKeys += "always_on_enforcement"

        val requirements = UninstallProtectionService.activeRequirements(
            UninstallProtectionService.Action.QUIT
        )

        assertTrue(requirements.any {
            it is UninstallProtectionService.Requirement.Blocked &&
                it.feature == "Always-On enforcement"
        })
    }

    @Test
    fun absentProtectionSettingsDoNotCreateBlockingRequirements() {
        val requirements = UninstallProtectionService.activeRequirements(
            UninstallProtectionService.Action.QUIT
        )

        assertFalse(requirements.any { it is UninstallProtectionService.Requirement.Blocked })
    }

    @Test
    fun malformedEnforcementFlagsAndStandaloneExpiryBlockQuit() {
        store.reads["always_on_enforcement"] = Database.SettingRead.Present("unknown")
        store.reads["standalone_block_until"] = Database.SettingRead.Present("not-a-timestamp")
        store.reads["nuclear_mode"] = Database.SettingRead.Present("unknown")

        val requirements = UninstallProtectionService.activeRequirements(
            UninstallProtectionService.Action.QUIT
        )

        val blockedFeatures = requirements
            .filterIsInstance<UninstallProtectionService.Requirement.Blocked>()
            .map { it.feature }
        assertTrue("Always-On enforcement" in blockedFeatures)
        assertTrue("Standalone Block" in blockedFeatures)
        assertTrue("Nuclear Mode" in blockedFeatures)
    }

    private class MutableSettingsStore : SettingsStore {
        val unreadableKeys = mutableSetOf<String>()
        val reads = mutableMapOf<String, Database.SettingRead>()
        override fun readSetting(key: String): Database.SettingRead =
            if (key in unreadableKeys) {
                Database.SettingRead.Unreadable(IllegalStateException("injected read failure"))
            } else {
                reads[key] ?: Database.SettingRead.Absent
            }

        override fun writeSetting(key: String, value: String) = Database.SettingWrite.Ok
        override fun writeSettings(pairs: Map<String, String>) = Database.SettingWrite.Ok
    }
}
