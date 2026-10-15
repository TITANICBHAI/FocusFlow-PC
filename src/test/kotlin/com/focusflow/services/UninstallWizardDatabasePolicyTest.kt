package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.testutil.TempHome
import com.focusflow.enforcement.ProcessMonitor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UninstallWizardDatabasePolicyTest {
    private var previousAlwaysOnEnabled = false

    @BeforeTest
    fun setUp() {
        previousAlwaysOnEnabled = ProcessMonitor.alwaysOnEnabled
        Database.resetForTest()
        UninstallProtectionService.isDirectInstallForTest = true
    }

    @AfterTest
    fun tearDown() {
        Database.resetForTest()
        UninstallProtectionService.isDirectInstallForTest = null
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
}
