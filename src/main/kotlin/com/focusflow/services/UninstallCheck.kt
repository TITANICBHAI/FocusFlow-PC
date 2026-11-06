package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.InstallVariant

/**
 * The question Windows Installer asks FocusFlow before it removes the app:
 * "may this uninstall go ahead?".
 *
 * The installer package (packaging/windows/main.wxs) runs `FocusFlow.exe
 * --uninstall-check` before it changes anything and waits for the exit code.
 * Because the question is asked from inside the installer, it covers every
 * route to a removal (Settings, Control Panel, `winget`, `msiexec`) and works
 * even when FocusFlow is not running, which the runtime uninstall guard cannot.
 *
 * The answer comes from the same code the uninstall wizard and tray Quit use,
 * so a Standalone Block still means "wait for it to end" and a session, Always-On
 * or Nuclear PIN is still asked for. The prompts are shown by this process.
 *
 * Exit codes are the whole contract with the installer. The installer maps
 * [EXIT_DENY] to "stop the uninstall" and EVERYTHING else to "continue", so a
 * crash, a launch failure or a missing JVM can never trap someone with a broken
 * install who needs to remove it. Keep [EXIT_DENY] equal to the value in main.wxs.
 */
object UninstallCheck {
    const val EXIT_ALLOW = 0
    const val EXIT_DENY = 42

    private const val LOG_TAG = "UninstallCheck"

    fun run(): Int {
        EnforcementLog.info(LOG_TAG, "installer asked whether removal may proceed")
        val code = try {
            decide()
        } catch (failure: Throwable) {
            // Could not even work out the protection state: fail open (see above).
            EnforcementLog.warn(LOG_TAG, "check failed before a decision; allowing removal", failure)
            EXIT_ALLOW
        }
        EnforcementLog.info(LOG_TAG, if (code == EXIT_DENY) "removal denied" else "removal allowed")
        return code
    }

    internal fun decide(): Int {
        if (!InstallVariant.isWindowsDirectInstall) return EXIT_ALLOW

        // Another FocusFlow process (the wizard, or the running app after a PIN)
        // already authorized this removal moments ago.
        if (UninstallAuthorizationHandoff.isGranted()) return EXIT_ALLOW

        // Same read-only database setup the wizard uses. False means the protection
        // state is busy or unsafe to read, which the wizard also refuses.
        if (!UninstallProtectionService.prepareForUninstallWizard()) {
            UninstallProtectionService.showNotice(
                "FocusFlow uninstall",
                "FocusFlow could not read its protection state safely.\n\n" +
                    "The application was not removed."
            )
            return EXIT_DENY
        }

        val allowed = try {
            UninstallProtectionService.authorizeUninstallWizard()
        } catch (failure: Throwable) {
            // A protection is active but could not be asked about (for example no
            // desktop to show a PIN box on): that must not read as permission.
            EnforcementLog.warn(LOG_TAG, "could not complete the authorization; denying removal", failure)
            false
        }
        if (!allowed) return EXIT_DENY

        // If Windows Installer asks again during the same uninstall, do not ask twice.
        UninstallAuthorizationHandoff.grant()
        return EXIT_ALLOW
    }
}
