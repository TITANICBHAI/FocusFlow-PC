package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.testutil.TempHome
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The installer-side question "may this removal proceed?". Only an explicit
 * deny may stop an uninstall; everything else must let it continue.
 */
class UninstallCheckTest {
    private var previousAlwaysOnEnabled = false
    private var notices = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        previousAlwaysOnEnabled = ProcessMonitor.alwaysOnEnabled
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        UninstallProtectionService.isDirectInstallForTest = true
        notices = mutableListOf()
        UninstallProtectionService.messageDialogForTest = { title, _ -> notices += title }
        UninstallProtectionService.pinPromptForTest = { null }
    }

    @AfterTest
    fun tearDown() {
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        ProcessMonitor.alwaysOnEnabled = previousAlwaysOnEnabled
    }

    private fun withSettings(settings: Map<String, String>, block: () -> Unit) =
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow/focusflow.db").toFile()
            assertIs<DbInitResult.Ready>(Database.init(dbFile = dbFile, allowRecovery = false))
            settings.forEach { (key, value) ->
                assertEquals(Database.SettingWrite.Ok, Database.writeSetting(key, value), key)
            }
            Database.close()
            UninstallAuthorizationHandoff.fileForTest = home.resolve("handoff").toFile()
            try {
                block()
            } finally {
                UninstallAuthorizationHandoff.resetForTest()
            }
        }

    @Test
    fun exitCodesAreTheContractWithTheInstaller() {
        assertEquals(0, UninstallCheck.EXIT_ALLOW)
        assertEquals(42, UninstallCheck.EXIT_DENY)
    }

    @Test
    fun nothingProtectedLetsTheRemovalContinue() = withSettings(
        mapOf("always_on_enforcement" to "false")
    ) {
        assertEquals(UninstallCheck.EXIT_ALLOW, UninstallCheck.decide())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun aStandaloneBlockDeniesTheRemovalEvenWhenFocusFlowIsNotRunning() = withSettings(
        mapOf(
            "always_on_enforcement" to "false",
            "standalone_block_until" to (System.currentTimeMillis() + 60_000L).toString(),
            "standalone_block_start" to (System.currentTimeMillis() - 1_000L).toString(),
            "standalone_block_processes" to "focusflow-test.exe"
        )
    ) {
        assertEquals(UninstallCheck.EXIT_DENY, UninstallCheck.decide())
        assertTrue(notices.any { it.contains("Uninstall protection") }, "the user is told why: $notices")
    }

    @Test
    fun aScheduledWindowDeniesTheRemovalBeforeItStarts() = withSettings(
        mapOf(
            "always_on_enforcement" to "false",
            "standalone_block_start" to (System.currentTimeMillis() + 3_600_000L).toString(),
            "standalone_block_until" to (System.currentTimeMillis() + 7_200_000L).toString(),
            "standalone_block_processes" to "focusflow-test.exe"
        )
    ) {
        assertEquals(UninstallCheck.EXIT_DENY, UninstallCheck.decide())
        assertTrue(notices.any { it.contains("Uninstall protection") }, "the user is told why: $notices")
    }

    @Test
    fun alwaysOnWithoutAGlobalPinDeniesTheRemoval() = withSettings(
        mapOf("always_on_enforcement" to "true")
    ) {
        assertEquals(UninstallCheck.EXIT_DENY, UninstallCheck.decide())
        assertTrue(notices.any { it.contains("Action blocked") }, "the user is told why: $notices")
    }

    @Test
    fun aCancelledPinDeniesTheRemoval() = withSettings(
        mapOf(
            "always_on_enforcement" to "true",
            "global_pin_hash" to "configured-global-pin-hash"
        )
    ) {
        assertEquals(UninstallCheck.EXIT_DENY, UninstallCheck.decide())
    }

    @Test
    fun aWizardOrPinGrantLetsTheRemovalThroughWithoutAskingAgain() = withSettings(
        mapOf(
            "always_on_enforcement" to "false",
            "standalone_block_until" to (System.currentTimeMillis() + 60_000L).toString(),
            "standalone_block_start" to (System.currentTimeMillis() - 1_000L).toString(),
            "standalone_block_processes" to "focusflow-test.exe"
        )
    ) {
        UninstallAuthorizationHandoff.grant()

        assertEquals(UninstallCheck.EXIT_ALLOW, UninstallCheck.decide())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun storeBuildsAreNeverStopped() = withSettings(
        mapOf(
            "standalone_block_until" to (System.currentTimeMillis() + 60_000L).toString(),
            "standalone_block_processes" to "focusflow-test.exe"
        )
    ) {
        UninstallProtectionService.isDirectInstallForTest = false

        assertEquals(UninstallCheck.EXIT_ALLOW, UninstallCheck.decide())
    }

    @Test
    fun theCheckSwitchIsNotTheWizardSwitch() {
        assertTrue(UninstallCheckFlag.isRequested(arrayOf("--uninstall-check")))
        assertTrue(!UninstallWizardFlag.isRequested(arrayOf("--uninstall-check")))
        assertTrue(UninstallWizardFlag.isRequested(arrayOf("--uninstall")))
        assertTrue(!UninstallCheckFlag.isRequested(arrayOf("--uninstall")))
    }
}
