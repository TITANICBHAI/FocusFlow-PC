package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.NativeSqliteUnavailableException
import com.focusflow.testutil.TempHome
import com.focusflow.enforcement.ProcessMonitor
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UninstallWizardDatabasePolicyTest {
    private var previousAlwaysOnEnabled = false

    @BeforeTest
    fun setUp() {
        previousAlwaysOnEnabled = ProcessMonitor.alwaysOnEnabled
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        UninstallProtectionService.isDirectInstallForTest = true
        UninstallProtectionService.messageDialogForTest = { _, _ -> }
        UninstallProtectionService.pinPromptForTest = { null }
    }

    @AfterTest
    fun tearDown() {
        Database.resetForTest()
        UninstallProtectionService.resetForTest()
        ProcessMonitor.alwaysOnEnabled = previousAlwaysOnEnabled
    }

    @Test
    fun missingDatabaseAllowsWizardWithoutCreatingProfileState() = TempHome.withHome { home ->
        val dbFile = home.resolve(".focusflow/focusflow.db").toFile()

        assertTrue(UninstallProtectionService.prepareForUninstallWizard())
        assertFalse(Database.isReady)
        assertTrue(UninstallProtectionService.authorizeUninstallWizard())

        assertFalse(dbFile.exists())
        assertFalse(dbFile.parentFile.exists())
    }

    @Test
    fun nonBusyNativeFailureRequiresTheTypedConfirmationPhrase() = TempHome.withHome { home ->
        val failedOpen = DbInitResult.Failed(
            NativeSqliteUnavailableException(
                message = "SQLite could not be loaded",
                attempts = emptyList(),
                requiresRestart = true,
                diagnostics = "test diagnostic"
            ),
            home.resolve(".focusflow/focusflow.db").toFile()
        )
        assertEquals(
            UninstallProtectionService.WizardDatabaseDecision.REQUIRE_CONFIRMATION,
            UninstallProtectionService.decideWizardDatabaseOutcome(failedOpen, databaseReady = false)
        )
        UninstallProtectionService.wizardDatabaseOpenForTest = { failedOpen }
        var promptCount = 0
        UninstallProtectionService.unreadableDbPhrasePromptForTest = {
            promptCount++
            "not the confirmation phrase"
        }

        assertTrue(UninstallProtectionService.prepareForUninstallWizard())
        assertFalse(UninstallProtectionService.authorizeUninstallWizard())
        assertEquals(1, promptCount)

        assertTrue(UninstallProtectionService.prepareForUninstallWizard())
        UninstallProtectionService.unreadableDbPhrasePromptForTest = {
            promptCount++
            UninstallProtectionService.UNREADABLE_DB_CONFIRMATION_PHRASE
        }
        assertTrue(UninstallProtectionService.authorizeUninstallWizard())
        assertEquals(2, promptCount)
    }

    @Test
    fun busyAndLockedFailuresBlockWithoutOfferingThePhraseOverride() = TempHome.withHome { home ->
        val dbFile = home.resolve(".focusflow/focusflow.db").toFile()
        val busy = SQLException("database is busy", "SQLITE_BUSY", 5)
        val locked = SQLException("database is locked", "SQLITE_LOCKED", 6)
        val busyResult = DbInitResult.Busy(busy, attempts = 1, waitedMs = 1_000)

        assertEquals(
            UninstallProtectionService.WizardDatabaseDecision.BLOCK,
            UninstallProtectionService.decideWizardDatabaseOutcome(busyResult, databaseReady = false)
        )
        assertEquals(
            UninstallProtectionService.WizardDatabaseDecision.BLOCK,
            UninstallProtectionService.decideWizardDatabaseOutcome(
                DbInitResult.Failed(locked, dbFile),
                databaseReady = false
            )
        )

        UninstallProtectionService.wizardBusyRetryDelaysForTest = listOf(0L, 0L)
        var openAttempts = 0
        UninstallProtectionService.wizardDatabaseOpenForTest = {
            openAttempts++
            busyResult
        }
        var promptCount = 0
        UninstallProtectionService.unreadableDbPhrasePromptForTest = {
            promptCount++
            UninstallProtectionService.UNREADABLE_DB_CONFIRMATION_PHRASE
        }

        assertFalse(UninstallProtectionService.prepareForUninstallWizard())
        assertFalse(UninstallProtectionService.authorizeUninstallWizard())
        assertEquals(0, promptCount)
        assertEquals(3, openAttempts)
    }

    @Test
    fun readyDatabaseStillBlocksAnActiveStandaloneRequirement() {
        assertReadyProtectionStillBlocks(
            mapOf(
                "always_on_enforcement" to "false",
                "standalone_block_until" to (System.currentTimeMillis() + 60_000L).toString(),
                "standalone_block_start" to (System.currentTimeMillis() - 1_000L).toString(),
                "standalone_block_processes" to "focusflow-test.exe"
            )
        )
    }

    @Test
    fun readyDatabaseStillRequiresTheGlobalPinForAlwaysOn() {
        assertReadyProtectionStillBlocks(
            mapOf(
                "always_on_enforcement" to "true",
                "global_pin_hash" to "configured-global-pin-hash"
            )
        )
    }

    @Test
    fun readyDatabaseStillRequiresTheNuclearPin() {
        assertReadyProtectionStillBlocks(
            mapOf(
                "always_on_enforcement" to "false",
                "nuclear_mode" to "true",
                "nuclear_pin_hash" to "configured-nuclear-pin-hash"
            )
        )
    }

    private fun assertReadyProtectionStillBlocks(settings: Map<String, String>) =
        TempHome.withHome { home ->
            val dbFile = home.resolve(".focusflow/focusflow.db").toFile()
            assertIs<DbInitResult.Ready>(
                Database.init(dbFile = dbFile, allowRecovery = false)
            )
            settings.forEach { (key, value) ->
                assertEquals(Database.SettingWrite.Ok, Database.writeSetting(key, value), key)
            }
            Database.close()

            var phrasePromptCount = 0
            UninstallProtectionService.unreadableDbPhrasePromptForTest = {
                phrasePromptCount++
                UninstallProtectionService.UNREADABLE_DB_CONFIRMATION_PHRASE
            }
            assertTrue(UninstallProtectionService.prepareForUninstallWizard())
            assertTrue(Database.isReady)
            assertFalse(UninstallProtectionService.authorizeUninstallWizard())
            assertEquals(0, phrasePromptCount)
        }
}
