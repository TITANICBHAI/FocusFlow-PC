package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.services.SoundAversion
import com.focusflow.services.SystemTrayManager
import com.focusflow.services.UninstallProtectionService
import kotlinx.coroutines.*
import java.awt.TrayIcon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.focusflow.enforcement.killProcessesByName

/**
 * NuclearMode — three-layer escape-route enforcement
 *
 * Layer 1 — Detect (efficient):
 *   A single `tasklist /FO CSV /NH` call captures every running process name.
 *   The output is filtered against [escapeProcesses] in-memory. This replaces
 *   40+ individual taskkill spawns with ONE scan per tick — from ~8 000 process
 *   spawns/min down to 1 per tick.
 *
 * Layer 2 — Kill (batch):
 *   If any escape processes are found, ONE `taskkill /F /IM proc1 /IM proc2 …`
 *   call terminates all of them simultaneously. Escape attempts are counted
 *   in-memory (per process name) and persisted to the DB every 5 hits so the
 *   Stats screen can surface "attempted escape N times" data.
 *
 * Layer 3 — Block (firewall):
 *   When nuclear mode activates, outbound-block firewall rules are added via
 *   NetworkBlocker for every escape process. Even if a user renames taskmgr.exe,
 *   the firewall prevents the original path from reaching the network. Rules are
 *   removed cleanly when nuclear mode deactivates.
 */
object NuclearMode {

    private val directInstallerProcesses = setOf(
        "msiexec.exe",
        "unins000.exe",
        "uninstall.exe",
        "uninstaller.exe"
    )

    /** Processes that could be used to escape focus enforcement. */
    private val escapeProcesses = setOf(
        // Task management / process viewers
        "taskmgr.exe",
        "procexp.exe", "procexp64.exe", "procmon.exe", "procmon64.exe",
        "processhacker.exe", "processhacker2.exe", "systemexplorer.exe",
        "perfmon.exe", "resmon.exe",
        // Registry / config editors
        "regedit.exe", "regedt32.exe", "msconfig.exe",
        // Shells / terminals
        "cmd.exe", "powershell.exe", "powershell_ise.exe", "pwsh.exe",
        "wt.exe", "mintty.exe",
        "conemu64.exe", "conemu.exe", "cmder.exe",
        "bash.exe", "zsh.exe", "sh.exe",
        "ubuntu.exe", "debian.exe", "kali.exe",
        "wsl.exe", "wslhost.exe",
        // MMC snap-ins / admin tools
        "mmc.exe", "eventvwr.exe",
        "wscript.exe", "cscript.exe", "mshta.exe",
        "wmic.exe", "winrm.exe",
        // Installers (could download bypass tools). Direct-installer
        // uninstallers are also watched while Nuclear Mode is active.
        "winget.exe", "msiexec.exe",
        "unins000.exe", "uninstall.exe", "uninstaller.exe"
    )

    /**
     * Known canonical Windows paths for escape tools.
     * Used to catch renamed executables — e.g. taskmgr.exe copied/renamed to
     * notmalware.exe still lives in System32 and matches by path suffix.
     * Values are lowercase path segments; comparison uses endsWith on the
     * lowercased full path returned by ProcessHandle.
     */
    private val knownEscapePathSuffixes: Set<String> = setOf(
        "\\windows\\system32\\taskmgr.exe",
        "\\windows\\syswow64\\taskmgr.exe",
        "\\windows\\regedit.exe",
        "\\windows\\system32\\regedt32.exe",
        "\\windows\\system32\\cmd.exe",
        "\\windows\\system32\\mmc.exe",
        "\\windows\\system32\\eventvwr.exe",
        "\\windows\\system32\\perfmon.exe",
        "\\windows\\system32\\resmon.exe",
        "\\windows\\system32\\msconfig.exe",
        "\\windows\\system32\\wmic.exe",
        "\\windows\\system32\\mshta.exe",
        "\\windows\\system32\\wscript.exe",
        "\\windows\\system32\\cscript.exe",
        "\\windows\\system32\\msiexec.exe",
        "\\windows\\system32\\windowspowershell\\v1.0\\powershell.exe",
        "\\windows\\syswow64\\windowspowershell\\v1.0\\powershell.exe",
        "\\windows\\system32\\windowspowershell\\v1.0\\powershell_ise.exe",
        "\\windows\\system32\\wbem\\wmic.exe"
    )

