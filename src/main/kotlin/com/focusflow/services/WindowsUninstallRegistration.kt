package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.InstallVariant
import com.focusflow.enforcement.UninstallCommandMatcher
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
    private const val LOG_TAG = "UninstallRegistration"

    data class RegisteredCommand(
        val registryPath: String,
        val command: String
    )

    /** What the last [ensureRegistered] call found, for the log and for tests. */
    enum class RegistrationStatus {
        /** Not a direct EXE/MSI install (Store/MSIX or non-Windows): nothing to do. */
        NOT_APPLICABLE,
        /** FocusFlow.exe could not be located next to the runtime. */
        EXECUTABLE_NOT_FOUND,
        /** No Uninstall entry named FocusFlow exists in HKCU or HKLM. */
        ENTRY_NOT_FOUND,
        /** An entry exists but every write failed (typically HKLM without admin rights). */
        WRITE_FAILED,
        /** The entry already pointed at the wizard; nothing was written. */
        ALREADY_REGISTERED,
        /** The entry was (re)written to point at the wizard. */
        REGISTERED
    }

    @Volatile private var lastLoggedStatus: RegistrationStatus? = null

    /**
     * Points FocusFlow's Windows uninstall entry at the wizard.
     *
     * Safe to call repeatedly: it writes only when the entry does not already
     * point at this executable, so the guard can call it periodically and heal
     * the entry after a Windows Installer repair or upgrade resets it. Every
     * outcome other than "nothing changed" is written to the enforcement log;
     * a silent failure here previously made a bypassed uninstall impossible
     * to diagnose.
     */
    fun ensureRegistered(): RegistrationStatus {
        val status = registerInternal()
        logStatusChange(status)
        return status
    }

    private fun registerInternal(): RegistrationStatus {
        if (!InstallVariant.isWindowsDirectInstall) return RegistrationStatus.NOT_APPLICABLE

        val executable = currentExecutable()
        if (executable == null || !executable.exists() ||
            !executable.name.endsWith(".exe", ignoreCase = true)
        ) {
            return RegistrationStatus.EXECUTABLE_NOT_FOUND
        }

        val wizardCommand = "\"${executable.absolutePath}\" --uninstall"
        var foundEntry = false

        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            for (entry in focusFlowEntries(hive)) {
                foundEntry = true
                val values = entry.values

                val existing = (values[UNINSTALL_STRING] as? String)?.trim().orEmpty()
                val original = (values[ORIGINAL_UNINSTALL_STRING] as? String)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: existing.takeIf { it.isNotBlank() }
                    ?: continue
                val removalCommand = normalizeRemovalCommand(original)

                val alreadyCorrect =
                    existing == wizardCommand &&
                        (values[QUIET_UNINSTALL_STRING] as? String)?.trim() == wizardCommand &&
                        (values[WIZARD_MARKER] as? String) == "1" &&
                        (values[ORIGINAL_UNINSTALL_STRING] as? String)?.trim() == removalCommand
                if (alreadyCorrect) return RegistrationStatus.ALREADY_REGISTERED

                // If a previous launch already installed the wrapper, preserve
                // the original command and only refresh the path to this EXE.
                try {
                    Advapi32Util.registrySetStringValue(
                        hive, entry.path, ORIGINAL_UNINSTALL_STRING, removalCommand
                    )
                    Advapi32Util.registrySetStringValue(hive, entry.path, WIZARD_MARKER, "1")
                    Advapi32Util.registrySetStringValue(hive, entry.path, UNINSTALL_STRING, wizardCommand)
                    // Windows Settings normally uses UninstallString, while
                    // package managers may use QuietUninstallString. Both must
                    // pass through the same gate instead of silently bypassing it.
                    Advapi32Util.registrySetStringValue(
                        hive, entry.path, QUIET_UNINSTALL_STRING, wizardCommand
                    )
                    return RegistrationStatus.REGISTERED
                } catch (failure: Throwable) {
                    // HKLM may be readable but not writable for a per-user
                    // process. Continue so HKCU can still be registered.
                    EnforcementLog.warn(
                        LOG_TAG,
                        "could not write uninstall entry ${hiveName(hive)}\\${entry.path}",
                        failure
                    )
                }
            }
        }
        return if (foundEntry) RegistrationStatus.WRITE_FAILED else RegistrationStatus.ENTRY_NOT_FOUND
    }

    private fun logStatusChange(status: RegistrationStatus) {
        if (status == lastLoggedStatus) return
        lastLoggedStatus = status
        when (status) {
            RegistrationStatus.NOT_APPLICABLE -> Unit
            RegistrationStatus.REGISTERED,
            RegistrationStatus.ALREADY_REGISTERED ->
                EnforcementLog.info(LOG_TAG, "uninstall entry status=$status")
            RegistrationStatus.EXECUTABLE_NOT_FOUND,
            RegistrationStatus.ENTRY_NOT_FOUND,
            RegistrationStatus.WRITE_FAILED ->
                EnforcementLog.warn(
                    LOG_TAG,
                    "uninstall entry status=$status: the wizard redirect is NOT in place; " +
                        "the uninstall guard still stops a removal while a protection is active"
                )
        }
    }

    /**
     * FocusFlow's installed Windows Installer product codes (upper-case, with
     * braces), read from its Uninstall entries. Read-only, so it works even when
     * writing the entry is not possible. The uninstall guard matches these
     * against a running `msiexec` command line.
     */
    fun productCodes(): Set<String> {
        if (!InstallVariant.isWindowsDirectInstall) return emptySet()
        val codes = LinkedHashSet<String>()
        for (hive in listOf(WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE)) {
            for (entry in focusFlowEntries(hive)) {
                codes += UninstallCommandMatcher.productCodesIn(
                    entry.path.substringAfterLast('\\'),
                    entry.values[UNINSTALL_STRING] as? String,
                    entry.values[ORIGINAL_UNINSTALL_STRING] as? String,
                    entry.values[QUIET_UNINSTALL_STRING] as? String,
                    entry.values["ModifyPath"] as? String
                )
            }
        }
        return codes
    }

    private class UninstallEntry(val path: String, val values: Map<String, Any?>)

    /** Every Uninstall subkey in [hive] whose DisplayName is FocusFlow. */
    private fun focusFlowEntries(hive: WinReg.HKEY): List<UninstallEntry> {
        val subkeys = try {
            Advapi32Util.registryGetKeys(hive, UNINSTALL_ROOT)
        } catch (_: Throwable) {
            return emptyList()
        }
        val result = ArrayList<UninstallEntry>()
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
            @Suppress("UNCHECKED_CAST")
            result += UninstallEntry(path, values as Map<String, Any?>)
        }
        return result
    }

    private fun hiveName(hive: WinReg.HKEY): String =
        if (hive == WinReg.HKEY_CURRENT_USER) "HKCU" else "HKLM"

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