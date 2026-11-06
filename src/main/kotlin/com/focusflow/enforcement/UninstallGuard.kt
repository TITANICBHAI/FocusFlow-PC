package com.focusflow.enforcement

import com.focusflow.services.FocusLauncherService
import com.focusflow.services.FocusSessionService
import com.focusflow.services.ProcessSnapshot
import com.focusflow.services.ProcessSnapshotEntry
import com.focusflow.services.StandaloneBlockService
import com.focusflow.services.UninstallProtectionService
import com.focusflow.services.UninstallProtectionService.UninstallVerdict
import com.focusflow.services.WindowsUninstallRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Stops a Windows Installer removal of FocusFlow while any protection is active.
 *
 * Why this exists: the registry redirect to FocusFlow's own uninstall wizard is
 * only a convenience. It can be missing (first launch not done yet, a repair
 * reset the entry, Windows calls Windows Installer by product code) and a
 * Standalone Block had no process-level guard at all, because the only code
 * that stopped `msiexec` lived inside Nuclear Mode's scan. Every route to an
 * MSI uninstall (Settings, Control Panel, `winget`, PowerShell, a terminal)
 * ends in a `msiexec.exe /x` client process, so this guard watches for that
 * process instead and does not depend on the registry at all.
 *
 * Order of events for a denied attempt:
 *  1. kill the installer client first, while it is still gathering information
 *     (the uninstall has not started changing anything, and Windows Installer
 *     rolls back if it already had);
 *  2. then tell the user why and, if a PIN can authorize it, ask for it. A PIN
 *     opens a short window and the user starts the uninstall again.
 *
 * An attempt that was allowed once (wizard grant or PIN window) is remembered
 * for the lifetime of that process and never killed later, so a slow legitimate
 * uninstall is not rolled back halfway when its authorization window ends.
 *
 * Limits, stated plainly: this is friction, not a security boundary. A process
 * running at a higher integrity level than FocusFlow has a command line that
 * FocusFlow cannot read, so such an attempt is not recognised unless FocusFlow
 * itself runs elevated or Nuclear Mode's blanket installer block is on.
 */
internal object UninstallGuard {

    private const val FAST_POLL_MS = 400L
    private const val IDLE_POLL_MS = 1_500L
    private const val HINT_REFRESH_MS = 5_000L
    private const val HINT_HOLD_MS = 15_000L
    private const val REGISTRATION_REFRESH_MS = 60_000L
    private const val PRODUCT_CODE_REFRESH_MS = 60_000L
    private const val KILL_WAIT_MS = 750L
    private const val MSIEXEC = "msiexec.exe"
    private const val LOG_TAG = "UninstallGuard"

    private enum class Decision { IGNORED, ALLOWED, BLOCKED }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var job: Job? = null
    private val decisions = ConcurrentHashMap<String, Decision>()
    private val promptInFlight = AtomicBoolean(false)
    private val ownPid: Long = ProcessHandle.current().pid()

    @Volatile private var productCodes: Set<String> = emptySet()
    @Volatile private var productCodesLoadedAtMs = 0L
    @Volatile private var lastUnreadableLogMs = 0L
    @Volatile private var lastFailureLogMs = 0L

    /** Starts the guard. Does nothing for Store/MSIX builds, whose removal Windows owns. */
    fun start() {
        if (!InstallVariant.isWindowsDirectInstall) return
        synchronized(this) {
            if (job?.isActive == true) return
            job = scope.launch { runLoop() }
        }
        EnforcementLog.info(LOG_TAG, "started")
    }

    fun stop() {
        synchronized(this) {
            job?.cancel()
            job = null
        }
    }

    /**
     * Called by Nuclear Mode when it has just killed an installer process for a
     * denied attempt, so the user hears why through the same single prompt.
     */
    fun reportBlockedAttempt() {
        if (!promptInFlight.compareAndSet(false, true)) return
        thread(name = "FocusFlow-UninstallGuardPrompt", isDaemon = true) {
            try {
                // Shows the Standalone Block / blocked-feature message or asks for each PIN.
                val authorized = UninstallProtectionService.authorizeUninstallAttempt()
                if (authorized) {
                    UninstallProtectionService.showNotice(
                        "Uninstall authorized",
                        "FocusFlow stopped that uninstall before it changed anything.\n\n" +
                            "You are authorized for the next 30 seconds. Start the uninstall again now."
                    )
                }
            } catch (failure: Throwable) {
                EnforcementLog.warn(LOG_TAG, "prompt after blocked uninstall failed", failure)
            } finally {
                promptInFlight.set(false)
            }
        }
    }

