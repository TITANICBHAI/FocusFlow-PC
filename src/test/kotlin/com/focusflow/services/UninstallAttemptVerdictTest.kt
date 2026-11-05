package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The verdict is what the uninstall guard acts on. It must say "deny" for a
 * Standalone Block on its own (the reported bug: only Nuclear Mode had a
 * process-level uninstall guard) and must honour a wizard grant.
 */
class UninstallAttemptVerdictTest {
    private lateinit var store: StubStore
    private lateinit var dir: java.io.File

    @BeforeEach
    fun setUp() {
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        store = StubStore()
        Database.settingsStoreForTest = store
        UninstallProtectionService.isDirectInstallForTest = true
        dir = java.nio.file.Files.createTempDirectory("ff-verdict").toFile()
        UninstallAuthorizationHandoff.fileForTest = java.io.File(dir, "uninstall-authorization")
    }

    @AfterEach
    fun tearDown() {
        UninstallProtectionService.resetForTest()
        Database.resetForTest()
        dir.deleteRecursively()
    }

    private fun startStandaloneBlockInDatabase() {
        store.reads["standalone_block_until"] =
            Database.SettingRead.Present((System.currentTimeMillis() + 60_000L).toString())
        store.reads["standalone_block_processes"] = Database.SettingRead.Present("chrome.exe")
    }

    @Test
    fun nothingProtectedAllowsTheUninstall() {
        assertEquals(
            UninstallProtectionService.UninstallVerdict.Allow,
            UninstallProtectionService.uninstallAttemptVerdict()
        )
    }

    @Test
    fun aStandaloneBlockAloneDeniesTheUninstall() {
        startStandaloneBlockInDatabase()

        val verdict = UninstallProtectionService.uninstallAttemptVerdict()

        assertIs<UninstallProtectionService.UninstallVerdict.Deny>(verdict)
        assertTrue(verdict.requirements.any {
            it is UninstallProtectionService.Requirement.WaitForStandalone
        })
    }

    @Test
    fun aWizardGrantLetsTheAuthorizedUninstallThrough() {
        startStandaloneBlockInDatabase()
        UninstallAuthorizationHandoff.grant()

        assertEquals(
            UninstallProtectionService.UninstallVerdict.Allow,
            UninstallProtectionService.uninstallAttemptVerdict()
        )
    }

    @Test
    fun nuclearModeWithoutAPinDeniesTheUninstall() {
        store.reads["nuclear_mode"] = Database.SettingRead.Present("true")

        assertIs<UninstallProtectionService.UninstallVerdict.Deny>(
            UninstallProtectionService.uninstallAttemptVerdict()
        )
    }

    @Test
    fun storeBuildsAreNeverIntercepted() {
        UninstallProtectionService.isDirectInstallForTest = false
        startStandaloneBlockInDatabase()

        assertEquals(
            UninstallProtectionService.UninstallVerdict.Allow,
            UninstallProtectionService.uninstallAttemptVerdict()
        )
    }

    private class StubStore : SettingsStore {
        val reads = mutableMapOf<String, Database.SettingRead>()
        override fun readSetting(key: String): Database.SettingRead =
            reads[key] ?: Database.SettingRead.Absent

        override fun writeSetting(key: String, value: String) = Database.SettingWrite.Ok
        override fun writeSettings(pairs: Map<String, String>) = Database.SettingWrite.Ok
    }
}
