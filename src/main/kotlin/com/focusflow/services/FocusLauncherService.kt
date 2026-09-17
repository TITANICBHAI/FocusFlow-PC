package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.FocusLauncherSession
import com.focusflow.data.models.FocusLauncherSessionApp
import com.focusflow.enforcement.GlobalKeyboardHook
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.RegistryLockdown
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.User32Extra
import com.focusflow.enforcement.isWindows
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.TrayIcon

data class FocusLauncherApp(
    val processName: String,
    val displayName: String,
    val exePath: String? = null
)

object FocusLauncherService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Hard safety net: if the JVM exits for ANY reason (crash, OOM, SIGKILL on Win is
        // not catchable, but SIGTERM / normal exit is) restore the taskbar immediately.
        // This runs even when our regular cleanup code never executes.
        Runtime.getRuntime().addShutdownHook(Thread {
            try { showTaskbar() } catch (_: Throwable) {}
        })
    }

    private val _isActive             = MutableStateFlow(false)
    val isActive: StateFlow<Boolean>  = _isActive

    private val _isHardLocked              = MutableStateFlow(false)
    val isHardLocked: StateFlow<Boolean>   = _isHardLocked

    private val _sessionApps                       = MutableStateFlow<List<FocusLauncherApp>>(emptyList())
    val sessionApps: StateFlow<List<FocusLauncherApp>> = _sessionApps

    private val _breakActive              = MutableStateFlow(false)
    val breakActive: StateFlow<Boolean>   = _breakActive

    private val _breakRemainingSeconds      = MutableStateFlow(0)
    val breakRemainingSeconds: StateFlow<Int> = _breakRemainingSeconds

    private val _sessionEndMs           = MutableStateFlow(0L)
    val sessionEndMs: StateFlow<Long>   = _sessionEndMs

    private val _sessionStartMs         = MutableStateFlow(0L)
    val sessionStartMs: StateFlow<Long> = _sessionStartMs

    /** Seconds of break time accumulated this session — subtracted from elapsed display.
     *  AtomicLong so addAndGet() in endBreak() and set(0) in enter()/exit() are race-free. */
    private val breakSecondsAccumulated = java.util.concurrent.atomic.AtomicLong(0L)

    /** Whether the user can still take a break today. Kept as a StateFlow so the
     *  UI can observe it without doing a synchronous DB read on the main thread. */
    private val _canTakeBreak           = MutableStateFlow(true)
    val canTakeBreak: StateFlow<Boolean> = _canTakeBreak

    /** True when the launcher window should be topmost over non-allowed apps. */
    private val _overlayVisible            = MutableStateFlow(true)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible

    /** Plain-text PIN exposed only long enough for the setup UI to display it. */
    private val _sessionPin           = MutableStateFlow("")
    val sessionPin: StateFlow<String> = _sessionPin

    /** Configured break count; -1 means unlimited. */
    private val _breaksTotal        = MutableStateFlow(1)
    val breaksTotal: StateFlow<Int> = _breaksTotal

    /** Number of breaks already taken in this session. */
    private val _breaksUsed        = MutableStateFlow(0)
    val breaksUsed: StateFlow<Int> = _breaksUsed

    @Volatile private var breakJob:        Job? = null
    @Volatile private var sessionTimerJob: Job? = null
    @Volatile private var taskbarGuardJob: Job? = null
    @Volatile private var breakDurationSeconds: Int = 5 * 60
    @Volatile private var breakEndMs: Long = 0L
    @Volatile private var sessionPinHash: String = ""

    private const val LAUNCHER_PIN_KEY  = "launcher_session_pin_hash"
    private const val BREAK_SECONDS_KEY = "launcher_break_duration_sec"
    private const val CRASH_GUARD_KEY = "launcher_crash_guard"
    private const val HARD_LOCK_KEY   = "launcher_hard_locked"

    // ── Public API ─────────────────────────────────────────────────────────

    private fun startTaskbarGuard() {
        taskbarGuardJob?.cancel()
        taskbarGuardJob = scope.launch {
            while (_isActive.value && !_breakActive.value) {
                hideTaskbar()
                delay(500)
            }
        }
    }

    private fun stopTaskbarGuard() {
        taskbarGuardJob?.cancel()
        taskbarGuardJob = null
    }

    /** Generate and persist the one-time PIN for the next launcher session. */
    fun preparePin(): String {
        val plain = PinPolicy.generate()
        sessionPinHash = sha256(plain)
        Database.setSetting(LAUNCHER_PIN_KEY, sessionPinHash)
        _sessionPin.value = plain
        return plain
    }

    /**
     * Verify a session PIN.
     *
     * A launcher session always creates its PIN before entering kiosk mode.
     * Fail closed if the stored hash is missing or blank so a database read
     * failure, stale session, or dismissed setup cannot become an exit bypass.
     */
    fun verifyPin(raw: String): Boolean {
        val stored = Database.getSetting(LAUNCHER_PIN_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return raw.isNotBlank() && sha256(raw) == stored
    }

    /**
     * Update launcher window Z-order after a foreground process change.
     * Allowed apps are usable below the launcher; all other foreground apps
     * bring the launcher back to the top.
     */
    fun onForegroundChanged(processName: String) {
        if (!_isActive.value || _breakActive.value) return
        val allowed = _sessionApps.value.map { it.processName.lowercase() }.toSet()
        _overlayVisible.value = processName.lowercase() !in allowed
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun enter(
        apps: List<FocusLauncherApp>,
        durationMinutes: Int?,
        breaksAllowed: Int = 1,
        breakSeconds: Int = 5 * 60
    ) {
        // Re-entrancy guard: if a session is already running, ignore the call.
        // The UI disable the Enter button while active, but this prevents any
        // race from triggering a double-enter that would orphan timer jobs.
        if (!_isActive.compareAndSet(false, true)) return

        _overlayVisible.value     = true
        _breaksTotal.value        = breaksAllowed
        _breaksUsed.value         = 0
        breakDurationSeconds      = breakSeconds
        _sessionApps.value        = apps
        _sessionStartMs.value     = System.currentTimeMillis()
        breakSecondsAccumulated.set(0L)
        _sessionEndMs.value       = if (durationMinutes != null)
            System.currentTimeMillis() + durationMinutes * 60_000L
        else 0L
        _canTakeBreak.value       = breaksAllowed != 0
        breakEndMs = 0L

        Database.setSetting(CRASH_GUARD_KEY, "true")
        if (sessionPinHash.isBlank()) {
            sessionPinHash = Database.getSetting(LAUNCHER_PIN_KEY).orEmpty()
        }
        if (sessionPinHash.isBlank()) {
            _isActive.value = false
            return
        }
        persistSession()

        val allowedSet = apps.map { it.processName.lowercase() }.toSet()
        ProcessMonitor.launcherAllowedProcesses = allowedSet
        ProcessMonitor.onLauncherForegroundChanged = ::onForegroundChanged

        // silent = true: NuclearMode is an implementation detail of kiosk mode;
        // the user should not see "Nuclear Mode ON" when entering Focus Launcher.
        NuclearMode.enable(silent = true)

        // Install the low-level keyboard hook to suppress system shortcuts
        // (Win key, Alt+Tab, Alt+F4, Ctrl+Esc, Alt+Esc).  Must come AFTER
        // NuclearMode so both enforcement layers are live at the same moment.
        GlobalKeyboardHook.enable()

        // Apply registry policy lockdown: disables Task Manager (HKCU), removes
        // Sign Out from Start/CAD screen (HKCU), and hides Fast User Switching
        // (HKLM — silently skipped when not elevated).
        RegistryLockdown.enable()

        startTaskbarGuard()

        if (durationMinutes != null) startSessionTimer()

        SystemTrayManager.showNotification(
            "Focus Launcher Active",
            "Kiosk mode enabled. ${apps.size} app(s) available.",
            TrayIcon.MessageType.INFO
        )
    }

    fun exit() {
        // Re-entrancy guard: if the session is already inactive, nothing to clean up.
        // This prevents the session-timer coroutine and a concurrent UI click from
        // both running the full teardown sequence simultaneously (double showTaskbar,
        // double NuclearMode.disable, redundant DB writes, etc.).
        if (!_isActive.compareAndSet(expect = true, update = false)) return

        runCatching { Database.clearFocusLauncherSession() }
        _isHardLocked.value       = false
        _breakActive.value        = false
        _overlayVisible.value     = true
        _breaksTotal.value        = 1
        _breaksUsed.value         = 0
        _sessionPin.value         = ""
        _sessionApps.value        = emptyList()
        _sessionEndMs.value       = 0L
        _sessionStartMs.value     = 0L
        breakSecondsAccumulated.set(0L)
        breakEndMs = 0L
        sessionPinHash = ""

        stopTaskbarGuard()
        ProcessMonitor.onLauncherForegroundChanged = null

        breakJob?.cancel()
        sessionTimerJob?.cancel()
        breakJob        = null
        sessionTimerJob = null

        Database.setSetting(CRASH_GUARD_KEY, "false")
        Database.setSetting(HARD_LOCK_KEY, "false")
        Database.setSetting(LAUNCHER_PIN_KEY, "")

        ProcessMonitor.launcherAllowedProcesses = emptySet()

        // silent = true: suppress the "Nuclear Mode OFF / Normal operation resumed"
        // tray notification — the user exited kiosk mode, not nuclear mode explicitly.
        if (NuclearMode.isActive) NuclearMode.disable(silent = true)

        // Remove the keyboard hook, release the foreground lock, and restore
        // all registry policies that were applied at session start.
        GlobalKeyboardHook.disable()
        RegistryLockdown.disable()

        showTaskbar()

        SystemTrayManager.updateTooltip("FocusFlow — Ready")
    }

    fun toggleHardLock() {
        // compareAndSet loop — atomic read-flip-write with no lost updates under concurrency.
        var prev: Boolean
        do { prev = _isHardLocked.value } while (!_isHardLocked.compareAndSet(prev, !prev))
        val newValue = !prev
        Database.setSetting(HARD_LOCK_KEY, newValue.toString())
        if (_isActive.value) persistSession()

    }

    /**
     * Start a 5-minute break — call this only after PIN has been verified by the UI.
     * Restores the taskbar, clears the launcher allowed-list (so normal Windows is
     * accessible), and starts a countdown that re-engages the launcher automatically.
     */
    fun startBreak() {
        if (!_isActive.value) return       // no active session — nothing to break from
        if (_isHardLocked.value) return
        if (!_canTakeBreak.value) return
        // compareAndSet prevents a rapid double-click from launching two break countdowns
        // and calling NuclearMode.disable() twice. Only one caller proceeds.
        if (!_breakActive.compareAndSet(false, true)) return
        stopTaskbarGuard()
        showTaskbar()
        _overlayVisible.value = false
        _breakRemainingSeconds.value = breakDurationSeconds

        // Pause the session countdown while the break runs.
        // Do NOT extend sessionEndMs here — we extend it in endBreak() by the
        // actual seconds used, so an early "End Break" doesn't gift free session time.
        sessionTimerJob?.cancel()
        sessionTimerJob = null
        _breaksUsed.value += 1
        _canTakeBreak.value = _breaksTotal.value == -1 ||
            _breaksUsed.value < _breaksTotal.value
        breakEndMs = System.currentTimeMillis() + breakDurationSeconds * 1_000L
        persistSession()

        ProcessMonitor.launcherAllowedProcesses = emptySet()
        // silent = true: suppress "Nuclear Mode OFF" notification during a focus break —
        // the user paused kiosk mode temporarily, not nuclear mode explicitly.
        if (NuclearMode.isActive) NuclearMode.disable(silent = true)

        // Lift keyboard suppression, foreground lock, and registry policies during
        // the break so the user can freely interact with their desktop.
        GlobalKeyboardHook.disable()
        RegistryLockdown.disable()

        breakJob = scope.launch {
            while (_breakActive.value) {
                delay(500)
                val remaining = maxOf(0L, (breakEndMs - System.currentTimeMillis()) / 1_000L)
                _breakRemainingSeconds.value = remaining.toInt()
                if (remaining == 0L) break
            }
            if (_breakActive.value) endBreak()
        }

        SystemTrayManager.showNotification(
            "5-Minute Break",
            "Focus Launcher will re-engage in 5 minutes.",
            TrayIcon.MessageType.INFO
        )
    }

    fun endBreak() {
        // compareAndSet prevents the countdown coroutine (reaching line 206) and a direct
        // UI call from both passing — which would double breakSecondsAccumulated and extend
        // the session end time by 2× the actual break duration.
        if (!_breakActive.compareAndSet(true, false)) return
        // Accumulate how many seconds the break actually ran (configured duration minus remaining)
        val breakUsed = (breakDurationSeconds - _breakRemainingSeconds.value).toLong()
        breakSecondsAccumulated.addAndGet(breakUsed)

        // Extend the session end time by exactly how long the break ran.
        // Doing it here (not in startBreak) ensures early-ended breaks don't
        // grant unearned session time.
        if (_sessionEndMs.value > 0L) {
            _sessionEndMs.value += breakUsed * 1_000L
        }

        breakJob?.cancel()
        breakJob = null
        _breakRemainingSeconds.value = 0
        breakEndMs = 0L
        persistSession()
        // NOTE: _breakActive is already false — compareAndSet(true, false) at the top set it.

        if (_isActive.value) {
            val allowedSet = _sessionApps.value.map { it.processName.lowercase() }.toSet()
            ProcessMonitor.launcherAllowedProcesses = allowedSet
            // silent = true: suppress "Nuclear Mode ON" notification when kiosk
            // automatically re-engages after a break — it would be jarring/confusing.
            NuclearMode.enable(silent = true)

            // Re-install keyboard hook, foreground lock, and registry policies.
            GlobalKeyboardHook.enable()
            RegistryLockdown.enable()

            startTaskbarGuard()
            _overlayVisible.value = true
            // Resume the session countdown timer now that the break is over
            if (_sessionEndMs.value > 0L) startSessionTimer()
            SystemTrayManager.showNotification(
                "Focus Launcher Resumed",
                "Kiosk mode re-engaged. Stay focused.",
                TrayIcon.MessageType.WARNING
            )
        }
    }

    // ── KillSwitch integration ──────────────────────────────────────────────

    /**
     * Called by KillSwitchService when the emergency break activates.
     * If the launcher is running, temporarily restores the taskbar and lifts
     * enforcement so the user can access their desktop during the break window.
     * The kiosk overlay remains visible so they know they're still in a session.
     */
    fun onKillSwitchActivated() {
        if (!_isActive.value || _breakActive.value) return
        ProcessMonitor.launcherAllowedProcesses = emptySet()
        // silent = true: suppress "Nuclear Mode OFF" — kill switch has its own notification.
        if (NuclearMode.isActive) NuclearMode.disable(silent = true)
        GlobalKeyboardHook.disable()
        RegistryLockdown.disable()
        showTaskbar()
        _overlayVisible.value = false
    }

    /**
     * Called by KillSwitchService when the emergency break ends (manually or expired).
     * Re-engages all kiosk enforcement if the launcher session is still active.
     */
    fun onKillSwitchDeactivated() {
        if (!_isActive.value || _breakActive.value) return
        val allowedSet = _sessionApps.value.map { it.processName.lowercase() }.toSet()
        ProcessMonitor.launcherAllowedProcesses = allowedSet
        // Re-check: exit() uses compareAndSet as its very first operation, so there is
        // a narrow window where this function passed the initial guard, exit() then ran
        // and set _isActive to false, and we would proceed to re-engage enforcement
        // during a session teardown. Catch that case and abort cleanly.
        if (!_isActive.value) {
            ProcessMonitor.launcherAllowedProcesses = emptySet()
            return
        }
        // silent = true: suppress "Nuclear Mode ON" — kill switch deactivation already
        // shows "Enforcement Resumed" from the tray; a second notification is redundant.
        NuclearMode.enable(silent = true)
        GlobalKeyboardHook.enable()
        RegistryLockdown.enable()
        startTaskbarGuard()
        _overlayVisible.value = true
    }

    // ── Interrupted-session recovery ──────────────────────────────────────────

    /**
     * Called at startup. Restores Windows to a normal usable state unconditionally.
     *
     * WHY unconditional showTaskbar():
     *   If the DB was corrupted and recreated from scratch, the crash guard key
     *   will NOT be present in the new empty DB — even though the taskbar is still
     *   hidden from the previous session. Checking the guard first would silently
     *   skip the restore and leave the user with a permanently hidden taskbar.
     *
     *   ShowWindow(taskbar, SW_SHOW) on an already-visible taskbar is a no-op,
     *   so calling it unconditionally costs nothing and is always safe.
     */
    fun restoreInterruptedSession() {
        // First clear OS state left by the previous JVM. A valid saved session below
        // will immediately re-apply the launcher restrictions.
        showTaskbar()
        try { RegistryLockdown.disable() } catch (_: Throwable) {}
        ProcessMonitor.launcherAllowedProcesses = emptySet()

        val hadLauncherMarkers =
            Database.getSetting(CRASH_GUARD_KEY) == "true" ||
                Database.getSetting(HARD_LOCK_KEY) == "true"
        val saved = Database.getFocusLauncherSession()
        if (saved == null || saved.pinHash.isBlank()) {
            clearStaleSessionMarkers(hadLauncherMarkers)
            return
        }

        val now = System.currentTimeMillis()
        if (saved.sessionEndMs > 0L && now >= saved.sessionEndMs) {
            clearStaleSessionMarkers(disableNuclear = true)
            return
        }

        if (saved.breakActive && saved.breakEndMs <= 0L) {
            clearStaleSessionMarkers(disableNuclear = true)
            return
        }

        sessionPinHash = saved.pinHash
        Database.setSetting(LAUNCHER_PIN_KEY, saved.pinHash)
        Database.setSetting(CRASH_GUARD_KEY, "true")
        Database.setSetting(HARD_LOCK_KEY, saved.hardLocked.toString())

        _isActive.value = true
        _isHardLocked.value = saved.hardLocked
        _breaksTotal.value = saved.breaksTotal
        _breaksUsed.value = saved.breaksUsed
        _canTakeBreak.value = saved.breaksTotal == -1 || saved.breaksUsed < saved.breaksTotal
        breakDurationSeconds = saved.breakDurationSeconds.coerceAtLeast(1)
        breakSecondsAccumulated.set(saved.breakSecondsAccumulated.coerceAtLeast(0L))
        _sessionApps.value = saved.apps.map {
            FocusLauncherApp(it.processName, it.displayName, it.exePath)
        }
        _sessionStartMs.value = saved.sessionStartMs
        _sessionEndMs.value = saved.sessionEndMs
        breakEndMs = saved.breakEndMs
        _sessionPin.value = ""

        if (saved.breakActive) {
            restoreInterruptedBreak(saved, now)
        } else {
            reenableLauncherRestrictions()
            if (_sessionEndMs.value > 0L) startSessionTimer()
        }
    }

    private fun clearStaleSessionMarkers(disableNuclear: Boolean) {
        runCatching { Database.clearFocusLauncherSession() }
        Database.setSetting(LAUNCHER_PIN_KEY, "")
        Database.setSetting(CRASH_GUARD_KEY, "false")
        Database.setSetting(HARD_LOCK_KEY, "false")
        sessionPinHash = ""
        _sessionPin.value = ""
        _isActive.value = false
        _isHardLocked.value = false
        _breakActive.value = false
        _breakRemainingSeconds.value = 0
        _sessionApps.value = emptyList()
        _sessionEndMs.value = 0L
        _sessionStartMs.value = 0L
        breakEndMs = 0L
        if (disableNuclear && NuclearMode.isActive) NuclearMode.disable(silent = true)
    }

    private fun restoreInterruptedBreak(saved: FocusLauncherSession, now: Long) {
        val remaining = ((saved.breakEndMs - now).coerceAtLeast(0L) / 1_000L).toInt()
        if (remaining == 0) {
            _breakActive.value = true
            _breakRemainingSeconds.value = 0
            endBreak()
            return
        }

        _breakActive.value = true
        _breakRemainingSeconds.value = remaining
        _overlayVisible.value = false
        ProcessMonitor.onLauncherForegroundChanged = ::onForegroundChanged
        ProcessMonitor.launcherAllowedProcesses = emptySet()
        if (NuclearMode.isActive) NuclearMode.disable(silent = true)
        GlobalKeyboardHook.disable()
        RegistryLockdown.disable()
        showTaskbar()

        breakJob = scope.launch {
            while (_breakActive.value) {
                delay(500)
                val next = maxOf(0L, (breakEndMs - System.currentTimeMillis()) / 1_000L)
                _breakRemainingSeconds.value = next.toInt()
                if (next == 0L) break
            }
            if (_breakActive.value) endBreak()
        }
    }

    private fun reenableLauncherRestrictions() {
        val allowedSet = _sessionApps.value.map { it.processName.lowercase() }.toSet()
        ProcessMonitor.launcherAllowedProcesses = allowedSet
        ProcessMonitor.onLauncherForegroundChanged = ::onForegroundChanged
        NuclearMode.enable(silent = true)
        GlobalKeyboardHook.enable()
        RegistryLockdown.enable()
        startTaskbarGuard()
        _overlayVisible.value = true
    }

    private fun persistSession() {
        val hash = sessionPinHash
        if (!_isActive.value || hash.isBlank()) return

        Database.saveFocusLauncherSession(
            FocusLauncherSession(
                apps = _sessionApps.value.map {
                    FocusLauncherSessionApp(it.processName, it.displayName, it.exePath)
                },
                sessionStartMs = _sessionStartMs.value,
                sessionEndMs = _sessionEndMs.value,
                breaksTotal = _breaksTotal.value,
                breaksUsed = _breaksUsed.value,
                breakDurationSeconds = breakDurationSeconds,
                breakSecondsAccumulated = breakSecondsAccumulated.get(),
                hardLocked = _isHardLocked.value,
                breakActive = _breakActive.value,
                breakEndMs = breakEndMs,
                pinHash = hash
            )
        )
    }

    // ── Emergency restore (crash handler / external call) ──────────────────

    /**
     * Public entry point for the global crash handler and the JVM shutdown hook.
     * Restores the taskbar and clears the launcher allowed-list — no DB access,
     * no coroutines, no state changes that could throw — pure Win32 calls only.
     * Must be safe to call from any thread at any time, including during a crash.
     */
    fun emergencyRestoreWindows() {
        ProcessMonitor.launcherAllowedProcesses = emptySet()
        try { GlobalKeyboardHook.disable() } catch (_: Throwable) {}
        try { RegistryLockdown.disable()   } catch (_: Throwable) {}
        showTaskbar()
    }

    // ── Session elapsed time helper ─────────────────────────────────────────

    fun elapsedSeconds(): Long {
        val start = _sessionStartMs.value
        if (start == 0L) return 0L
        val raw = (System.currentTimeMillis() - start) / 1000L
        return maxOf(0L, raw - breakSecondsAccumulated.get())
    }

    fun remainingSeconds(): Long {
        val endMs = _sessionEndMs.value
        if (endMs == 0L) return -1L
        return maxOf(0L, (endMs - System.currentTimeMillis()) / 1000L)
    }

    // ── OS-level taskbar control ────────────────────────────────────────────

    private fun hideTaskbar() {
        if (!isWindows) return
        try {
            val u32 = User32Extra.INSTANCE
            val taskbar = u32.FindWindowW("Shell_TrayWnd", null)
            if (taskbar != null) u32.ShowWindow(taskbar, SW_HIDE)
            // Enumerate ALL secondary taskbars — FindWindowW only returns the first,
            // which misses additional monitors. Loop via FindWindowExW to get all.
            var secondary = u32.FindWindowExW(null, null, "Shell_SecondaryTrayWnd", null)
            while (secondary != null) {
                u32.ShowWindow(secondary, SW_HIDE)
                secondary = u32.FindWindowExW(null, secondary, "Shell_SecondaryTrayWnd", null)
            }
        } catch (_: Exception) {}
    }

    private fun showTaskbar() {
        if (!isWindows) return
        try {
            val u32 = User32Extra.INSTANCE
            val taskbar = u32.FindWindowW("Shell_TrayWnd", null)
            if (taskbar != null) u32.ShowWindow(taskbar, SW_SHOW)
            // Enumerate ALL secondary taskbars — FindWindowW only returns the first,
            // which misses additional monitors. Loop via FindWindowExW to get all.
            var secondary = u32.FindWindowExW(null, null, "Shell_SecondaryTrayWnd", null)
            while (secondary != null) {
                u32.ShowWindow(secondary, SW_SHOW)
                secondary = u32.FindWindowExW(null, secondary, "Shell_SecondaryTrayWnd", null)
            }
        } catch (_: Exception) {}
    }

    private fun startSessionTimer() {
        sessionTimerJob?.cancel()
        sessionTimerJob = scope.launch {
            while (_isActive.value) {
                delay(1_000)
                val endMs = _sessionEndMs.value
                if (endMs > 0L && System.currentTimeMillis() >= endMs) {
                    exit()
                    SystemTrayManager.showNotification(
                        "Focus Launcher Ended",
                        "Your session is complete. Well done!",
                        TrayIcon.MessageType.INFO
                    )
                    return@launch
                }
            }
        }
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private const val SW_HIDE = 0
    private const val SW_SHOW = 5
}
