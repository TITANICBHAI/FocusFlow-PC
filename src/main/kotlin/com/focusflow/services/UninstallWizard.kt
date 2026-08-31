package com.focusflow.services

import com.focusflow.enforcement.InstallVariant
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane

/**
 * Small native Windows uninstall wizard.
 *
 * Windows Installer still performs the actual file/registry removal. This
 * process is only the user-facing gate in front of that operation, which keeps
 * the EXE and MSI uninstall paths consistent with tray Quit.
 */
object UninstallWizard {

    fun run() {
        if (!InstallVariant.isWindowsDirectInstall) return
        if (GraphicsEnvironment.isHeadless()) return

        val registered = WindowsUninstallRegistration.registeredCommand()
        if (registered == null) {
            showMessage(
                "FocusFlow uninstall",
                "FocusFlow could not find its Windows Installer entry.\n\n" +
                    "Open Windows Settings and try Apps → Installed apps again."
            )
            return
        }

        if (!confirmStart()) return
        if (!UninstallProtectionService.authorizeUninstallWizard()) return

        val confirmed = JOptionPane.showConfirmDialog(
            null,
            "All FocusFlow data and the installed application will be removed by Windows Installer.\n\n" +
                "Continue with uninstall?",
            "Confirm FocusFlow uninstall",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirmed != JOptionPane.YES_OPTION) return

        try {
            // The original command is created by jpackage and retained in the
            // registry by WindowsUninstallRegistration. cmd.exe preserves its
            // quoting and lets both MSI and EXE-generated commands work.
            ProcessBuilder("cmd.exe", "/d", "/s", "/c", registered.command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            showMessage(
                "FocusFlow uninstall could not start",
                "Windows Installer could not be started.\n\n${e.message ?: "Unknown error"}"
            )
        }
    }

    private fun confirmStart(): Boolean {
        val answer = JOptionPane.showConfirmDialog(
            null,
            "FocusFlow will ask Windows Installer to remove this installation.\n\n" +
                "Any active protection must finish or be authorized before uninstall can continue.",
            "FocusFlow uninstall wizard",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.INFORMATION_MESSAGE
        )
        return answer == JOptionPane.OK_OPTION
    }

    private fun showMessage(title: String, message: String) {
        JOptionPane.showMessageDialog(
            null,
            message,
            title,
            JOptionPane.WARNING_MESSAGE
        )
    }
}