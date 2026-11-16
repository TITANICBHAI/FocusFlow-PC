package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.services.SoundAversion
import com.focusflow.services.SystemTrayManager
import com.focusflow.services.UninstallProtectionService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.TrayIcon
import java.util.concurrent.ConcurrentHashMap

/**
 * NuclearMode — three-layer escape-route enforcement
 *
 * Layer 1 — Detect (shared snapshot):
 *   A short-lived shared process snapshot supplies names and paths to both
 *   escape checks. No tasklist process is spawned during an enforcement tick.
 *
 * Layer 2 — Kill (batch, by process id):
 *   If any escape processes are found, ONE `taskkill /F /PID n /PID m …`
 *   call terminates exactly those processes. It is by id, never by image name, so a
 *   person's PowerShell is ended while FocusFlow's own helper PowerShell is not
 *   (see EnforcementTrust / EscapeEnforcement). Escape attempts are counted
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

    private const val HOLD_MODEL_KEY = "nuclear_hold_model"
    private const val HOLD_MODEL_VERSION = "2"

    internal enum class PersistedState {
        ENABLED,
        DISABLED,
        UNREADABLE
    }

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
     * lowercased full path returned by the shared process snapshot.
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
    @Volatile private var settingsRetryJob: Job? = null
    @Volatile private var settingsReadFailure: Throwable? = null

    /** Per-process escape attempt counter — flushed to DB every 5 hits. */
    private val escapeCounts = ConcurrentHashMap<String, Int>()

    /**
     * Who keeps Nuclear Mode on (see [NuclearHolds]). Enforcement runs while either the
     * person (manual) or a Focus Launcher session holds it; only the manual hold is
     * remembered across restarts.
     */
    private val holds = NuclearHolds()
    private val _isActiveState = MutableStateFlow(false)
    private val _manualState = MutableStateFlow(false)
    private val _sessionState = MutableStateFlow(false)

    /** Nuclear enforcement is running, for any reason. */
    val activeState: StateFlow<Boolean> = _isActiveState.asStateFlow()
    val isActive: Boolean get() = _isActiveState.value

    /** The person turned Nuclear Mode on; it survives restarts and needs the Nuclear PIN to end. */
    val manualActiveState: StateFlow<Boolean> = _manualState.asStateFlow()
    val manualActive: Boolean get() = _manualState.value

    /** A Focus Launcher session is holding Nuclear Mode on; it ends with the session. */
    val sessionHeldState: StateFlow<Boolean> = _sessionState.asStateFlow()
    val sessionHeld: Boolean get() = _sessionState.value

    /** Number of processes in the escape-route list (shown on the Nuclear Mode screen). */
    val escapeProcessCount: Int get() = escapeProcesses.size

    /** Names of all monitored escape processes (shown on the Nuclear Mode screen). */
    val escapeProcessNames: Set<String> get() = escapeProcesses

    // Reference to the background firewall-cleanup thread so awaitCleanup() can join it.
    @Volatile private var cleanupThread: Thread? = null
    @Volatile private var lastSnapshotFailureLogMs = 0L
    private val snapshotFailureLogLock = Any()
    // Serialises firewall apply/remove operations. Without this, a rapid
    // disable() -> enable() can let the old cleanup remove rules after the
    // new enable has applied them.
    private val firewallLock = Any()
    // Keeps cleanup-thread handoff atomic with the active-state transition.
    // Otherwise enable() can miss a cleanup thread created by disable().
    private val lifecycleLock = Any()

    // ── Layer 1: Detect ─────────────────────────────────────────────────────

    // Detection lives in EscapeTargetSelector (by name, and by known system path so a renamed copy
    // of taskmgr.exe is still caught). The lists below stay here as the single source of truth.

    /** The known system paths of escape tools; exposed so tests exercise the real list. */
    internal val escapePathSuffixes: Set<String> get() = knownEscapePathSuffixes

    /** Shared name/path test used by the launcher policy and Nuclear Mode. */
    internal fun isEscapeTool(processName: String, executablePath: String? = null): Boolean {
        val name = processName.trim()
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .lowercase()
        val nameMatch = name in escapeProcesses && shouldBlockInstallerProcess(name)
        val pathMatch = executablePath
            ?.lowercase()
            ?.replace("/", "\\")
            ?.let { path ->
                knownEscapePathSuffixes.any(path::endsWith) && shouldBlockInstallerProcess(name)
            } == true
        return nameMatch || pathMatch
    }

    /**
     * Installer processes are part of the direct EXE/MSI protection only.
     * Store/MSIX removal is owned by Windows and must not be interfered with.
     */
    private fun shouldBlockInstallerProcess(processName: String): Boolean {
        if (InstallVariant.isMsix && processName in directInstallerProcesses) return false
        if (InstallVariant.isWindowsDirectInstall && processName in directInstallerProcesses) {
            // Decide without any dialog so the caller kills a denied installer at
            // once. The old code showed a Standalone Block message or a PIN prompt
            // first, which stalled this scan and left the installer running (and
            // able to finish) behind that dialog. The shared guard prompt runs
            // after the kill.
            return when (UninstallProtectionService.uninstallAttemptVerdict()) {
                UninstallProtectionService.UninstallVerdict.Allow -> false
                is UninstallProtectionService.UninstallVerdict.Deny -> {
                    UninstallGuard.reportBlockedAttempt()
                    true
                }
            }
        }
        return true
    }

    // ── Layer 2: Kill (batch) + log ──────────────────────────────────────────

    /**
     * Ends the chosen escape processes by process id in one batched `taskkill`, then records the
     * attempts and persists every 5th hit to the database. Never kills by image name: that would
     * also end FocusFlow's own PowerShell (see [EscapeEnforcement]).
     */
    private fun killAndLog(
        targets: Map<Long, String>,
        snapshot: List<com.focusflow.services.ProcessSnapshotEntry>
    ) {
        if (targets.isEmpty()) return

        val attempted = EscapeEnforcement.kill(
            targets = targets,
            snapshot = snapshot,
            isAlive = com.focusflow.services.ProcessSnapshot::isAlive,
            killBatch = ::killProcessesByPid,
            killOne = { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        )

        logEscapeAttempts(attempted.values.toSet())
    }

    private fun logEscapeAttempts(found: Set<String>) {
        // Tally and periodically persist escape attempts
        found.forEach { exe ->
            val count = escapeCounts.merge(exe, 1, Int::plus) ?: 1
            if (count == 1 || count % 5 == 0) {
                persistSetting(
                    "escape_attempt_${exe.removeSuffix(".exe")}",
                    count.toString(),
                    "escape-attempt counter for $exe"
                )
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
        if (!ElevationManager.isElevated) return
        // One batched PowerShell run instead of ~40 sequential ones. The old per-program
        // loop kept the firewall service and CPU busy for minutes after Nuclear Mode
        // was turned on, which starved the process kills.
        NetworkBlocker.addRules(
            escapeProcesses
                .filter { InstallVariant.isWindowsDirectInstall || it !in directInstallerProcesses }
                .filter { it.endsWith(".exe") },
            FirewallOwner.NUCLEAR
        )
    }

    private fun removeFirewallLock() {
        NetworkBlocker.removeRules(
            escapeProcesses
                .filter { InstallVariant.isWindowsDirectInstall || it !in directInstallerProcesses }
                .filter { it.endsWith(".exe") },
            FirewallOwner.NUCLEAR
        )
    }

    // ── Enforcement loop ─────────────────────────────────────────────────────

    /**
     * Single enforcement tick: detect → kill → (layer 3 is applied once at enable()).
     * Runs every 500 ms — aggressive enough to be effective, not so fast it saturates
     * the scheduler. The old 300 ms cadence with 40+ spawns per tick was far more
     * expensive than this single-scan approach.
     */
    private fun enforceTick() {
        val startedAtNanos = System.nanoTime()
        try {
            if (KillSwitchService.isActive.value) return
            val snapshot = try {
                com.focusflow.services.ProcessSnapshot.snapshotProcesses()
            } catch (failure: Exception) {
                val now = System.currentTimeMillis()
                synchronized(snapshotFailureLogLock) {
                    if (now - lastSnapshotFailureLogMs >= 30_000L) {
                        lastSnapshotFailureLogMs = now
                        EnforcementLog.warn(
                            "NuclearMode",
                            "Shared process snapshot failed; escape enforcement skipped this tick",
                            failure
                        )
                    }
                }
                return
            }
            // FocusFlow's own helpers (its firewall PowerShell, its taskkill, the Watchdog's
            // scheduled-task run) are told apart from a person's tools by identity, never by
            // image name; see EnforcementTrust. Decided once per process per sweep.
            val trusted = EnforcementTrust.helperCheck(snapshot)
            val launcherOwnUtilityExemption =
                ProcessMonitor.launcherAllowedProcesses.isNotEmpty() &&
                    com.focusflow.services.LauncherChainAllowance.isEnabled
            val systemRoot = System.getenv("SystemRoot") ?: System.getenv("WINDIR")

            val launcherBlocks: ((com.focusflow.services.ProcessSnapshotEntry) -> Boolean)? =
                if (launcherOwnUtilityExemption) {
                    { process ->
                        com.focusflow.services.LauncherKioskPolicy.decide(
                            process = process,
                            snapshot = snapshot,
                            allowedProcessNames = ProcessMonitor.launcherAllowedProcesses,
                            allowedExecutablePaths = ProcessMonitor.launcherAllowedAppExecutablePaths,
                            safeProcessNames = ProcessMonitor.launcherSafeProcesses,
                            ownPid = ownPid,
                            systemRoot = systemRoot,
                            chainAllowanceEnabled = true,
                            resolvePath = com.focusflow.services.ProcessSnapshot::resolveExecutablePath,
                            isTrustedHelper = trusted
                        ) == com.focusflow.services.LauncherKioskPolicy.Decision.BLOCK
                    }
                } else {
                    null
                }

            val targets = EscapeEnforcement.plan(
                snapshot = snapshot,
                ownPid = ownPid,
                escapeNames = escapeProcesses,
                escapePathSuffixes = knownEscapePathSuffixes,
                resolvePath = com.focusflow.services.ProcessSnapshot::resolveExecutablePath,
                isNameBlockable = ::shouldBlockInstallerProcess,
                isTrusted = trusted,
                launcherBlocks = launcherBlocks
            )
            killAndLog(targets, snapshot)
        } finally {
            val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            if (durationMs > 50L) {
                EnforcementLog.info("Launcher", "nuclearSweep durationMs=$durationMs")
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Publishes a hold change to the observable flags. Call only inside [lifecycleLock]. */
    private fun publish(transition: NuclearHolds.Transition): NuclearHolds.Transition {
        _manualState.value = transition.after.manual
        _sessionState.value = transition.after.session
        _isActiveState.value = transition.after.active
        return transition
    }

    /**
     * Turn Nuclear Mode on as the person's own setting (the hold that is remembered and
     * guarded by the Nuclear PIN).
     * @param silent When true, suppresses tray notifications and sound.
     */
    fun enable(silent: Boolean = false) {
        val transition = synchronized(lifecycleLock) { publish(holds.setManual(true)) }
        if (!transition.manualChanged) return

        // Remembered before anything else, on this thread, so a crash right after cannot
        // lose a protection that was just switched on.
        persistManualHold()
        if (transition.started) startEnforcement()

        if (!silent) {
            if (transition.started) {
                notifyOn()
            } else {
                SystemTrayManager.showNotification(
                    "Nuclear Mode kept on",
                    "Nuclear Mode will stay on after the Focus Launcher session ends.",
                    TrayIcon.MessageType.INFO
                )
            }
        }
    }

    /**
     * Turn off the person's own Nuclear Mode setting. If a Focus Launcher session is still
     * holding Nuclear Mode, enforcement continues until the session ends.
     * @param silent When true, suppresses tray notifications.
     */
    fun disable(silent: Boolean = false) {
        com.focusflow.services.LooseningCoolOff.cancel(com.focusflow.services.LooseningCoolOff.KEY_NUCLEAR_OFF)
        val transition = synchronized(lifecycleLock) { publish(holds.setManual(false)) }

        // The remembered value is written off the caller's thread (the caller may be the UI
        // thread and the database may be busy), always as the *latest* hold state.
        schedulePersistManualHold()

        // Cleanup is idempotent and safe to repeat, so a shutdown-time disable() still
        // removes firewall rules even if nothing was marked active in memory.
        if (!transition.after.active) stopEnforcement()

        if (!silent && transition.manualChanged) {
            if (transition.after.active) {
                SystemTrayManager.showNotification(
                    "Nuclear Mode setting off",
                    "A Focus Launcher session is still using Nuclear Mode until it ends.",
                    TrayIcon.MessageType.INFO
                )
            } else {
                SystemTrayManager.updateTooltip("FocusFlow — Ready")
                SystemTrayManager.showNotification(
                    "Nuclear Mode OFF",
                    "Normal operation resumed.",
                    TrayIcon.MessageType.INFO
                )
            }
        }
    }

    /**
     * A Focus Launcher session needs Nuclear Mode. Not remembered by itself: the launcher's
     * own session record brings it back after an interruption, and
     * [releaseSessionHold] ends it, without touching a manual setting.
     */
    fun acquireSessionHold() {
        val transition = synchronized(lifecycleLock) { publish(holds.setSession(true)) }
        if (transition.started) startEnforcement()
    }

    /** The Focus Launcher session (or break) no longer needs Nuclear Mode. Idempotent. */
    fun releaseSessionHold() {
        val transition = synchronized(lifecycleLock) { publish(holds.setSession(false)) }
        if (transition.stopped) stopEnforcement()
    }

    /**
     * Test seam: when false, hold changes are tracked but no monitor, firewall rule or
     * process kill is started, so tests can never end a real process on the machine
     * running them.
     */
    @Volatile internal var enforcementRunsForTest = true

    /** Start the monitor, firewall rules and hardening. Called once per off→on change. */
    private fun startEnforcement() {
        if (!enforcementRunsForTest) return
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
                // The holds may have been released while the previous cleanup was
                // finishing. Never apply rules for a mode that is no longer active.
                if (_isActiveState.value) applyFirewallLock()
            }
            // Optional (hidden setting): make "end task" / taskkill on FocusFlow fail while Nuclear
            // Mode is on. Re-check afterwards in case it was released meanwhile.
            if (_isActiveState.value) {
                ProcessHardening.enableIfConfigured()
                if (!_isActiveState.value) ProcessHardening.disable()
            }
        }

        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                enforceTick()
                delay(500)
            }
        }
    }

    /** Stop the monitor and remove firewall rules and hardening. Safe to call repeatedly. */
    private fun stopEnforcement() {
        if (!enforcementRunsForTest) return
        synchronized(lifecycleLock) {
            monitorJob?.cancel()
            monitorJob = null

            // Snapshot escape counts before handing off to the background thread
            // (ConcurrentHashMap.values.sum() is safe without synchronisation, but
            // the snapshot ensures the background thread sees the same total as the
            // UI notification that will immediately follow).
            val totalAttemptsSnapshot = escapeCounts.values.sum()

            // Firewall removal and bookkeeping run on a background thread so the caller
            // (startBreak() / onKillSwitchActivated() — both on the AWT EDT or Compose
            // UI thread) is never blocked. awaitCleanup() joins this thread before JVM
            // exit, so the work completes even when this runs from the shutdown sequence.
            val t = Thread({
                // First, so a locked database can never keep FocusFlow un-killable.
                ProcessHardening.disable()
                synchronized(firewallLock) {
                    // A hold taken again meanwhile owns the rules now.
                    if (!_isActiveState.value) removeFirewallLock()
                    if (totalAttemptsSnapshot > 0) {
                        persistSetting(
                            "nuclear_last_session_attempts",
                            totalAttemptsSnapshot.toString(),
                            "last-session escape attempts"
                        )
                    }
                }
            }, "FocusFlow-FwCleanup")
            cleanupThread = t
            t.start()
        }
    }

    private fun notifyOn() {
        val uninstallNotice = if (InstallVariant.isWindowsDirectInstall) {
            " Common EXE/MSI uninstallers are blocked while Nuclear Mode is active."
        } else {
            ""
        }
        SystemTrayManager.showNotification(
            "Nuclear Mode ON",
            if (ElevationManager.isElevated || !InstallVariant.isWindows) {
                "All escape routes are blocked.$uninstallNotice Stay focused."
            } else {
                "Process blocking is on, but FocusFlow is not running as administrator: the firewall lock is off and tools started as administrator may survive. Use Restart as admin.$uninstallNotice"
            },
            TrayIcon.MessageType.WARNING
        )
        SystemTrayManager.updateTooltip("FocusFlow — NUCLEAR MODE ACTIVE")
        SoundAversion.playNuclearAlert()
    }

    /**
     * Stop this process's monitor for an administrator handoff without clearing
     * the persisted manual setting or firewall rules. The elevated child resumes the
     * manual hold from the database; a Focus Launcher session is not carried over (it
     * cannot be active during a handoff) and a restored one re-acquires its own hold.
     */
    fun suspendForElevationHandoff() {
        val totalAttemptsSnapshot = synchronized(lifecycleLock) {
            if (!holds.snapshot().active) return
            persistFrozen = true
            publish(holds.clearAll())
            monitorJob?.cancel()
            monitorJob = null
            escapeCounts.values.sum()
        }
        ProcessHardening.disable()
        if (totalAttemptsSnapshot > 0) {
            persistSetting(
                "nuclear_last_session_attempts",
                totalAttemptsSnapshot.toString(),
                "last-session escape attempts"
            )
        }
    }

    /**
     * Block until the background firewall-cleanup thread and any pending write of the
     * remembered setting finish. Call ONLY from the shutdown thread — never from the UI thread.
     */
    fun awaitCleanup(timeoutMs: Long = 5_000) {
        cleanupThread?.join(timeoutMs)
        cleanupThread = null
        runCatching { pendingPersist?.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    /** Total escape attempts recorded since the last [enable] call. */
    fun sessionEscapeAttempts(): Int = escapeCounts.values.sum()

    /** Per-process escape attempt breakdown for the current/last session. */
    fun escapeAttemptBreakdown(): Map<String, Int> = HashMap(escapeCounts)

    fun loadFromDb() {
        if (!loadPersistedState()) {
            EnforcementLog.warn(
                "NuclearMode",
                "Nuclear Mode setting is unreadable; keeping enforcement active and retrying",
                settingsReadFailure
            )
            applyStoredManualHold(true)
            scheduleSettingsRetry()
        }
    }

    internal fun persistedState(read: Database.SettingRead): PersistedState =
        when (read) {
            is Database.SettingRead.Present ->
                when (read.value) {
                    "true" -> PersistedState.ENABLED
                    "false" -> PersistedState.DISABLED
                    else -> PersistedState.UNREADABLE
                }
            Database.SettingRead.Absent -> PersistedState.DISABLED
            is Database.SettingRead.Unreadable -> PersistedState.UNREADABLE
        }

    /**
     * Sets the manual hold from what is stored, without writing it back and without
     * touching a Focus Launcher session's hold.
     */
    private fun applyStoredManualHold(held: Boolean) {
        val transition = synchronized(lifecycleLock) {
            if (persistFrozen) return
            publish(holds.setManual(held))
        }
        if (transition.started) startEnforcement()
        if (transition.stopped) stopEnforcement()
    }

    private fun loadPersistedState(): Boolean {
        val read = Database.readSetting("nuclear_mode")
        settingsReadFailure = (read as? Database.SettingRead.Unreadable)?.cause
        return when (persistedState(read)) {
            PersistedState.ENABLED -> {
                settingsRetryJob?.cancel()
                settingsRetryJob = null
                applyStoredManualHold(true)
                announceLegacyFlagOnce(wasEnabled = true)
                true
            }
            PersistedState.DISABLED -> {
                settingsRetryJob?.cancel()
                settingsRetryJob = null
                applyStoredManualHold(false)
                announceLegacyFlagOnce(wasEnabled = false)
                true
            }
            PersistedState.UNREADABLE -> false
        }
    }

    /**
     * Before the two-hold model, Focus Launcher wrote its own sessions into the same stored
     * flag, so an "on" left over from an older version may not have been chosen by the
     * person. It is kept (never silently weakened) as a manual setting; this tells the
     * person once so they can turn it off themselves.
     */
    private fun announceLegacyFlagOnce(wasEnabled: Boolean) {
        val marker = (Database.readSetting(HOLD_MODEL_KEY) as? Database.SettingRead.Present)?.value
        if (marker == HOLD_MODEL_VERSION) return
        persistSetting(HOLD_MODEL_KEY, HOLD_MODEL_VERSION, "Nuclear Mode hold-model marker")
        if (!wasEnabled) return
        scope.launch {
            delay(8_000L)
            SystemTrayManager.showNotification(
                "Nuclear Mode is ON",
                "Nuclear Mode was left on and is kept as your own setting. " +
                    "Turn it off in Nuclear Mode if you did not choose it (it asks for the Nuclear PIN if you set one).",
                TrayIcon.MessageType.WARNING
            )
        }
    }

    private fun scheduleSettingsRetry() {
        if (settingsRetryJob?.isActive == true) return
        settingsRetryJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                if (loadPersistedState()) {
                    settingsRetryJob = null
                    return@launch
                }
            }
        }
    }

    // ── Remembering the manual hold ───────────────────────────────────────────

    private val persistLock = Any()
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "FocusFlow-NuclearPersist").apply { isDaemon = true }
    }
    @Volatile private var pendingPersist: java.util.concurrent.Future<*>? = null
    @Volatile private var persistFrozen = false

    /**
     * Writes the manual hold as it is *now*. Every writer goes through this one lock and
     * reads the latest state when it runs, so a late write can never put back an older value.
     */
    private fun persistManualHold() {
        synchronized(persistLock) {
            if (persistFrozen) return
            val value = holds.snapshot().manual
            repeat(3) { attempt ->
                when (val result = Database.writeSetting("nuclear_mode", value.toString())) {
                    Database.SettingWrite.Ok -> return
                    is Database.SettingWrite.Failed -> {
                        if (attempt == 2) {
                            EnforcementLog.warn("NuclearMode", "Could not persist Nuclear Mode state", result.cause)
                        } else {
                            try { Thread.sleep(250L) } catch (_: InterruptedException) { return }
                        }
                    }
                }
            }
        }
    }

    private fun schedulePersistManualHold() {
        pendingPersist = persistExecutor.submit { persistManualHold() }
    }

    private fun persistSetting(key: String, value: String, description: String) {
        when (val result = Database.writeSetting(key, value)) {
            Database.SettingWrite.Ok -> Unit
            is Database.SettingWrite.Failed ->
                EnforcementLog.warn("NuclearMode", "Could not persist $description", result.cause)
        }
    }

    internal fun resetForTest() {
        settingsRetryJob?.cancel()
        settingsRetryJob = null
        monitorJob?.cancel()
        monitorJob = null
        synchronized(lifecycleLock) { publish(holds.clearAll()) }
        enforcementRunsForTest = true
        persistFrozen = false
        runCatching { pendingPersist?.get(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
        pendingPersist = null
        escapeCounts.clear()
        settingsReadFailure = null
        cleanupThread?.join(1_000L)
        cleanupThread = null
    }
}
