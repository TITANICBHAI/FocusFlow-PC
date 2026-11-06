package com.focusflow.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The installer template (packaging/windows/main.wxs) and the app agree on a
 * private contract: which switch to run and which exit code means "do not remove".
 * Nothing in the build checks that, so these tests do, and fail loudly if one side
 * is edited without the other.
 */
class InstallerUninstallCheckWiringTest {
    private fun projectFile(path: String): File {
        val file = File(path)
        check(file.isFile) { "Expected $path relative to ${File(".").absoluteFile}" }
        return file
    }

    private val wxs: String
        get() = projectFile("packaging/windows/main.wxs").readText()

    @Test
    fun theInstallerRunsTheSwitchTheAppListensFor() {
        assertTrue(wxs.contains("--uninstall-check"), "main.wxs must run FocusFlow.exe --uninstall-check")
        assertTrue(UninstallCheckFlag.isRequested(arrayOf("--uninstall-check")))
    }

    @Test
    fun theInstallerRefusesOnExactlyTheExitCodeTheAppReturnsForDeny() {
        val deny = UninstallCheck.EXIT_DENY
        assertTrue(
            wxs.contains("if errorlevel $deny if not errorlevel ${deny + 1} exit /b 1"),
            "main.wxs must refuse only on exit code $deny (UninstallCheck.EXIT_DENY)"
        )
    }

    @Test
    fun everyOtherOutcomeLetsTheUninstallContinue() {
        // A missing exe, a JVM that will not start or a crash must not trap a broken install.
        assertTrue(wxs.contains("if exist \"[INSTALLDIR]FocusFlow.exe\""))
        assertTrue(wxs.contains("& exit /b 0"), "the wrapper must end in success when the app did not say deny")
    }

    @Test
    fun theCheckOnlyRunsForAnUninstallAndNotForAnUpgrade() {
        assertTrue(wxs.contains("REMOVE=\"ALL\" AND NOT UPGRADINGPRODUCTCODE"))
        assertTrue(wxs.contains("After=\"CostFinalize\""), "must run before InstallInitialize so a refusal changes nothing")
    }

    @Test
    fun theAppEntryPointEndsTheProcessWithTheCheckResult() {
        val main = File("src/main/kotlin/com/focusflow/Main.kt").readText()
        val flag = main.indexOf("UninstallCheckFlag.isRequested(args)")
        val wizard = main.indexOf("UninstallWizardFlag.isRequested(args)")
        assertTrue(flag in 0 until wizard, "the check must be handled before the wizard and the normal startup")
        assertTrue(main.contains("exitProcess(com.focusflow.services.UninstallCheck.run())"))
    }

    @Test
    fun theGradleTasksAndCiUseTheGuardedInstaller() {
        val gradle = projectFile("build.gradle.kts").readText()
        assertTrue(gradle.contains("packageGuarded\$taskSuffix"))
        assertTrue(gradle.contains("\"--resource-dir\", resourceDir.asFile.absolutePath"))
        val ci = projectFile(".github/workflows/build-windows.yml").readText()
        val guardedBuilds = Regex("gradle package(Exe|Msi) -Pfocusflow\\.guardedInstaller=true")
            .findAll(ci).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("Exe", "Msi"), guardedBuilds, "both installers must be built guarded")
        assertTrue(ci.contains("verify-installer-uninstall-check.ps1"))
    }
}
