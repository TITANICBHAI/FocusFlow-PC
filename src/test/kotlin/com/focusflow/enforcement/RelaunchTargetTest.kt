package com.focusflow.enforcement

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RelaunchTargetTest {

    @Test
    fun nonWindowsNeverAttemptsElevation() {
        assertEquals(
            RelaunchTarget.NotSupported,
            chooseRelaunchTarget(
                isWindows = false,
                isMsix = false,
                aumid = null,
                exePath = "/opt/focusflow/FocusFlow.exe",
                aliasPath = null
            )
        )
    }

    @Test
    fun directInstallUsesExecutableAndItsDirectory() {
        val path = "/opt/focusflow/FocusFlow.exe"
        val target = assertIs<RelaunchTarget.DirectExe>(
            chooseRelaunchTarget(true, false, null, path, null)
        )

        assertEquals(path, target.path)
        assertEquals(File(path).absoluteFile.parentFile.absolutePath, target.workingDirectory)
    }

    @Test
    fun msixUsesPackageAumidBeforeExecutionAlias() {
        assertEquals(
            RelaunchTarget.AppsFolder("FocusFlow_abcd!App"),
            chooseRelaunchTarget(
                isWindows = true,
                isMsix = true,
                aumid = "FocusFlow_abcd!App",
                exePath = null,
                aliasPath = "C:\\Users\\User\\AppData\\Local\\Microsoft\\WindowsApps\\focusflow.exe"
            )
        )
    }

    @Test
    fun msixFallsBackToAvailableExecutionAlias() {
        val alias = "C:\\Users\\User\\AppData\\Local\\Microsoft\\WindowsApps\\focusflow.exe"
        assertEquals(
            RelaunchTarget.ExecutionAlias(alias),
            chooseRelaunchTarget(true, true, null, null, alias)
        )
    }

    @Test
    fun msixWithoutIdentityOrAliasShowsManualInstructionsPath() {
        assertEquals(
            RelaunchTarget.Manual,
            chooseRelaunchTarget(true, true, null, null, null)
        )
    }

    @Test
    fun directInstallWithoutExecutableUsesManualInstructionsPath() {
        assertEquals(
            RelaunchTarget.Manual,
            chooseRelaunchTarget(true, false, null, null, null)
        )
    }
}
