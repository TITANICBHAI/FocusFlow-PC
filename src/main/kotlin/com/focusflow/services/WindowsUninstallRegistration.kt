package com.focusflow.services

import com.focusflow.enforcement.InstallVariant
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File

/**
 * Makes the direct-install uninstall entry point FocusFlow-owned.
 *
 * jpackage creates the initial EXE/MSI uninstall entry.  On the first launch
 * after installation, this replaces only FocusFlow's own UninstallString with
 * the installed executable plus --uninstall, while retaining the original
 * Windows Installer command in a private value.  The custom entry point can
 * then run the same Windows Installer command after FocusFlow's protection
 * checks have completed.
 *
 * This intentionally does not touch MSIX registrations: package removal for a
 * Store app is controlled by Windows.
 */
object WindowsUninstallRegistration {

    private const val UNINSTALL_ROOT =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
    private const val DISPLAY_NAME = "DisplayName"
    private const val UNINSTALL_STRING = "UninstallString"
    private const val QUIET_UNINSTALL_STRING = "QuietUninstallString"
    private const val ORIGINAL_UNINSTALL_STRING = "FocusFlowOriginalUninstallString"
    private const val WIZARD_MARKER = "FocusFlowUninstallWizard"

    data class RegisteredCommand(
        val registryPath: String,
        val command: String
    )

    fun ensureRegistered() {
        if (!InstallVariant.isWindowsDirectInstall) return

        val executable = currentExecutable() ?: return
        if (!executable.exists() || !executable.name.endsWith(".exe", ignoreCase = true)) return

        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            val subkeys = try {
                Advapi32Util.registryGetKeys(hive, UNINSTALL_ROOT)
            } catch (_: Throwable) {
                continue
            }

            for (subkey in subkeys) {
                val path = "$UNINSTALL_ROOT\\$subkey"
                val values = try {
                    Advapi32Util.registryGetValues(hive, path)
                } catch (_: Throwable) {
                    continue
                }

                val displayName = (values[DISPLAY_NAME] as? String)?.trim() ?: continue
                if (!displayName.equals("FocusFlow", ignoreCase = true) &&
                    !displayName.startsWith("FocusFlow ", ignoreCase = true)
                ) {
                    continue
                }

                val existing = (values[UNINSTALL_STRING] as? String)?.trim().orEmpty()
                val original = (values[ORIGINAL_UNINSTALL_STRING] as? String)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: existing.takeIf { it.isNotBlank() }
                    ?: continue
                val removalCommand = normalizeRemovalCommand(original)

                // If a previous launch already installed the wrapper, preserve
                // the original command and only refresh the path to this EXE.
                try {
                    Advapi32Util.registrySetStringValue(
                        hive,
                        path,
                        ORIGINAL_UNINSTALL_STRING,
                        removalCommand
                    )
                    Advapi32Util.registrySetStringValue(
                        hive,
                        path,
                        WIZARD_MARKER,
                        "1"
                    )
                    val wizardCommand = "\"${executable.absolutePath}\" --uninstall"
                    Advapi32Util.registrySetStringValue(
                        hive,
                        path,
                        UNINSTALL_STRING,
                        wizardCommand
                    )
                    // Windows Settings normally uses UninstallString, while
                    // package managers may use QuietUninstallString. Both must
                    // pass through the same gate instead of silently bypassing it.
                    Advapi32Util.registrySetStringValue(
                        hive,
                        path,
                        QUIET_UNINSTALL_STRING,
                        wizardCommand
                    )
                    return
                } catch (_: Throwable) {
                    // HKLM may be readable but not writable for a per-user
                    // process. Continue so HKCU can still be registered.
                }
            }
        }
    }

    fun registeredCommand(): RegisteredCommand? {
        if (!InstallVariant.isWindowsDirectInstall) return null

        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            val subkeys = try {
                Advapi32Util.registryGetKeys(hive, UNINSTALL_ROOT)
            } catch (_: Throwable) {
                continue
            }

            for (subkey in subkeys) {
                val path = "$UNINSTALL_ROOT\\$subkey"
                try {
                    val values = Advapi32Util.registryGetValues(hive, path)
                    if ((values[WIZARD_MARKER] as? String) != "1") continue
                    val command = (values[ORIGINAL_UNINSTALL_STRING] as? String)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::normalizeRemovalCommand)
                        ?: continue
                    return RegisteredCommand(path, command)
                } catch (_: Throwable) {
                    // Registry entries can disappear while Windows Installer is
                    // repairing or updating the installation.
                }
            }
        }
        return null
    }

    /**
     * Older MSI registrations can expose maintenance mode through
     * `msiexec.exe /I{product-code}`. Once the user has passed FocusFlow's
     * wizard, the handoff must be an uninstall, not a repair/change operation.
     * EXE-based uninstallers and already-correct `/X` commands are unchanged.
     */
    private fun normalizeRemovalCommand(command: String): String {
        val maintenanceSwitch = Regex(
            pattern = """(?i)(\bmsiexec(?:\.exe)?\s+)/i(?=[\s{])"""
        )
        return command.replace(maintenanceSwitch) { match ->
            "${match.groupValues[1]}/x"
        }
    }

    /**
     * Resolve the installed jpackage launcher rather than the bundled JVM.
     *
     * Depending on the Compose/JDK launcher, the current process may be either
     * FocusFlow.exe or runtime\bin\java.exe. The launcher is at the install
     * root, beside the app and runtime directories. The old implementation
     * checked inside runtime and therefore silently skipped registration for
     * launches reporting java.exe.
     */
    private fun currentExecutable(): File? = runCatching {
        val command = ProcessHandle.current().info().command().orElse(null)
            ?.let(::File)
        val commandName = command?.name.orEmpty()

        if (command != null &&
            command.isFile &&
            commandName.equals("FocusFlow.exe", ignoreCase = true)
        ) {
            return@runCatching command
        }

        val roots = sequenceOf(
            command,
            System.getProperty("compose.application.resources.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
        )
            .filterNotNull()
            .flatMap { file ->
                generateSequence(file) { it.parentFile }.take(8)
            }
            .distinctBy { it.absolutePath.lowercase() }

        roots
            .map { File(it, "FocusFlow.exe") }
            .firstOrNull { it.isFile }
    }.getOrNull()
}