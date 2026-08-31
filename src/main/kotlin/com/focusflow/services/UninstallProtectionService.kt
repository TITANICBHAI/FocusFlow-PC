package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.InstallVariant
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards the app's own quit path for direct EXE/MSI installations.
 *
 * This is deliberately separate from crash cleanup: a normal user-requested
 * quit is gated, while crash recovery and JVM shutdown hooks remain fail-open
 * so a broken process cannot leave the user trapped.
 */
object UninstallProtectionService {

    private const val AUTHORIZATION_WINDOW_MS = 30_000L
    private val authorizedUninstallUntilMs = AtomicLong(0L)
    private val uninstallPromptLock = Any()
    private enum class Action { QUIT, UNINSTALL }

    private sealed interface Requirement {
        data class WaitForStandalone(val remainingMs: Long) : Requirement
        data class Blocked(val feature: String, val message: String) : Requirement
        data class Pin(
            val feature: String,
            val prompt: String,
            val verify: (String) -> Boolean
        ) : Requirement
    }

    /**
     * Prepare the standalone uninstall-wizard JVM to read the same persisted
     * state as the running app. The wizard starts before the normal application
     * bootstrap, so its StateFlows would otherwise all look inactive.
     *
     * This is intentionally not used for MSIX: package removal is owned by
     * Windows and must not be intercepted by FocusFlow.
     */
    fun prepareForUninstallWizard(): Boolean {
        if (!InstallVariant.isWindowsDirectInstall) return true

        return runCatching {
            if (!Database.isReady) Database.init()
            if (!Database.isReady) return false

            ProcessMonitor.alwaysOnEnabled =
                Database.getSetting("always_on_enforcement") == "true"
            true
        }.getOrDefault(false)
    }

    /**
     * Returns true when the user has satisfied every active protection.
     *
     * The stock jpackage uninstaller cannot call Compose code before removing
     * an installation.  Nuclear Mode separately blocks common uninstaller
     * processes while active; this gate protects FocusFlow's normal Quit and
     * close paths and is also the API for a future custom uninstaller helper.
     */
    fun authorizeQuit(): Boolean {
        if (!InstallVariant.isWindowsDirectInstall) return true

        return authorizeRequirements(activeRequirements(Action.QUIT))
    }

    /**
     * Entry point used by the custom EXE/MSI uninstall wizard.
     *
     * This intentionally allows uninstall when no protection is active. When
     * protection is active it uses the same requirements as tray Quit.
     */
    fun authorizeUninstallWizard(): Boolean {
        if (!InstallVariant.isWindowsDirectInstall) return true
        if (!Database.isReady) {
            showMessage(
                "Uninstall protection unavailable",
                "FocusFlow could not read its protection state safely. " +
                    "The application was not removed."
            )
            return false
        }
        return authorizeRequirements(activeRequirements(Action.UNINSTALL))
    }

    /**
     * Called by Nuclear Mode when a direct-installer uninstall process appears.
     *
     * Returning false makes the caller terminate the uninstaller.  A successful
     * PIN sequence opens a short authorization window because MSI can spawn
     * more than one msiexec process during a single uninstall.
     */
    fun authorizeUninstallAttempt(): Boolean {
        if (!InstallVariant.isWindowsDirectInstall) return true

        val now = System.currentTimeMillis()
        if (authorizedUninstallUntilMs.get() > now) return true

        synchronized(uninstallPromptLock) {
            val refreshedNow = System.currentTimeMillis()
            if (authorizedUninstallUntilMs.get() > refreshedNow) return true

            val requirements = activeRequirements(Action.UNINSTALL)
            val standalone = requirements
                .filterIsInstance<Requirement.WaitForStandalone>()
                .firstOrNull()
            if (standalone != null) {
                showMessage(
                    "Uninstall protection is active",
                    "A Standalone Block is active. FocusFlow must remain installed until it ends.\n\n" +
                        "Time remaining: ${formatRemaining(standalone.remainingMs)}"
                )
                return false
            }

            // This method is called from Nuclear Mode's 500ms process scan.
            // Nuclear Mode is itself a hard requirement when no Nuclear PIN
            // exists; otherwise a valid PIN opens a short MSI window.
            val pins = requirements.filterIsInstance<Requirement.Pin>()
            val blocked = requirements.filterIsInstance<Requirement.Blocked>().firstOrNull()
            if (blocked != null) return false
            if (pins.isEmpty()) return true
            pins.forEach { requirement ->
                if (!promptForPin(requirement)) return false
            }

            authorizedUninstallUntilMs.set(
                System.currentTimeMillis() + AUTHORIZATION_WINDOW_MS
            )
            return true
        }
    }