    private val ownPid: Long = ProcessHandle.current().pid()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var monitorJob: Job? = null

    /** Per-process escape attempt counter — flushed to DB every 5 hits. */
    private val escapeCounts = ConcurrentHashMap<String, Int>()

    // AtomicBoolean so enable()/disable() are race-free: two simultaneous enable()
    // calls cannot both pass the guard and launch two monitorJobs.
    private val _isActiveAtomic = AtomicBoolean(false)
    val isActive: Boolean get() = _isActiveAtomic.get()

    /** Number of processes in the escape-route list (shown on the Nuclear Mode screen). */
    val escapeProcessCount: Int get() = escapeProcesses.size

    /** Names of all monitored escape processes (shown on the Nuclear Mode screen). */
    val escapeProcessNames: Set<String> get() = escapeProcesses

    // Reference to the background firewall-cleanup thread so awaitCleanup() can join it.
    @Volatile private var cleanupThread: Thread? = null
    // Serialises firewall apply/remove operations. Without this, a rapid
    // disable() -> enable() can let the old cleanup remove rules after the
    // new enable has applied them.
    private val firewallLock = Any()
    // Keeps cleanup-thread handoff atomic with the active-state transition.
    // Otherwise enable() can miss a cleanup thread created by disable().
    private val lifecycleLock = Any()

    // ── Layer 1: Detect ─────────────────────────────────────────────────────