    // ── Loop ─────────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        var lastRegistrationMs = 0L
        var lastHintRefreshMs = 0L
        var hintUntilMs = 0L
        while (currentCoroutineContext().isActive) {
            var watching = false
            try {
                val now = System.currentTimeMillis()

                // Keep the wizard redirect healthy (heals a Windows Installer repair).
                if (now - lastRegistrationMs >= REGISTRATION_REFRESH_MS) {
                    lastRegistrationMs = now
                    runCatching { WindowsUninstallRegistration.ensureRegistered() }
                        .onFailure { EnforcementLog.warn(LOG_TAG, "uninstall entry refresh failed", it) }
                }

                // Protection that lives only in the database (for example an
                // unfinished focus session after a restart) is discovered here.
                if (now - lastHintRefreshMs >= HINT_REFRESH_MS) {
                    lastHintRefreshMs = now
                    if (UninstallProtectionService.uninstallAttemptVerdict() is UninstallVerdict.Deny) {
                        hintUntilMs = now + HINT_HOLD_MS
                    }
                }

                watching = hintUntilMs > now || inMemoryProtectionActive()
                if (watching) scanOnce(ProcessSnapshot.snapshotProcesses())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val now = System.currentTimeMillis()
                if (now - lastFailureLogMs >= 30_000L) {
                    lastFailureLogMs = now
                    EnforcementLog.warn(LOG_TAG, "scan failed; will retry", failure)
                }
            }
            delay(if (watching) FAST_POLL_MS else IDLE_POLL_MS)
        }
    }

    private fun inMemoryProtectionActive(): Boolean =
        StandaloneBlockService.isScheduled ||
            FocusSessionService.state.value.isActive ||
            FocusLauncherService.isActive.value ||
            ProcessMonitor.alwaysOnEnabled ||
            NuclearMode.isActive

    // ── Scan ─────────────────────────────────────────────────────────────

    internal fun scanOnce(snapshot: List<ProcessSnapshotEntry>) {
        val live = HashSet<String>()
        val codes = currentProductCodes()

        for (entry in snapshot) {
            if (entry.pid == ownPid) continue
            val name = entry.processName
                .substringAfterLast('\\')
                .substringAfterLast('/')
                .lowercase()
            if (name != MSIEXEC) continue

            val key = "${entry.pid}:${entry.creationTimeMs ?: 0L}"
            live += key
            // One decision per process: its command line never changes, and an
            // allowed uninstall must never be killed later when a window ends.
            if (decisions.containsKey(key)) continue

            val handle = ProcessHandle.of(entry.pid).orElse(null) ?: continue
            val commandLine = runCatching { handle.info().commandLine().orElse(null) }.getOrNull()
            if (commandLine.isNullOrBlank()) {
                decisions[key] = Decision.IGNORED
                logUnreadableOnce()
                continue
            }
            if (!UninstallCommandMatcher.isFocusFlowRemoval(commandLine, codes)) {
                decisions[key] = Decision.IGNORED
                continue
            }

            when (UninstallProtectionService.uninstallAttemptVerdict()) {
                UninstallVerdict.Allow -> {
                    decisions[key] = Decision.ALLOWED
                    EnforcementLog.info(LOG_TAG, "removal pid=${entry.pid} allowed (authorized or no protection active)")
                }
                is UninstallVerdict.Deny -> {
                    decisions[key] = Decision.BLOCKED
                    val stopped = terminate(handle, entry.pid)
                    EnforcementLog.warn(
                        LOG_TAG,
                        "removal pid=${entry.pid} denied; installer ${if (stopped) "stopped" else "COULD NOT be stopped"}"
                    )
                    reportBlockedAttempt()
                }
            }
        }

        decisions.keys.retainAll(live)
    }

    private fun terminate(handle: ProcessHandle, pid: Long): Boolean {
        runCatching { handle.destroyForcibly() }
        val exited = runCatching {
            handle.onExit().get(KILL_WAIT_MS, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
        if (exited || !handle.isAlive) return true
        return killProcessByPid(pid, MSIEXEC)
    }

    private fun currentProductCodes(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - productCodesLoadedAtMs >= PRODUCT_CODE_REFRESH_MS) {
            productCodesLoadedAtMs = now
            val fresh = runCatching { WindowsUninstallRegistration.productCodes() }.getOrDefault(emptySet())
            // Keep the last known codes if one registry read comes back empty.
            if (fresh.isNotEmpty()) productCodes = fresh
        }
        return productCodes
    }

    private fun logUnreadableOnce() {
        val now = System.currentTimeMillis()
        if (now - lastUnreadableLogMs < 60_000L) return
        lastUnreadableLogMs = now
        EnforcementLog.info(
            LOG_TAG,
            "a msiexec command line could not be read (a higher-privilege process); " +
                "it is not treated as a FocusFlow removal"
        )
    }

    internal fun resetForTest() {
        decisions.clear()
        productCodes = emptySet()
        productCodesLoadedAtMs = 0L
    }
}
