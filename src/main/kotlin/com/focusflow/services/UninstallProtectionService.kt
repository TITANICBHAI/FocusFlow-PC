package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.DbInitResult
import com.focusflow.data.SqliteFailureKind
import com.focusflow.data.Database.SettingRead
import com.focusflow.data.classifySqliteFailure
import com.focusflow.enforcement.InstallVariant
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import java.awt.GraphicsEnvironment
import java.sql.SQLException
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
    internal const val UNREADABLE_DB_CONFIRMATION_PHRASE = "UNINSTALL FOCUSFLOW"
    private val WIZARD_BUSY_RETRY_DELAYS_MS = listOf(1_000L, 2_000L)
    private val authorizedUninstallUntilMs = AtomicLong(0L)
    private val uninstallPromptLock = Any()
    internal enum class Action { QUIT, UNINSTALL }
    internal enum class WizardDatabaseDecision {
        READABLE,
        MISSING,
        REQUIRE_CONFIRMATION,
        BLOCK
    }

    @Volatile
    internal var isDirectInstallForTest: Boolean? = null
    @Volatile
    internal var wizardDatabaseOpenForTest: (() -> DbInitResult)? = null
    @Volatile
    internal var wizardBusyRetryDelaysForTest: List<Long>? = null
    @Volatile
    internal var unreadableDbPhrasePromptForTest: (() -> String?)? = null
    @Volatile
    internal var pinPromptForTest: ((Requirement.Pin) -> String?)? = null
    @Volatile
    internal var messageDialogForTest: ((String, String) -> Unit)? = null
    @Volatile
    private var wizardDatabaseMissing = false
    @Volatile
    private var wizardDatabaseUnreadable = false

    private val isDirectInstall: Boolean
        get() = isDirectInstallForTest ?: InstallVariant.isWindowsDirectInstall

    internal sealed interface Requirement {
        data class WaitForStandalone(val remainingMs: Long) : Requirement
        data class Blocked(
            val feature: String,
            val message: String,
            val databaseFailure: Throwable? = null
        ) : Requirement
        data class Pin(
            val feature: String,
            val prompt: String,
            val verify: (String) -> PinVerificationResult
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
        wizardDatabaseMissing = false
        wizardDatabaseUnreadable = false
        if (!isDirectInstall) return true

        return runCatching {
            val result = openWizardDatabaseWithBusyRetries()
            when (decideWizardDatabaseOutcome(result, Database.isReady)) {
                WizardDatabaseDecision.BLOCK -> false
                WizardDatabaseDecision.REQUIRE_CONFIRMATION -> {
                    wizardDatabaseUnreadable = true
                    true
                }
                WizardDatabaseDecision.MISSING -> {
                    // A missing database contains no persisted protection state.
                    wizardDatabaseMissing = true
                    ProcessMonitor.alwaysOnEnabled = false
                    true
                }
                WizardDatabaseDecision.READABLE -> when (val setting =
                    Database.readSetting("always_on_enforcement")
                ) {
                    is SettingRead.Present -> when (setting.value) {
                        "true" -> {
                            ProcessMonitor.alwaysOnEnabled = true
                            true
                        }
                        "false" -> {
                            ProcessMonitor.alwaysOnEnabled = false
                            true
                        }
                        else -> {
                            wizardDatabaseUnreadable = true
                            true
                        }
                    }
                    SettingRead.Absent -> {
                        ProcessMonitor.alwaysOnEnabled = false
                        true
                    }
                    is SettingRead.Unreadable -> {
                        if (isBusyOrLocked(setting.cause)) {
                            false
                        } else {
                            wizardDatabaseUnreadable = true
                            true
                        }
                    }
                }
            }
        }.getOrDefault(false)
    }

    internal fun decideWizardDatabaseOutcome(
        result: DbInitResult,
        databaseReady: Boolean
    ): WizardDatabaseDecision = when (result) {
        DbInitResult.Ready ->
            if (databaseReady) WizardDatabaseDecision.READABLE else WizardDatabaseDecision.MISSING
        is DbInitResult.Busy -> WizardDatabaseDecision.BLOCK
        is DbInitResult.Failed ->
            if (isBusyOrLocked(result.cause)) {
                WizardDatabaseDecision.BLOCK
            } else {
                WizardDatabaseDecision.REQUIRE_CONFIRMATION
            }
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
        if (!isDirectInstall) return true

        return authorizeRequirements(activeRequirements(Action.QUIT))
    }

    /**
     * Entry point used by the custom EXE/MSI uninstall wizard.
     *
     * This intentionally allows uninstall when no protection is active. When
     * protection is active it uses the same requirements as tray Quit.
     */
    fun authorizeUninstallWizard(): Boolean {
        if (!isDirectInstall) return true
        if (wizardDatabaseMissing) {
            wizardDatabaseMissing = false
            return true
        }
        if (wizardDatabaseUnreadable) {
            wizardDatabaseUnreadable = false
            return confirmUnreadableDatabase()
        }
        if (!Database.isReady) {
            showMessage(
                "Uninstall protection unavailable",
                "FocusFlow could not read its protection state safely. " +
                    "The application was not removed."
            )
            return false
        }
        wizardDatabaseMissing = false
        val requirements = activeRequirements(Action.UNINSTALL)
        val unreadableDatabase = requirements
            .filterIsInstance<Requirement.Blocked>()
            .mapNotNull { it.databaseFailure }
        if (unreadableDatabase.any(::isBusyOrLocked)) {
            return authorizeRequirements(requirements)
        }
        if (unreadableDatabase.isNotEmpty()) {
            return confirmUnreadableDatabase()
        }
        return authorizeRequirements(requirements)
    }

    /**
     * Called by Nuclear Mode when a direct-installer uninstall process appears.
     *
     * Returning false makes the caller terminate the uninstaller.  A successful
     * PIN sequence opens a short authorization window because MSI can spawn
     * more than one msiexec process during a single uninstall.
     */
    fun authorizeUninstallAttempt(): Boolean {
        if (!isDirectInstall) return true

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

    private fun openWizardDatabaseWithBusyRetries(): DbInitResult {
        val retryDelays = wizardBusyRetryDelaysForTest ?: WIZARD_BUSY_RETRY_DELAYS_MS
        var retryIndex = 0
        while (true) {
            val result = wizardDatabaseOpenForTest?.invoke()
                ?: Database.initReadOnlyForWizard()
            if (result !is DbInitResult.Busy) return result

            val delayMs = retryDelays.getOrNull(retryIndex++) ?: return result
            if (delayMs <= 0L) continue
            try {
                Thread.sleep(delayMs)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return result
            }
        }
    }

    internal fun activeRequirements(action: Action): List<Requirement> {
        val result = mutableListOf<Requirement>()

        val standaloneReads = listOf(
            Database.readSetting("standalone_block_until"),
            Database.readSetting("standalone_block_start"),
            Database.readSetting("standalone_block_processes")
        )
        val unreadableStandalone = standaloneReads
            .filterIsInstance<SettingRead.Unreadable>()
            .firstOrNull()
        if (unreadableStandalone != null) {
            result += unreadableRequirement("Standalone Block", unreadableStandalone.cause)
        } else {
            val untilRead = standaloneReads[0] as? SettingRead.Present
            val startRead = standaloneReads[1] as? SettingRead.Present
            val persistedUntil = untilRead?.value?.toLongOrNull()
            val persistedStart = startRead?.value?.toLongOrNull()
            val persistedProcesses = (standaloneReads[2] as? SettingRead.Present)?.value.orEmpty()
            val now = System.currentTimeMillis()
            val malformed = (untilRead != null && persistedUntil == null) ||
                (startRead != null && persistedStart == null) ||
                (persistedProcesses.isNotBlank() && persistedUntil == null) ||
                (persistedUntil != null && persistedUntil > now && persistedProcesses.isBlank())
            if (malformed) {
                result += unreadableRequirement(
                    "Standalone Block",
                    IllegalArgumentException("Malformed persisted Standalone Block settings.")
                )
            }
            val persistedActive = persistedProcesses.isNotBlank() &&
                (persistedUntil ?: 0L) > now &&
                (persistedStart == null || persistedStart == 0L || persistedStart <= now)

            if (StandaloneBlockService.isActive || persistedActive) {
                val remaining = if (StandaloneBlockService.isActive) {
                    StandaloneBlockService.remainingMs()
                } else {
                    ((persistedUntil ?: 0L) - now).coerceAtLeast(0L)
                }
                result += Requirement.WaitForStandalone(remaining)
            }
        }

        when (val setting = Database.readSetting("always_on_enforcement")) {
            is SettingRead.Unreadable ->
                result += unreadableRequirement("Always-On enforcement", setting.cause)
            is SettingRead.Present -> {
                if (setting.value != "true" && setting.value != "false") {
                    result += unreadableRequirement(
                        "Always-On enforcement",
                        IllegalArgumentException("Malformed Always-On setting.")
                    )
                }
                if ((ProcessMonitor.alwaysOnEnabled || setting.value == "true") && GlobalPin.isSet()) {
                    result += Requirement.Pin(
                        feature = "Always-On enforcement",
                        prompt = "Enter the Global PIN to quit or uninstall while Always-On enforcement is active:",
                        verify = GlobalPin::verifyResult
                    )
                }
            }
            SettingRead.Absent -> if (ProcessMonitor.alwaysOnEnabled && GlobalPin.isSet()) {
                result += Requirement.Pin(
                    feature = "Always-On enforcement",
                    prompt = "Enter the Global PIN to quit or uninstall while Always-On enforcement is active:",
                    verify = GlobalPin::verifyResult
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
                        verify = SessionPin::verifyResult
                    )
                } else if (action == Action.UNINSTALL) {
                    if (GlobalPin.isSet()) {
                        result += Requirement.Pin(
                            feature = "Global PIN",
                            prompt = "Enter the Global PIN to uninstall while a focus session is active:",
                            verify = GlobalPin::verifyResult
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

        fun addNuclearModeRequirement() {
            if (!NuclearMode.isActive) return
            if (NuclearPin.isSet()) {
                result += Requirement.Pin(
                    feature = "Nuclear Mode",
                    prompt = "Enter the Nuclear Mode PIN to quit or uninstall while Nuclear Mode is active:",
                    verify = NuclearPin::verifyResult
                )
            } else {
                result += Requirement.Blocked(
                    feature = "Nuclear Mode",
                    message = "Disable Nuclear Mode from within FocusFlow before quitting or uninstalling."
                )
            }
        }

        when (val setting = Database.readSetting("nuclear_mode")) {
            is SettingRead.Unreadable ->
                result += unreadableRequirement("Nuclear Mode", setting.cause)
            is SettingRead.Present -> when (setting.value) {
                "true" -> {
                    if (NuclearMode.isActive) addNuclearModeRequirement()
                    else if (NuclearPin.isSet()) {
                        result += Requirement.Pin(
                            feature = "Nuclear Mode",
                            prompt = "Enter the Nuclear Mode PIN to quit or uninstall while Nuclear Mode is active:",
                            verify = NuclearPin::verifyResult
                        )
                    } else {
                        result += Requirement.Blocked(
                            feature = "Nuclear Mode",
                            message = "Disable Nuclear Mode from within FocusFlow before quitting or uninstalling."
                        )
                    }
                }
                "false" -> addNuclearModeRequirement()
                else -> result += unreadableRequirement(
                    "Nuclear Mode",
                    IllegalArgumentException("Malformed Nuclear Mode setting.")
                )
            }
            SettingRead.Absent -> addNuclearModeRequirement()
        }

        return result
    }

    private fun unreadableRequirement(feature: String, cause: Throwable? = null) =
        Requirement.Blocked(
            feature = feature,
            message = "FocusFlow could not read the $feature protection settings safely. " +
                "Try again when the database is available.",
            databaseFailure = cause
        )

    private fun confirmUnreadableDatabase(): Boolean {
        val promptForTest = unreadableDbPhrasePromptForTest
        val enteredPhrase = if (promptForTest != null) {
            promptForTest()
        } else {
            if (GraphicsEnvironment.isHeadless()) return false
            JOptionPane.showInputDialog(
                null,
                "FocusFlow cannot read its protection settings, so it cannot verify any PIN or block.\n\n" +
                    "To continue anyway, type $UNREADABLE_DB_CONFIRMATION_PHRASE exactly.",
                "Protection settings unavailable",
                JOptionPane.WARNING_MESSAGE
            )
        } ?: return false

        if (enteredPhrase.trim() == UNREADABLE_DB_CONFIRMATION_PHRASE) return true
        showMessage(
            "Confirmation did not match",
            "The confirmation phrase did not match. The application was not removed."
        )
        return false
    }

    private fun isBusyOrLocked(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var depth = 0
        while (current != null && depth++ < 16) {
            if (current is SQLException) {
                when (classifySqliteFailure(current)) {
                    SqliteFailureKind.BUSY, SqliteFailureKind.LOCKED -> return true
                    else -> Unit
                }
            }
            current = current.cause
        }
        return false
    }

    internal fun resetForTest() {
        wizardDatabaseMissing = false
        wizardDatabaseUnreadable = false
        isDirectInstallForTest = null
        wizardDatabaseOpenForTest = null
        wizardBusyRetryDelaysForTest = null
        unreadableDbPhrasePromptForTest = null
        pinPromptForTest = null
        messageDialogForTest = null
        authorizedUninstallUntilMs.set(0L)
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
        val promptForTest = pinPromptForTest
        val entered = if (promptForTest != null) {
            promptForTest(requirement)
        } else {
            if (GraphicsEnvironment.isHeadless()) return false
            JOptionPane.showInputDialog(
                null,
                requirement.prompt,
                "${requirement.feature} protection",
                JOptionPane.WARNING_MESSAGE
            )
        } ?: return false

        return when (requirement.verify(entered)) {
            PinVerificationResult.ACCEPTED -> true
            PinVerificationResult.INCORRECT -> {
                showMessage(
                    "Incorrect PIN",
                    "The ${requirement.feature} PIN was incorrect. FocusFlow will keep running."
                )
                false
            }
            PinVerificationResult.UNAVAILABLE -> {
                showMessage("PIN settings unavailable", PinPolicy.SETTINGS_READ_ERROR)
                false
            }
        }
    }

    private fun showMessage(title: String, message: String) {
        val dialogForTest = messageDialogForTest
        if (dialogForTest != null) {
            dialogForTest(title, message)
            return
        }
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