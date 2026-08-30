package com.focusflow.services

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

    private sealed interface Requirement {
        data class WaitForStandalone(val remainingMs: Long) : Requirement
        data class Pin(
            val feature: String,
            val prompt: String,
            val verify: (String) -> Boolean
        ) : Requirement
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

        val requirements = activeRequirements()
        if (requirements.isEmpty()) return true

        // A standalone block is a hard time condition, not a PIN prompt.
        // Do not sleep here: the quit request is rejected and the running block
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

        // Ask each distinct credential in a stable order.  Multiple active
        // protections therefore cannot be bypassed by satisfying only one.
        requirements.filterIsInstance<Requirement.Pin>().forEach { requirement ->
            if (!promptForPin(requirement)) return false
        }

        return true
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

            val requirements = activeRequirements()
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

            // Nuclear Mode itself blocks an uninstall unless the user has
            // explicitly satisfied every configured feature PIN.
            val pins = requirements.filterIsInstance<Requirement.Pin>()
            if (pins.isEmpty()) return false
            pins.forEach { requirement ->
                if (!promptForPin(requirement)) return false
            }

            authorizedUninstallUntilMs.set(
                System.currentTimeMillis() + AUTHORIZATION_WINDOW_MS
            )
            return true
        }
    }

    private fun activeRequirements(): List<Requirement> {
        val result = mutableListOf<Requirement>()

        runCatching {
            if (StandaloneBlockService.isActive) {
                result += Requirement.WaitForStandalone(StandaloneBlockService.remainingMs())
            }
        }

        runCatching {
            if (ProcessMonitor.alwaysOnEnabled && GlobalPin.isSet()) {
                result += Requirement.Pin(
                    feature = "Always-On enforcement",
                    prompt = "Enter the Global PIN to quit or uninstall while Always-On enforcement is active:",
                    verify = GlobalPin::verify
                )
            }
        }

        runCatching {
            if ((FocusSessionService.state.value.isActive || FocusLauncherService.isActive.value) &&
                SessionPin.isSet()
            ) {
                result += Requirement.Pin(
                    feature = "Focus session",
                    prompt = "Enter the Session PIN to quit or uninstall during the active focus session:",
                    verify = SessionPin::verify
                )
            }
        }

        runCatching {
            if (NuclearMode.isActive && NuclearPin.isSet()) {
                result += Requirement.Pin(
                    feature = "Nuclear Mode",
                    prompt = "Enter the Nuclear Mode PIN to quit or uninstall while Nuclear Mode is active:",
                    verify = NuclearPin::verify
                )
            }
        }

        return result
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