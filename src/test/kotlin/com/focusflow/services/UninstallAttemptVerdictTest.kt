package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * The verdict is what the uninstall guard acts on. It must say "deny" for a
 * Standalone Block on its own (the reported bug: only Nuclear Mode had a
 * process-level uninstall guard) and must honour a wizard grant.
 */
class UninstallAttemptVerdictTest {
    private lateinit var store: StubStore
    private lateinit var dir: java.io.File

    @BeforeTest
    fun setUp() {
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        store = StubStore()
        Database.settingsStoreForTest = store
        UninstallProtectionService.isDirectInstallForTest = true
        dir = java.nio.file.Files.createTempDirectory("ff-verdict").toFile()
        UninstallAuthorizationHandoff.fileForTest = java.io.File(dir, "uninstall-authorization")
    }

    @AfterTest
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

    // ── Armed but not yet fighting, and Always-On without a PIN ──────────────

    private fun scheduleStandaloneBlockInDatabase(startsInMs: Long = 3_600_000L) {
        val now = System.currentTimeMillis()
        store.reads["standalone_block_start"] = Database.SettingRead.Present((now + startsInMs).toString())
        store.reads["standalone_block_until"] = Database.SettingRead.Present((now + startsInMs + 3_600_000L).toString())
        store.reads["standalone_block_processes"] = Database.SettingRead.Present("chrome.exe")
    }

    @Test
    fun aScheduledWindowThatHasNotStartedYetStillDeniesTheUninstall() {
        scheduleStandaloneBlockInDatabase()

        val verdict = UninstallProtectionService.uninstallAttemptVerdict()

        assertIs<UninstallProtectionService.UninstallVerdict.Deny>(verdict)
        val wait = verdict.requirements
            .filterIsInstance<UninstallProtectionService.Requirement.WaitForStandalone>().single()
        assertTrue(wait.startsInMs > 0L, "the message can say when it starts")
        assertTrue(wait.remainingMs > wait.startsInMs)
    }

    @Test
    fun aScheduledWindowDoesNotChangeTrayQuit() {
        scheduleStandaloneBlockInDatabase()
        UninstallProtectionService.messageDialogForTest = { _, _ -> }

        assertTrue(UninstallProtectionService.authorizeQuit())
    }

    @Test
    fun aWindowThatAlreadyEndedNoLongerDeniesAnything() {
        val now = System.currentTimeMillis()
        store.reads["standalone_block_start"] = Database.SettingRead.Present((now - 7_200_000L).toString())
        store.reads["standalone_block_until"] = Database.SettingRead.Present((now - 3_600_000L).toString())
        store.reads["standalone_block_processes"] = Database.SettingRead.Present("chrome.exe")

        assertEquals(
            UninstallProtectionService.UninstallVerdict.Allow,
            UninstallProtectionService.uninstallAttemptVerdict()
        )
    }

    @Test
    fun alwaysOnWithoutAGlobalPinDeniesTheUninstallButNotTrayQuit() {
        store.reads["always_on_enforcement"] = Database.SettingRead.Present("true")
        UninstallProtectionService.messageDialogForTest = { _, _ -> }

        val verdict = UninstallProtectionService.uninstallAttemptVerdict()

        assertIs<UninstallProtectionService.UninstallVerdict.Deny>(verdict)
        assertTrue(verdict.requirements.any { it is UninstallProtectionService.Requirement.Blocked })
        assertTrue(UninstallProtectionService.authorizeQuit(), "quit keeps its old behaviour")
    }

    @Test
    fun alwaysOnWithAGlobalPinAsksForThePinInsteadOfRefusing() {
        store.reads["always_on_enforcement"] = Database.SettingRead.Present("true")
        store.reads["global_pin_hash"] = Database.SettingRead.Present("configured-global-pin-hash")

        val verdict = UninstallProtectionService.uninstallAttemptVerdict()

        assertIs<UninstallProtectionService.UninstallVerdict.Deny>(verdict)
        assertTrue(verdict.requirements.any { it is UninstallProtectionService.Requirement.Pin })
        assertTrue(verdict.requirements.none { it is UninstallProtectionService.Requirement.Blocked })
    }

    @Test
    fun alwaysOnTurnedOffAllowsTheUninstall() {
        store.reads["always_on_enforcement"] = Database.SettingRead.Present("false")

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