    /**
     * Returns the subset of [escapeProcesses] that are currently running.
     * Uses ONE `tasklist` call and parses CSV output in-memory — O(n) scan
     * rather than n individual process spawns.
     */
    private fun getRunningEscapeProcesses(): Set<String> {
        if (!isWindows) return getRunningEscapeProcessesFallback()
        return try {
            val proc = ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                .redirectErrorStream(true)
                .start()
            // Use `.use {}` so the BufferedReader (and its underlying InputStream) is
            // closed as soon as we've consumed the output. Also close the OutputStream
            // (our end of tasklist's stdin pipe) — tasklist never reads it, but leaving
            // it open leaks a file descriptor every tick.
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            runCatching { proc.outputStream.close() }
            text.lineSequence()
                .mapNotNull { line ->
                    // CSV format: "chrome.exe","12345","Console","1","50,000 K"
                    line.trim()
                        .removePrefix("\"")
                        .substringBefore("\"")
                        .lowercase()
                        .takeIf { it.isNotBlank() }
                }
                .filter { it in escapeProcesses && shouldBlockInstallerProcess(it) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun getRunningEscapeProcessesFallback(): Set<String> {
        return try {
            ProcessHandle.allProcesses()
                .filter { ph -> ph.pid() != ownPid && ph.info().command().isPresent }
                .toList()
                .mapNotNull { ph ->
                    val cmd = ph.info().command().orElse(null) ?: return@mapNotNull null
                    java.io.File(cmd).name.lowercase().takeIf { it.isNotBlank() }
                }
                .filter { it in escapeProcesses && shouldBlockInstallerProcess(it) }
                .toSet()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Supplemental path-based scan using ProcessHandle.
     * Catches renamed executables — e.g. taskmgr.exe renamed to notmalware.exe —
     * by matching the full executable path against [knownEscapePathSuffixes].
     * Returns the actual running filename (for use in taskkill), not the canonical name.
     */
    private fun getEscapeProcessesByPath(): Set<String> {
        return try {
            ProcessHandle.allProcesses()
                .filter { ph -> ph.pid() != ownPid && ph.info().command().isPresent }
                .toList()
                .mapNotNull { ph ->
                    val rawPath = ph.info().command().orElse(null) ?: return@mapNotNull null
                    val normalised = rawPath.lowercase().replace("/", "\\")
                    val matchesKnownPath = knownEscapePathSuffixes.any { suffix ->
                        normalised.endsWith(suffix)
                    }
                    if (matchesKnownPath) java.io.File(rawPath).name.lowercase() else null
                }
                .filter { it.isNotBlank() && shouldBlockInstallerProcess(it) }
                .toSet()
        } catch (_: Exception) { emptySet() }
    }

    /**
     * Installer processes are part of the direct EXE/MSI protection only.
     * Store/MSIX removal is owned by Windows and must not be interfered with.
     */
    private fun shouldBlockInstallerProcess(processName: String): Boolean {
        if (InstallVariant.isMsix && processName in directInstallerProcesses) return false
        if (InstallVariant.isWindowsDirectInstall && processName in directInstallerProcesses) {
            return !UninstallProtectionService.authorizeUninstallAttempt()
        }
        return true
    }

    // ── Layer 2: Kill (batch) + log ──────────────────────────────────────────

    /**
     * Kills all [found] escape processes in a single `taskkill` invocation.
     * Records escape attempts and persists every 5th hit to the database.
     */
    private fun killAndLog(found: Set<String>) {
        if (found.isEmpty()) return

        // Batch kill: one process spawn for all targets. The helper waits for
        // taskkill briefly so this path has the same reliable completion and
        // child-process cleanup guarantees as normal enforcement.
        killProcessesByName(found)

        // Tally and periodically persist escape attempts
        found.forEach { exe ->
            val count = escapeCounts.merge(exe, 1, Int::plus) ?: 1
            if (count == 1 || count % 5 == 0) {
                runCatching {
                    Database.setSetting("escape_attempt_${exe.removeSuffix(".exe")}", count.toString())
                }
            }
        }
    }

    // ── Layer 3: Pre-emptive firewall block ──────────────────────────────────

    /**
     * Adds outbound-deny firewall rules for every known escape process via
     * [NetworkBlocker]. This is a best-effort call — if admin is not available
     * or the path can't be resolved, it degrades gracefully to kill-only mode.
     */
    private fun applyFirewallLock() {
        if (!isRunningAsAdmin()) return
        escapeProcesses
            .filter { InstallVariant.isWindowsDirectInstall || it !in directInstallerProcesses }
            .filter { it.endsWith(".exe") }
            .forEach { exe -> NetworkBlocker.addRule(exe) }
    }

    private fun removeFirewallLock() {
        escapeProcesses
            .filter { InstallVariant.isWindowsDirectInstall || it !in directInstallerProcesses }
            .filter { it.endsWith(".exe") }
            .forEach { exe -> NetworkBlocker.removeRule(exe) }
    }

    // ── Enforcement loop ─────────────────────────────────────────────────────

    /**
     * Single enforcement tick: detect → kill → (layer 3 is applied once at enable()).
     * Runs every 500 ms — aggressive enough to be effective, not so fast it saturates
     * the scheduler. The old 300 ms cadence with 40+ spawns per tick was far more
     * expensive than this single-scan approach.
     */
    private fun enforceTick() {
        if (KillSwitchService.isActive.value) return
        val byName = getRunningEscapeProcesses()
        val byPath = getEscapeProcessesByPath()
        killAndLog(byName + byPath)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Enable nuclear mode.
     * @param silent When true, suppresses tray notifications and sound. Pass true
     *               when called from FocusLauncherService so kiosk-mode lifecycle
     *               events don't show confusing "Nuclear Mode ON" popups.
     */
    fun enable(silent: Boolean = false) {
        // CAS prevents two simultaneous enable() calls from both passing the guard
        // and launching two monitorJobs (each firing enforceTick() every 500 ms).
        if (!_isActiveAtomic.compareAndSet(false, true)) return

        Database.setSetting("nuclear_mode", "true")
        escapeCounts.clear()

        // Capture the pending cleanup thread reference before launching the IO work.
        // The join and the new firewall-rule application are both done on an IO thread
        // so the caller is never blocked — onKillSwitchDeactivated() runs on the AWT
        // EDT and a 3-second join there would freeze the UI.
        // Safety: without waiting for the prior cleanup to finish, the cleanup thread
        // could race with applyFirewallLock() and remove the rules we're about to add,
        // leaving nuclear mode with zero firewall coverage. Joining inside the IO
        // coroutine preserves this guarantee without touching the EDT.
        val pendingCleanup = synchronized(lifecycleLock) {
            cleanupThread.also { cleanupThread = null }
        }
        scope.launch(Dispatchers.IO) {
            pendingCleanup?.join(3_000)   // join — interrupt won't help netsh waitFor() blocks
            synchronized(firewallLock) {
                // disable() may have won the race while the previous cleanup was
                // finishing. Never apply rules for a mode that is no longer active.
                if (_isActiveAtomic.get()) applyFirewallLock()
            }
        }

        monitorJob = scope.launch {
            while (isActive) {
                enforceTick()
                delay(500)
            }
        }

        if (!silent) {
            val uninstallNotice = if (InstallVariant.isWindowsDirectInstall) {
                " Common EXE/MSI uninstallers are blocked while Nuclear Mode is active."
            } else {
                ""
            }
            SystemTrayManager.showNotification(
                "Nuclear Mode ON",
                "All escape routes are blocked.$uninstallNotice Stay focused.",
                TrayIcon.MessageType.WARNING
            )
            SystemTrayManager.updateTooltip("FocusFlow — NUCLEAR MODE ACTIVE")
            SoundAversion.playNuclearAlert()
        }
    }

    /**
     * Disable nuclear mode.
     * @param silent When true, suppresses tray notifications. Pass true when called
     *               from FocusLauncherService so kiosk-mode lifecycle events don't
     *               show confusing "Nuclear Mode OFF / Normal operation resumed" popups.
     */
    fun disable(silent: Boolean = false) {
        synchronized(lifecycleLock) {
            _isActiveAtomic.set(false)
            monitorJob?.cancel()
            monitorJob = null

            // Snapshot escape counts before handing off to the background thread
            // (ConcurrentHashMap.values.sum() is safe without synchronisation, but
            // the snapshot ensures the background thread sees the same total as the
            // UI notification that will immediately follow).
            val totalAttemptsSnapshot = escapeCounts.values.sum()

            // Move both DB writes into the background cleanup thread so the caller
            // (startBreak() / onKillSwitchActivated() — both on the AWT EDT or Compose
            // UI thread) is never blocked waiting for the DB lock.
            // awaitCleanup() joins this thread before JVM exit, so persistence is safe
            // even when disable() is called from the shutdown sequence.
            val t = Thread({
                synchronized(firewallLock) {
                    Database.setSetting("nuclear_mode", "false")
                    removeFirewallLock()
                    if (totalAttemptsSnapshot > 0) {
                        Database.setSetting("nuclear_last_session_attempts", totalAttemptsSnapshot.toString())
                    }
                }
            }, "FocusFlow-FwCleanup")
            cleanupThread = t
            t.start()
        }

        if (!silent) {
            SystemTrayManager.updateTooltip("FocusFlow — Ready")
            SystemTrayManager.showNotification(
                "Nuclear Mode OFF",
                "Normal operation resumed.",
                TrayIcon.MessageType.INFO
            )
        }
    }

    /**
     * Block until the background firewall-cleanup thread finishes.
     * Call ONLY from the shutdown thread — never from the UI thread.
     * No-op if no cleanup is pending or it already finished.
     */
    fun awaitCleanup(timeoutMs: Long = 5_000) {
        cleanupThread?.join(timeoutMs)
        cleanupThread = null
    }

    /** Total escape attempts recorded since the last [enable] call. */
    fun sessionEscapeAttempts(): Int = escapeCounts.values.sum()

    /** Per-process escape attempt breakdown for the current/last session. */
    fun escapeAttemptBreakdown(): Map<String, Int> = HashMap(escapeCounts)

    fun loadFromDb() {
        if (Database.getSetting("nuclear_mode") == "true") enable()
    }
}
