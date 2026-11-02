package com.focusflow.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** Cheap structural guard: reads source files, does not run the UI or Windows. */
class SecurityWiringSourceTest {
    private fun source(relative: String): String =
        Files.readString(Path.of("src/main/kotlin/com/focusflow").resolve(relative))

    @Test
    fun emergencyBreakIsAskedForThePinBeforeItStarts() {
        val text = source("services/StartupBootstrap.kt")
        val pin = text.indexOf("EmergencyBreakPin.authorizeStart()")
        val toggle = text.indexOf("KillSwitchService.toggle()")
        assertTrue(pin in 0 until toggle, "the PIN check must come before the toggle")
    }

    @Test
    fun theStartupRestoresProtectionsBeforeAnythingReadsThem() {
        val text = source("services/StartupBootstrap.kt")
        val restore = text.indexOf("ProtectionSnapshotService.restoreIfNeeded()")
        val firstRead = text.indexOf("NuclearMode.loadFromDb()")
        assertTrue(restore in 0 until firstRead)
        assertTrue(text.contains("ProtectionSnapshotService.start()"))
    }

    @Test
    fun theUninstallWizardUsesTheSnapshotWhenTheDatabaseLostItsProtections() {
        val text = source("services/UninstallProtectionService.kt")
        assertTrue(text.contains("ProtectionSnapshotService.shouldUseSnapshotForWizard()"))
    }

    @Test
    fun pinCheckedTurnOffSitesGoThroughTheCoolOff() {
        val settings = source("ui/screens/SettingsScreen.kt")
        assertTrue(settings.contains("LooseningCoolOff.requestAlwaysOnOff()"))
        assertTrue(settings.contains("LooseningCoolOff.requestNuclearOff()"))
        assertTrue(source("ui/screens/NuclearModeScreen.kt").contains("LooseningCoolOff.requestNuclearOff()"))
    }

    @Test
    fun theCoolOffSwitchesAreOffByDefault() {
        // isEnabled() is true only for an explicit "true"; a missing setting means no cool-off.
        assertTrue(source("services/LooseningCoolOff.kt").contains("== \"true\" }.getOrDefault(false)"))
    }
}