    private fun activeRequirements(action: Action): List<Requirement> {
        val result = mutableListOf<Requirement>()

        runCatching {
            val persistedUntil = Database.getSetting("standalone_block_until")
                ?.toLongOrNull() ?: 0L
            val persistedStart = Database.getSetting("standalone_block_start")
                ?.toLongOrNull() ?: 0L
            val persistedProcesses = Database.getSetting("standalone_block_processes")
                .orEmpty()
            val persistedActive =
                persistedProcesses.isNotBlank() &&
                    persistedUntil > System.currentTimeMillis() &&
                    (persistedStart == 0L || persistedStart <= System.currentTimeMillis())

            if (StandaloneBlockService.isActive || persistedActive) {
                val remaining = if (StandaloneBlockService.isActive) {
                    StandaloneBlockService.remainingMs()
                } else {
                    (persistedUntil - System.currentTimeMillis()).coerceAtLeast(0L)
                }
                result += Requirement.WaitForStandalone(remaining)
            }
        }

        runCatching {
            val persistedAlwaysOn = Database.getSetting("always_on_enforcement") == "true"
            if ((ProcessMonitor.alwaysOnEnabled || persistedAlwaysOn) && GlobalPin.isSet()) {
                result += Requirement.Pin(
                    feature = "Always-On enforcement",
                    prompt = "Enter the Global PIN to quit or uninstall while Always-On enforcement is active:",
                    verify = GlobalPin::verify
                )
            }
        }

        runCatching {
            val persistedFocusSession = Database.hasUnfinishedFocusSession()
            if (FocusSessionService.state.value.isActive ||
                FocusLauncherService.isActive.value ||
                persistedFocusSession
            ) {
                if (action == Action.QUIT && SessionPin.isSet()) {
                    result += Requirement.Pin(
                        feature = "Focus session",
                        prompt = "Enter the Session PIN to quit during the active focus session:",
                        verify = SessionPin::verify
                    )
                } else if (action == Action.UNINSTALL) {
                    if (GlobalPin.isSet()) {
                        result += Requirement.Pin(
                            feature = "Global PIN",
                            prompt = "Enter the Global PIN to uninstall while a focus session is active:",
                            verify = GlobalPin::verify
                        )
                    } else {
                        result += Requirement.Blocked(
                            feature = "Focus session",
                            message = "FocusFlow cannot be uninstalled during an active focus session. " +
                                "Let the session end, or set a Global PIN before starting the session."
                        )
                    }
                }
            }
        }

        runCatching {
            val persistedNuclearMode = Database.getSetting("nuclear_mode") == "true"
            if (NuclearMode.isActive || persistedNuclearMode) {
                if (NuclearPin.isSet()) {
                    result += Requirement.Pin(
                        feature = "Nuclear Mode",
                        prompt = "Enter the Nuclear Mode PIN to quit or uninstall while Nuclear Mode is active:",
                        verify = NuclearPin::verify
                    )
                } else {
                    result += Requirement.Blocked(
                        feature = "Nuclear Mode",
                        message = "Disable Nuclear Mode from within FocusFlow before quitting or uninstalling."
                    )
                }
            }
        }

        return result
    }

    private fun authorizeRequirements(
        requirements: List<Requirement>
    ): Boolean {
        if (requirements.isEmpty()) return true

        // A standalone block is a hard time condition, not a PIN prompt.
        // Do not sleep here: the action is rejected and the running block
        // continues to enforce normally.
        val standalone = requirements.filterIsInstance<Requirement.WaitForStandalone>().firstOrNull()
        if (standalone != null) {
            showMessage(
                "Uninstall protection is active",
                "A Standalone Block is active. FocusFlow must remain installed until it ends.\n\n" +
                    "Time remaining: ${formatRemaining(standalone.remainingMs)}"
            )
            return false
        }

        val blocked = requirements.filterIsInstance<Requirement.Blocked>().firstOrNull()
        if (blocked != null) {
            showMessage(
                "Action blocked",
                "${blocked.message}\n\n" +
                    "FocusFlow will keep running and your Windows installation is unchanged."
            )
            return false
        }

        // Ask each distinct credential in a stable order. Multiple active
        // protections therefore cannot be bypassed by satisfying only one.
        requirements.filterIsInstance<Requirement.Pin>().forEach { requirement ->
            if (!promptForPin(requirement)) return false
        }

        return true
    }

    private fun promptForPin(requirement: Requirement.Pin): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false

        val entered = JOptionPane.showInputDialog(
            null,
            requirement.prompt,
            "${requirement.feature} protection",
            JOptionPane.WARNING_MESSAGE
        ) ?: return false

        if (requirement.verify(entered)) return true

        showMessage(
            "Incorrect PIN",
            "The ${requirement.feature} PIN was incorrect. FocusFlow will keep running."
        )
        return false
    }

    private fun showMessage(title: String, message: String) {
        if (GraphicsEnvironment.isHeadless()) return
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE)
    }

    private fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            "${hours}h ${minutes}m ${seconds}s"
        } else {
            "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        }
    }
}