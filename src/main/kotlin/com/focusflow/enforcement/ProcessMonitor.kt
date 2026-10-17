package com.focusflow.enforcement

import com.focusflow.data.Database
import com.focusflow.data.models.BlockRule
import com.focusflow.data.models.NetworkCutoffRule
import com.focusflow.data.models.NetworkRuleMode
import com.focusflow.services.KeywordMatchLogger
import com.focusflow.services.SoundAversion
import com.focusflow.services.TemptationLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * ProcessMonitor
 *
 * Dual-mode enforcement engine:
 *   1. WinEventHook (instant) — EVENT_SYSTEM_FOREGROUND fires on every foreground change.
 *      Zero delay between app switch and kill.
 *   2. Polling fallback (2 s when hook active, 750 ms when not) — catches apps already in
 *      the foreground when a session starts, and covers hook-registration failures.
 *
 * Concurrency model:
 *   Both the hook callback and the poll can fire for the same foreground window within
 *   milliseconds of each other. The cooldown check uses ConcurrentHashMap.compute() so
 *   the read-check-write is a single atomic operation — prevents double-kills.
 *
 * Block sources (union evaluated on every event):
 *   - alwaysOnEnabled / sessionActive    — all block_rules with enabled=1
 *   - sessionExtraBlockedProcesses       — per-task apps from the active task
 *   - scheduleBlockedProcesses           — injected by BlockScheduleService
 *   - standaloneBlockedProcesses         — injected by StandaloneBlockService
 *   - dailyAllowanceBlockedProcesses     — injected by DailyAllowanceTracker
 *
 * DB caching:
 *   All DB reads are cached in-memory (TTL = CACHE_TTL_MS). The enforcement hot-path
 *   never touches the database. Cache is eagerly refreshed on start() so the very first
 *   WinEventHook callback already has populated data.
 */
object ProcessMonitor {

    // ── Tuning constants ──────────────────────────────────────────────────────
    /** How long to wait before re-blocking the same process. 800 ms prevents instant
     *  re-spawn loops while still feeling near-instant to the user. */
    private const val COOLDOWN_MS  = 800L

    /** Poll interval when WinEventHook is active — slower since the hook handles
     *  real-time detection and the poll is only a safety net. */
    private const val POLL_HOOK_MS = 2_000L

    /** Poll interval when WinEventHook is NOT registered (fallback mode). */
    private const val POLL_FALLBACK_MS = 750L

    /** Background process sweep interval for process-name block rules. */
    private const val BACKGROUND_SWEEP_INTERVAL_MS = 1_000L

    /** How often the in-memory block-rule cache is refreshed from SQLite. */
    private const val CACHE_TTL_MS = 2_000L

    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    private var cacheJob:   Job? = null

    private val _blockedAttempts = MutableStateFlow(0)
    val blockedAttempts: StateFlow<Int> = _blockedAttempts

    private val _lastBlockedApp = MutableStateFlow<String?>(null)
    val lastBlockedApp: StateFlow<String?> = _lastBlockedApp

    /**
     * Per-process-key cooldown timestamps.
     * The map itself is ConcurrentHashMap, but the check-and-set uses .compute()
     * to make the entire read-compare-write sequence atomic, preventing duplicate
     * kills when the hook and poll both fire within the cooldown window.
     */
    private val cooldowns = ConcurrentHashMap<String, Long>()

    // @Volatile: these are written by the UI/service threads and read by the
    // monitorJob / tickPoll loop on Dispatchers.IO. Without @Volatile, the JVM
    // is free to cache the value in a register and the monitor thread may never
    // see updates, leaving enforcement enabled after a session ends.
    @Volatile var sessionActive:   Boolean = false
    @Volatile var alwaysOnEnabled: Boolean = false

    /**
     * Set to true by KillSwitchService while the daily emergency break is active.
     * When true, ALL enforcement is bypassed — no process kills, no network blocks.
     */
    @Volatile var killSwitchActive: Boolean = false

    /** Injected by BlockScheduleService — processes blocked by schedule right now. */
    @Volatile var scheduleBlockedProcesses: Set<String> = emptySet()

    /** Injected by StandaloneBlockService — processes blocked by timed block. */
    @Volatile var standaloneBlockedProcesses: Set<String> = emptySet()

    /** Injected by DailyAllowanceTracker — processes whose daily cap is exceeded. */
    @Volatile var dailyAllowanceBlockedProcesses: Set<String> = emptySet()

    /**
     * Injected by FocusSessionService — per-task extra blocked apps from the
     * active task's focusBlockedApps list. Cleared when the session ends.
     */
    @Volatile var sessionExtraBlockedProcesses: Set<String> = emptySet()

    /**
     * Injected by FocusLauncherService — when non-empty, the launcher kiosk
     * mode is active. Any foreground process NOT in this set (and not a known-safe
     * system process) will be killed. This is inverse of normal blocking.
     */
    @Volatile var launcherAllowedProcesses: Set<String> = emptySet()

    /** Full executable paths for the selected roots, keyed by normalized name. */
    @Volatile private var launcherAllowedAppPaths: Map<String, String> = emptyMap()
    internal val launcherAllowedAppExecutablePaths: Map<String, String>
        get() = launcherAllowedAppPaths

    /** Cached on the polling worker; native foreground callbacks only consult it. */
    @Volatile private var launcherPolicySnapshot: List<com.focusflow.services.ProcessSnapshotEntry> = emptyList()
    @Volatile private var launcherPolicySnapshotVersion: Long = 0L

    /**
     * Set by FocusLauncherService while a launcher session is active.
     * Called for every foreground change so the launcher can lower itself for
     * allowed apps and reclaim topmost ordering for other processes.
     */
    @Volatile var onLauncherProcessForegroundChanged: ((processName: String, pid: Long) -> Unit)? = null

    fun setLauncherAllowedApps(apps: List<com.focusflow.services.FocusLauncherApp>) {
        launcherAllowedProcesses = apps.map { it.processName.lowercase() }.toSet()
        launcherAllowedAppPaths = apps.mapNotNull { app ->
            app.exePath?.takeIf { it.isNotBlank() }
                ?.let { app.processName.lowercase() to it }
        }.toMap()
    }

    fun clearLauncherAllowedApps() {
        launcherAllowedProcesses = emptySet()
        launcherAllowedAppPaths = emptyMap()
        launcherPolicySnapshot = emptyList()
    }

    /**
     * Read-only cached policy query for AWT/WinEvent callbacks. It never
     * enumerates processes or opens process handles on the callback thread.
     */
    internal fun isLauncherProcessAllowed(processName: String, pid: Long): Boolean {
        val allowed = launcherAllowedProcesses
        if (allowed.isEmpty()) return true
        val snapshot = launcherPolicySnapshot
        val candidate = snapshot.firstOrNull { it.pid == pid }
            ?: com.focusflow.services.ProcessSnapshotEntry(pid = pid, processName = processName)
        return com.focusflow.services.LauncherKioskPolicy.decide(
            process = candidate,
            snapshot = snapshot,
            allowedProcessNames = allowed,
            allowedExecutablePaths = launcherAllowedAppPaths,
            safeProcessNames = launcherSafeProcesses,
            ownPid = ProcessHandle.current().pid(),
            systemRoot = System.getenv("SystemRoot") ?: System.getenv("WINDIR"),
            chainAllowanceEnabled = com.focusflow.services.LauncherChainAllowance.isEnabled,
            snapshotVersion = launcherPolicySnapshotVersion
        ) != com.focusflow.services.LauncherKioskPolicy.Decision.BLOCK
    }

    @Synchronized
    private fun refreshLauncherPolicySnapshot(
        forceRefresh: Boolean = false
    ): List<com.focusflow.services.ProcessSnapshotEntry> {
        val snapshot = com.focusflow.services.ProcessSnapshot.snapshotProcesses(forceRefresh)
        launcherPolicySnapshot = snapshot
        launcherPolicySnapshotVersion += 1L
        com.focusflow.services.LauncherKioskPolicy.onNewSnapshot(snapshot)
        return snapshot
    }

    /**
     * System processes that are always safe in launcher mode — never kill these.
     *
     * Covers six categories:
     *   1. Core Windows system processes (killing these = BSOD or unrecoverable state)
     *   2. Input stack: keyboard, mouse, touchpad, touchscreen, stylus, on-screen keyboard
     *      (killing any of these breaks input until reboot)
     *   3. Shell & UWP infrastructure that routes focus/activation events
     *   4. Security, Defender, and Windows Update processes
     *   5. Audio and display stack
     *   6. Accessibility middleware and tools
     *   7. OEM peripheral driver suites (Synaptics, Elan, Logitech, Razer, SteelSeries,
     *      Corsair, ASUS ROG) — killing these breaks mice/keyboards during kiosk mode
     *
     * Research basis: cross-referenced against Microsoft Learn (Win32/WDK docs),
     * Windows internals literature, and live process-tree analysis.
     * Last reviewed: 2025-05
     */
    val launcherSafeProcesses = setOf(
        // ── FocusFlow itself ──────────────────────────────────────────────────
        "focusflow.exe", "java.exe", "javaw.exe",

        // ── Core Windows kernel/session processes — NEVER touch these ─────────
        // Killing any of these causes BSOD, logon failure, or unrecoverable state.
        "explorer.exe",       // Shell host; taskbar, desktop, file dialogs
        "dwm.exe",            // Desktop Window Manager; mandatory compositor since Win8
        "winlogon.exe",       // Logon/session management; killing = immediate logoff
        "csrss.exe",          // Client/Server Runtime; hosts Raw Input Thread (RIT) — kill = BSOD
        "wininit.exe",        // Windows initialisation; parent of services.exe and lsass.exe
        "services.exe",       // Service Control Manager; all Windows services depend on this
        "lsass.exe",          // Local Security Authority; authentication — kill = immediate reboot
        "svchost.exe",        // Service host for hundreds of system services (audio, network, etc.)
        "smss.exe",           // Session Manager Subsystem; bootstraps Win32 subsystem
        "fontdrvhost.exe",    // User-mode font driver host; isolated font rendering
        "spoolsv.exe",        // Print spooler service
        "conhost.exe",        // Console host — required by many system and background processes
        "dllhost.exe",        // COM+ / DLL surrogate host
        "taskhostw.exe",      // Task host for DLL-based scheduled tasks
        "sihost.exe",         // Shell Infrastructure Host — context menus, input routing, transparency

        // ── Input framework — keyboard, mouse, touchpad, touchscreen, stylus ──
        // Killing ANY of these can cause a total input lockout requiring a hard reboot.
        "ctfmon.exe",              // CTF Loader / Text Services Framework — ALL keyboard input flows through this; IME support
        "tabtip.exe",              // Touch Keyboard & Handwriting Panel (tablets, Surface)
        "textinputhost.exe",       // Modern Text Input Host — emoji picker, clipboard history, touch keyboard UI (Win10/11)
        "osk.exe",                 // On-screen keyboard (accessibility + touchscreen fallback)
        "inputmethod.exe",         // Input method host
        "inputpersonalization.exe",// Input personalisation service (handwriting recognition training)
        "rdpinput.exe",            // Remote Desktop input redirection
        "tabletinputservice.exe",  // Tablet PC Input Service
        "wisptis.exe",             // Windows Ink Services Platform — stylus/pen pressure tracking
        "wudfhost.exe",            // Windows User-mode Driver Framework Host — HID/USB input drivers (touchpads, sensors)
        "hidinput.exe",            // HID input aggregator (some OEM drivers use this)
        "touchpointeditor.exe",    // Touchpad calibration (Synaptics/Elan/Precision touchpads)

        // ── Synaptics touchpad drivers (Dell, HP, Lenovo, Asus laptops) ───────
        "syntp.exe", "syntpenh.exe", "syntphelper.exe",
        "syntplpr.exe",            // Synaptics Low Power event handler
        "syntpenhservice.exe",     // Synaptics enhanced service wrapper
        "syntpstart.exe",          // Synaptics startup initialiser

        // ── Elan touchpad drivers (Asus, Acer, Lenovo, HP) ───────────────────
        "elantech.exe", "etdctrl.exe", "etdgesture.exe",
        "etdservice.exe",          // Elan Smart-Pad service
        "etdtouch.exe",            // Elan touch input coordinator

        // ── Microsoft IntelliMouse / Microsoft Keyboard ───────────────────────
        "itype.exe", "ipoint.exe",

        // ── Logitech — SetPoint (legacy) + G HUB (gaming) + Options (productivity) ──
        "setpoint.exe", "khalmnpr.exe",  // SetPoint (legacy MX mice + keyboards)
        "lghub.exe", "lghub_agent.exe", "lghub_updater.exe", // Logitech G HUB (gaming peripherals)
        "logioptions.exe",               // Logitech Options (productivity mice/keyboards)
        "logioptionsplus.exe",           // Logitech Options+ (newer productivity mice)
        "logooptionsplus.exe",           // Logitech Options+ (newer productivity mice)
        "lcore.exe",                     // Logitech Setpoint legacy core

        // ── Razer peripherals ─────────────────────────────────────────────────
        "razer.exe", "razercentralservice.exe",
        "razeringameengine.exe",   // Razer Synapse in-game overlay engine
        "rzsynapse.exe",           // Razer Synapse legacy helper

        // ── SteelSeries GG ────────────────────────────────────────────────────
        "steelseries.exe", "ggdrive.exe",
        "steelseriesgg.exe",       // SteelSeries GG main app (new suite)
        "steelseriesggclient.exe", // SteelSeries GG client helper

        // ── Corsair iCUE ──────────────────────────────────────────────────────
        "icue.exe",                // Corsair iCUE peripheral control
        "corsairservice.exe",      // Corsair background service
        "cuellaccessservice.exe",  // Corsair low-level access service (required for lighting + input)

        // ── ASUS ROG Armoury Crate ────────────────────────────────────────────
        "armourycrate.exe",        // ASUS ROG Armoury Crate (keyboard lighting, macro keys)
        "armourycrate.service.exe",// Armoury Crate service process
        "armourcrate.service.exe", // Armoury Crate service process
        "lightingservice.exe",     // ASUS/ROG lighting service

        // ── UWP & shell activation infrastructure ─────────────────────────────
        "runtimebroker.exe",           // UWP security broker — verifies app permissions; one per active UWP app
        "applicationframehost.exe",    // UWP window frame host — all UWP apps close instantly if this is killed
        "backgroundtaskhost.exe",      // UWP background task host — app sync, live tiles, background work
        "shellexperiencehost.exe",     // Action Centre, Quick Settings, notification toasts, taskbar flyouts
        "startmenuexperiencehost.exe", // Start menu host (isolated since Win10 1903 to prevent shell crash)
        "searchhost.exe",              // Windows 11 search UI (Feature Experience Pack)
        "searchapp.exe",               // Windows 10 search UI
        "lockapp.exe",                 // Lock screen
        "logonui.exe",                 // Logon UI
        "credentialuibroker.exe",      // Credential dialogs (UAC prompts, password boxes)
        "consent.exe",                 // UAC consent dialog
        "dashost.exe",                 // Device Association Framework
        "settingsynchost.exe",         // Settings sync
        "systemsettingsbroker.exe",    // Windows Settings UWP broker
        "usoclient.exe",               // Update Session Orchestrator client
        "usocoreworker.exe",           // Update Session Orchestrator core worker

        // ── Audio — must remain running for any in-app sound ──────────────────
        "audiodg.exe",                // Audio Device Graph Isolation — ALL sound routes through this
        "audioendpointbuilder.exe",   // Audio endpoint builder — manages audio devices/endpoints
        "displayswitch.exe",           // Display switching (Win+P multi-monitor)

        // ── Security & Windows Update ─────────────────────────────────────────
        "msmpeng.exe",                // Windows Defender antivirus engine
        "nissrv.exe",                 // Microsoft Network Realtime Inspection (Defender network protection)
        "securityhealthsystray.exe",  // Windows Security tray icon
        "securityhealthservice.exe",  // Windows Security health service (backend for the dashboard)
        "smartscreen.exe",            // Windows SmartScreen (download/app reputation checks)
        "msseces.exe",                // Microsoft Security Essentials (older Windows)
        "waasmedicagent.exe",         // Windows Update Medic Agent — self-heals Update components; cannot be stopped

        // ── Accessibility — screen readers, magnifier, AT broker ─────────────
        // atbroker.exe is the middleware layer ALL accessibility tools depend on;
        // killing it breaks Narrator/Magnifier even if those processes are still running.
        "narrator.exe",               // Windows Narrator screen reader
        "magnify.exe",                // Screen magnifier
        "utilman.exe",                // Utility Manager (Ease of Access shortcut Win+U)
        "atbroker.exe",               // Assistive Technology Broker — middleware for ALL AT tools
        "sethc.exe",                  // Sticky Keys handler (emergency keyboard accessibility)
        "eoaexperiences.exe"          // Ease of Access orchestration UI experiences
    )

    /** True when at least one enabled keyword-mode NetworkCutoffRule exists. */
    @Volatile var networkCutoffKeywordEnabled: Boolean = false

    // ── In-memory caches for hot-path DB reads ────────────────────────────────

    @Volatile private var cachedEnabledProcesses: Set<String>             = emptySet()
    @Volatile private var cachedKeywordEnabled:   Boolean                 = false
    @Volatile private var cachedKeywords:         List<String>            = emptyList()
    @Volatile private var cachedNetCutoffRules:   List<NetworkCutoffRule> = emptyList()
    @Volatile private var cachedBlockRules:       List<BlockRule>         = emptyList()
    @Volatile private var cacheLastRefreshMs:     Long                    = 0L

    private suspend fun refreshCaches() {
        val now = System.currentTimeMillis()
        if (now - cacheLastRefreshMs < CACHE_TTL_MS) return
        // NOTE: cacheLastRefreshMs is stamped AFTER all reads succeed so that a DB
        // exception does not lock out the cache for a full TTL window with stale data.

        // Refresh VPN settings only from this Dispatchers.IO cache path. The
        // WinEventHook callback uses VpnBlocker's volatile cache-only accessors.
        VpnBlocker.refreshSettingsFromDatabase()
        cachedEnabledProcesses  = Database.getEnabledBlockProcesses()
        cachedKeywordEnabled    = Database.isKeywordBlockerEnabled()
        cachedKeywords          = Database.getBlockedKeywords()
        cachedNetCutoffRules    = Database.getEnabledNetworkCutoffRules()
        cachedBlockRules        = Database.getBlockRules()
        networkCutoffKeywordEnabled =
            cachedNetCutoffRules.any { it.mode == NetworkRuleMode.KEYWORD && it.enabled }

        // Retry firewall rules that couldn't be applied earlier because the
        // target process wasn't running at block time. Now that processes may
        // have started, the path can be resolved and the rule applied.
        if (NetworkBlocker.pendingRuleCount() > 0) {
            NetworkBlocker.retryPendingRules()
        }

        cacheLastRefreshMs = now
    }

    /**
     * Force an immediate cache refresh on the next poll tick.
     * Call this after any DB write that affects enforcement.
     */
    fun invalidateCaches() {
        cacheLastRefreshMs = 0L
    }

    /**
     * Read-only, cache-only explanation for launcher pre-flight. Keep this
     * path non-blocking: the launch coordinator must not query the database
     * before deciding whether to create a process.
     */
    fun launcherBlockReason(processName: String): String? {
        val normalized = processName
            .substringAfterLast('\\')
            .substringAfterLast('/')
            .trim()
        if (normalized.isEmpty()) return null
        fun contains(set: Set<String>) = set.any { it.equals(normalized, ignoreCase = true) }

        return when {
            contains(scheduleBlockedProcesses) -> "Schedule"
            contains(standaloneBlockedProcesses) -> "Timed block"
            contains(dailyAllowanceBlockedProcesses) -> "Daily limit"
            sessionActive && contains(sessionExtraBlockedProcesses) -> "Focus session"
            alwaysOnEnabled && contains(cachedEnabledProcesses) -> "Always-on block"
            sessionActive && contains(cachedEnabledProcesses) -> "Focus session"
            VpnBlocker.isEnabledForEnforcement &&
                VpnBlocker.getAllBlockedProcesses().any {
                    it.equals(normalized, ignoreCase = true)
                } -> "VPN block"
            else -> null
        }
    }

    /**
     * Atomic cooldown gate. Returns true if this key has not fired within COOLDOWN_MS
     * AND atomically records the current timestamp so concurrent callers cannot both pass.
     *
     * ConcurrentHashMap.compute() guarantees the lambda runs under the key's lock,
     * making the read-compare-write a single uninterruptible operation.
     */
    private fun tryAcquireCooldown(key: String, now: Long): Boolean {
        var acquired = false
        cooldowns.compute(key) { _, lastHit ->
            if (lastHit == null || now - lastHit >= COOLDOWN_MS) {
                acquired = true
                now
            } else {
                lastHit  // keep existing timestamp — this caller loses the race
            }
        }
        return acquired
    }

    /**
     * Called from WinEventHook callback for instant (zero-delay) enforcement.
     * [pid] is the exact OS PID of the foreground window — used for targeted
     * per-window kills (e.g. one Chrome window) instead of all-instances-by-name.
     */
    fun onForegroundChanged(processName: String, pid: Long = 0L) {
        if (!isAnyEnforcementActive()) return
        val lower = processName.lowercase()

        // The launcher needs every foreground transition, including allowed
        // processes. Do this before the enforcement cooldown gate.
        if (launcherAllowedProcesses.isNotEmpty()) {
            onLauncherProcessForegroundChanged?.invoke(processName, pid)
        }

        val now   = System.currentTimeMillis()
        // Fast pre-check: discard obvious repeat events before spending coroutine
        // overhead. This prevents a rapid window-switching storm from queuing
        // hundreds of concurrent checkProcess() coroutines. The atomic gate inside
        // tryAcquireCooldown() — called again inside checkProcess() — is the real
        // correctness boundary; this is a performance optimisation only.
        if ((cooldowns[lower] ?: 0L).let { now - it < COOLDOWN_MS }) return
        scope.launch {
            try {
                checkProcess(processName, pid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: VirtualMachineError) {
                throw fatal
            } catch (failure: Throwable) {
                EnforcementLog.warn("ProcessMonitor", "Foreground enforcement check failed", failure)
            }
        }
    }

    private fun isAnyEnforcementActive(): Boolean {
        if (killSwitchActive) return false
        return sessionActive || alwaysOnEnabled ||
            scheduleBlockedProcesses.isNotEmpty() ||
            standaloneBlockedProcesses.isNotEmpty() ||
            dailyAllowanceBlockedProcesses.isNotEmpty() ||
            launcherAllowedProcesses.isNotEmpty() ||
            cachedKeywordEnabled ||
            VpnBlocker.isEnabledForEnforcement ||
            networkCutoffKeywordEnabled
    }

    fun start() {
        if (monitorJob?.isActive == true) return

        // Eagerly refresh caches before the hook fires its first callback.
        // This prevents the narrow window on session-start where cachedEnabledProcesses
        // is empty and a switch to a blocked app goes undetected.
        invalidateCaches()
        scope.launch(Dispatchers.IO) {
            try {
                refreshCaches()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: VirtualMachineError) {
                throw fatal
            } catch (failure: Throwable) {
                EnforcementLog.warn(
                    "ProcessMonitor",
                    "Initial cache refresh failed — block rules may be empty until next cycle",
                    failure
                )
            }
        }

        // Start background cache refresh loop
        if (cacheJob?.isActive != true) {
            cacheJob = scope.launch {
                while (isActive) {
                    try {
                        refreshCaches()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (fatal: VirtualMachineError) {
                        throw fatal
                    } catch (failure: Throwable) {
                        EnforcementLog.warn(
                            "ProcessMonitor",
                            "Periodic cache refresh failed — block rules may be stale",
                            failure
                        )
                    }
                    delay(CACHE_TTL_MS)
                }
            }
        }

        if (isWindows) {
            WinEventHook.start { pName, pid -> onForegroundChanged(pName, pid) }
        }

        monitorJob = scope.launch {
            while (isActive) {
                try {
                    if (isAnyEnforcementActive()) tickPoll()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: VirtualMachineError) {
                    throw fatal
                } catch (failure: Throwable) {
                    EnforcementLog.warn("ProcessMonitor", "Monitor tick failed; it will retry on the next poll", failure)
                }
                // Use a slower poll rate when the hook is active — the hook handles
                // instant detection, the poll is only a safety net (e.g., already-
                // foreground apps at session start, hook delivery gaps).
                val pollInterval = if (WinEventHook.isActive) POLL_HOOK_MS else POLL_FALLBACK_MS
                delay(pollInterval)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        cacheJob?.cancel()
        cacheJob = null
        WinEventHook.stop()
    }

    // ── Launcher sweep ────────────────────────────────────────────────────────

    /** Minimum elapsed-time threshold checked by tickPoll before a launcher sweep.
     *  This is not a scheduler: tickPoll runs once per monitor poll, every 2 seconds
     *  with WinEventHook or every 750 ms in fallback mode, so sweeps run no more
     *  often than that poll cadence. */
    private const val LAUNCHER_SWEEP_INTERVAL_MS = 250L

    @Volatile private var lastLauncherSweepMs = 0L
    @Volatile private var lastBackgroundSweepMs = 0L

    private suspend fun tickPoll() {
        if (!isWindows) return

        // Process-name blocks are enforced independently of window visibility.
        // This catches a blocked app that launches in the background without
        // creating a foreground event. The sweep never shows an overlay.
        val now = System.currentTimeMillis()
        if (now - lastBackgroundSweepMs >= BACKGROUND_SWEEP_INTERVAL_MS) {
            lastBackgroundSweepMs = now
            backgroundSweep()
        }

        // In launcher kiosk mode, run a periodic sweep of ALL running processes
        // in addition to the foreground check. This catches UWP apps whose window
        // is owned by ApplicationFrameHost.exe (the frame host appears as foreground
        // but the actual UWP process — e.g. Calculator.exe — runs separately and
        // would escape a foreground-only check). The sweep kills any process that
        // is not in the launcher's allowed set or the permanent safe list.
        val launcherAllowed = launcherAllowedProcesses
        if (launcherAllowed.isNotEmpty()) {
            val launcherNow = System.currentTimeMillis()
            if (launcherNow - lastLauncherSweepMs >= LAUNCHER_SWEEP_INTERVAL_MS) {
                lastLauncherSweepMs = launcherNow
                launcherSweep()
            }
        }

        val (processName, pid) = getForegroundProcessNameAndPid() ?: return
        checkProcess(processName, pid)
    }

    /**
     * Kills selected process-name blocks even when their windows are not in the
     * foreground. This is intentionally separate from the foreground path:
     * background kills have no visible-window context and therefore must never
     * show the block overlay.
     */
    private suspend fun backgroundSweep() {
        val blocked = buildBlockedProcessSet()
        if (blocked.isEmpty()) return

        val startedAtNanos = System.nanoTime()
        val ownPid = ProcessHandle.current().pid()
        val foregroundPid = getForegroundProcessNameAndPid()?.second ?: -1L
        val now = System.currentTimeMillis()
        try {
            val snapshot = com.focusflow.services.ProcessSnapshot.snapshotProcesses()
            for (process in snapshot) {
                if (process.pid == ownPid || process.pid == foregroundPid) continue
                val exeName = snapshotProcessName(process) ?: continue
                // The foreground PID can change after the process list snapshot.
                // Recheck it so a newly foregrounded blocked app goes through
                // checkProcess(), which is the only path allowed to show an overlay.
                if (exeName in blocked &&
                    com.focusflow.services.ProcessSnapshot.isAlive(
                        process.pid,
                        process.creationTimeMs
                    ) != false &&
                    !isVisibleForegroundWindowForPid(process.pid) &&
                    tryAcquireCooldown(exeName, now)
                ) {
                    enforceBlock(exeName, process.pid, showOverlay = false)
                }
            }
        } catch (e: Exception) {
            EnforcementLog.warn(
                "ProcessMonitor",
                "Background process sweep failed — foreground enforcement remains active",
                e
            )
        } finally {
            val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            if (durationMs > 50L) {
                EnforcementLog.info("ProcessMonitor", "backgroundSweep durationMs=$durationMs")
            }
        }
    }

    /**
     * Full-process-list enforcement for launcher kiosk mode.
     *
     * Iterates the shared process snapshot and kills any process whose name is
     * NOT in [allowed] AND NOT in [launcherSafeProcesses]. This is the
     * definitive fix for UWP apps running inside ApplicationFrameHost.exe:
     * the UWP process (e.g. calculator.exe) appears as its own ProcessHandle entry
     * even though its window frame is owned by applicationframehost.exe, so it is
     * correctly caught and terminated here.
     *
     * Called every [LAUNCHER_SWEEP_INTERVAL_MS] ms from [tickPoll] while kiosk
     * mode is active. Uses the same [tryAcquireCooldown] gate as the foreground
     * hook to prevent duplicate kills within the cooldown window.
     */
    private fun launcherSweep() {
        val startedAtNanos = System.nanoTime()
        val ownPid = ProcessHandle.current().pid()
        val now    = System.currentTimeMillis()
        try {
            val snapshot = refreshLauncherPolicySnapshot()
            for (process in snapshot) {
                // Re-read the active allow-set on every iteration. If exit()
                // clears it mid-sweep, stop using the stale snapshot at once.
                val currentAllowed = launcherAllowedProcesses
                if (currentAllowed.isEmpty()) break
                if (process.pid == ownPid) continue

                val resolvedPath = com.focusflow.services.ProcessSnapshot.resolveExecutablePath(process)
                val exeName = process.processName
                    .ifBlank { resolvedPath?.substringAfterLast('\\')?.substringAfterLast('/') ?: "" }
                    .substringAfterLast('\\')
                    .substringAfterLast('/')
                    .lowercase()
                val decision = com.focusflow.services.LauncherKioskPolicy.decide(
                    process = process,
                    snapshot = snapshot,
                    allowedProcessNames = currentAllowed,
                    allowedExecutablePaths = launcherAllowedAppPaths,
                    safeProcessNames = launcherSafeProcesses,
                    ownPid = ownPid,
                    systemRoot = System.getenv("SystemRoot") ?: System.getenv("WINDIR"),
                    chainAllowanceEnabled = com.focusflow.services.LauncherChainAllowance.isEnabled,
                    snapshotVersion = launcherPolicySnapshotVersion,
                    resolvePath = { com.focusflow.services.ProcessSnapshot.resolveExecutablePath(it) }
                )
                if (
                    decision == com.focusflow.services.LauncherKioskPolicy.Decision.BLOCK &&
                    tryAcquireCooldown("sweep:${process.pid}", now)
                ) {
                    // Target this PID only: name-wide taskkill could also
                    // terminate a separately proven FocusFlow-owned helper.
                    try { ProcessHandle.of(process.pid).orElse(null)?.destroyForcibly() } catch (_: Exception) {}
                    killProcessByPid(process.pid, exeName.takeIf { it.isNotBlank() })
                }
            }
        } catch (e: Exception) {
            // The entire kiosk sweep loop crashed silently. This is critical:
            // while launcherSweep() is dead, background processes go unkilled.
            com.focusflow.services.CrashReporter.reportCritical(
                source    = "ProcessMonitor.launcherSweep",
                message   = "Kiosk sweep loop threw an unhandled exception — background process enforcement may be inactive until next sweep tick.",
                throwable = e
            )
        } finally {
            val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            if (durationMs > 50L) {
                EnforcementLog.info("Launcher", "launcherSweep durationMs=$durationMs")
            }
        }
    }

    /**
     * UWP apps (Netflix, Calculator, Windows Store apps) are hosted inside
     * ApplicationFrameHost.exe. Resolve to the actual child process.
     */
    private val uwpFrameHost = "applicationframehost.exe"

    private val systemFrameProcesses = setOf(
        "applicationframehost.exe",
        "shellexperiencehost.exe",
        "startmenuexperiencehost.exe",
        "searchhost.exe",
        "searchapp.exe"
    )

    private suspend fun checkProcess(processName: String, pid: Long = 0L) {
        val lower = processName.lowercase()
        val now   = System.currentTimeMillis()
        val visibleForegroundWindow = isVisibleForegroundWindowForPid(pid)

        // Refresh caches if stale (non-blocking if still fresh)
        refreshCaches()

        // ── Build blocked set once ────────────────────────────────────────────
        val blocked = buildBlockedProcessSet()

        // ── Launcher kiosk mode — inverse block (kill anything not allowed) ───
        // Resolved BEFORE the standard UWP path so that in pure launcher mode
        // (where `blocked` is empty) a disallowed UWP app inside
        // ApplicationFrameHost.exe is still caught and killed.
        val launcherAllowed = launcherAllowedProcesses
        if (launcherAllowed.isNotEmpty()) {
            // When the UWP frame host is foreground, resolve its actual child;
            // the shared policy decides whether that child is a root, helper,
            // safe process, or a block target.
            val launcherTarget = if (lower == uwpFrameHost) {
                resolveUwpLauncherProcess(pid)
            } else {
                ProcessTarget(processName, pid)
            }
            if (launcherTarget != null) {
                val launcherResolved = launcherTarget.name
                val snapshot = launcherPolicySnapshot
                val entry = snapshot.firstOrNull { it.pid == launcherTarget.pid }
                    ?: com.focusflow.services.ProcessSnapshotEntry(
                        pid = launcherTarget.pid,
                        processName = launcherResolved
                    )
                val decision = com.focusflow.services.LauncherKioskPolicy.decide(
                    process = entry,
                    snapshot = snapshot,
                    allowedProcessNames = launcherAllowed,
                    allowedExecutablePaths = launcherAllowedAppPaths,
                    safeProcessNames = launcherSafeProcesses,
                    ownPid = ProcessHandle.current().pid(),
                    systemRoot = System.getenv("SystemRoot") ?: System.getenv("WINDIR"),
                    chainAllowanceEnabled = com.focusflow.services.LauncherChainAllowance.isEnabled,
                    snapshotVersion = launcherPolicySnapshotVersion,
                    resolvePath = { com.focusflow.services.ProcessSnapshot.resolveExecutablePath(it) }
                )
                if (
                    decision == com.focusflow.services.LauncherKioskPolicy.Decision.BLOCK &&
                    tryAcquireCooldown("launcher:${launcherTarget.pid}", now)
                ) {
                    if (launcherTarget.pid > 0L) {
                        try { ProcessHandle.of(launcherTarget.pid).orElse(null)?.destroyForcibly() } catch (_: Exception) {}
                        killProcessByPid(launcherTarget.pid, launcherResolved)
                    } else {
                        killProcessByName(launcherResolved)
                    }
                    _blockedAttempts.update { it + 1 }
                    return
                }
                // ALLOW and DEFER both continue through ordinary focus rules.
                // DEFER waits for a new background snapshot before deciding.
            }
            // An allowed launcher app is still processed by the normal enforcement
            // path below. Launcher mode must not become a bypass for always-on
            // rules, focus-session rules, schedules, allowances, VPN blocking,
            // keyword blocking, or network cutoffs.
        }

        // ── UWP frame host resolution (normal block mode) ─────────────────────
        val target = if (lower == uwpFrameHost) {
            // Never kill ApplicationFrameHost itself. It can host multiple UWP
            // windows, so only a child process that can be tied to this host PID
            // is a safe target.
            resolveUwpHostedProcess(blocked, pid) ?: return
        } else {
            ProcessTarget(processName, pid)
        }
        val resolvedName = target.name
        val resolvedPid = target.pid
        val resolvedLower = resolvedName.lowercase()

        // ── 0. VPN process blocking ───────────────────────────────────────────
        if (VpnBlocker.isVpnProcess(resolvedLower)) {
            if (tryAcquireCooldown("vpn:$resolvedLower", now)) {
                enforceBlock(resolvedName, resolvedPid, visibleForegroundWindow)
            }
            return
        }

        // ── 1. Process-name blocking ──────────────────────────────────────────
        if (blocked.any { resolvedLower == it.lowercase() }) {
            if (tryAcquireCooldown(resolvedLower, now)) {
                enforceBlock(resolvedName, resolvedPid, visibleForegroundWindow)
            }
            return
        }

        // ── 2a. Network cutoff keyword rules (cuts network, does NOT kill) ────
        if (networkCutoffKeywordEnabled) {
            val netRules = cachedNetCutoffRules.filter { it.mode == NetworkRuleMode.KEYWORD }
            if (netRules.isNotEmpty()) {
                val netTitle = getForegroundWindowTitle() ?: ""
                val netTitleLower = netTitle.lowercase()
                netRules.forEach { rule ->
                    val processMatches = rule.targetProcess == null ||
                        rule.targetProcess.lowercase() == resolvedLower
                    if (!processMatches) return@forEach
                    if (netTitleLower.contains(rule.pattern.lowercase())) {
                        val netKey = "net:${rule.id}:$resolvedLower"
                        if (tryAcquireCooldown(netKey, now)) {
                            val cutTarget = rule.targetProcess ?: resolvedName
                            NetworkBlocker.addRule(cutTarget)
                        }
                    }
                }
            }
        }

        // ── 2b. Keyword blocking (kills process) ─────────────────────────────
        if (!cachedKeywordEnabled) return
        val keywords = cachedKeywords
        if (keywords.isEmpty()) return

        val title = getForegroundWindowTitle() ?: return
        val titleLower = title.lowercase()
        val matchedKeyword = keywords.firstOrNull { kw -> titleLower.contains(kw.lowercase()) }
            ?: return

        if (!tryAcquireCooldown("kw:$resolvedLower", now)) return

        // Kill by PID when available — closes only the specific browser window.
        if (resolvedPid > 0L) {
            killProcessByPid(resolvedPid, resolvedName)
        } else {
            killProcessByName(resolvedName)
        }
        SoundAversion.playBlockAlert()

        val displayName = resolvedName.removeSuffix(".exe").replaceFirstChar { it.uppercase() }
        TemptationLogger.log(resolvedName, displayName)
        Database.logTemptation(resolvedName, displayName)
        KeywordMatchLogger.record(displayName, matchedKeyword, title)

        _blockedAttempts.update { it + 1 }
        _lastBlockedApp.value = displayName

        if (visibleForegroundWindow) {
            withContext(Dispatchers.Main) {
                AppBlocker.showOverlay(displayName)
            }
        }
    }

    /**
     * Builds the process-name block union used by both foreground enforcement
     * and the background sweep. The normal escape-process exclusions remain
     * owned by NuclearMode and are intentionally not added here.
     */
    private fun buildBlockedProcessSet(): Set<String> = buildSet {
        if (alwaysOnEnabled || sessionActive) addAll(cachedEnabledProcesses)
        addAll(scheduleBlockedProcesses)
        addAll(standaloneBlockedProcesses)
        addAll(dailyAllowanceBlockedProcesses)
        if (sessionActive) addAll(sessionExtraBlockedProcesses)
        // VPN clients also need background enforcement. Checking only the
        // foreground process lets VPN services/daemons keep running silently.
        if (VpnBlocker.isEnabledForEnforcement) addAll(VpnBlocker.getAllBlockedProcesses())
    }

    private data class ProcessTarget(val name: String, val pid: Long)

    private fun snapshotProcessName(
        process: com.focusflow.services.ProcessSnapshotEntry
    ): String? {
        val image = process.processName.takeIf(String::isNotBlank)
            ?: com.focusflow.services.ProcessSnapshot.resolveExecutablePath(process)
            ?: return null
        return image.substringAfterLast('\\')
            .substringAfterLast('/')
            .lowercase()
            .takeIf(String::isNotBlank)
    }

    private fun resolveUwpHostedProcess(blocked: Set<String>, hostPid: Long): ProcessTarget? {
        return try {
            if (blocked.isEmpty() || hostPid <= 0L) return null
            val ownPid = ProcessHandle.current().pid()
            fun findChild(snapshot: List<com.focusflow.services.ProcessSnapshotEntry>) =
                snapshot.asSequence()
                    .filter { process ->
                        process.pid != ownPid && process.parentPid == hostPid
                    }
                    .mapNotNull { process ->
                        val name = snapshotProcessName(process) ?: return@mapNotNull null
                        if (blocked.any { b -> name == b.lowercase() }) {
                            ProcessTarget(name, process.pid)
                        } else {
                            null
                        }
                    }
                    .firstOrNull()

            val cached = com.focusflow.services.ProcessSnapshot.snapshotProcesses()
            findChild(cached)
                ?: findChild(
                    com.focusflow.services.ProcessSnapshot.snapshotProcesses(forceRefresh = true)
                )
        } catch (_: Exception) { null }
    }

    /** Finds an actual foreground child hosted by ApplicationFrameHost. */
    private fun resolveUwpLauncherProcess(hostPid: Long): ProcessTarget? {
        return try {
            if (hostPid <= 0L) return null
            val ownPid = ProcessHandle.current().pid()
            fun findChild(snapshot: List<com.focusflow.services.ProcessSnapshotEntry>) =
                snapshot.asSequence()
                    .filter { process ->
                        process.pid != ownPid && process.parentPid == hostPid
                    }
                    .mapNotNull { process ->
                        val name = snapshotProcessName(process) ?: return@mapNotNull null
                        ProcessTarget(name, process.pid)
                    }
                    .firstOrNull()

            val cached = refreshLauncherPolicySnapshot()
            findChild(cached)
                ?: findChild(refreshLauncherPolicySnapshot(forceRefresh = true))
        } catch (_: Exception) { null }
    }

    /**
     * Shared kill + log + notify path for process-name block triggers.
     * Kills by PID when available (targeted), falls back to name-based kill.
     */
    private suspend fun enforceBlock(
        processName: String,
        pid: Long = 0L,
        showOverlay: Boolean = false
    ) {
        if (pid > 0L) {
            killProcessByPid(pid, processName)
        } else {
            killProcessByName(processName)
        }
        SoundAversion.playBlockAlert()

        val displayName = processName.removeSuffix(".exe").replaceFirstChar { it.uppercase() }
        TemptationLogger.log(processName, displayName)
        Database.logTemptation(processName, displayName)

        _blockedAttempts.update { it + 1 }
        _lastBlockedApp.value = displayName

        if (showOverlay) {
            withContext(Dispatchers.Main) {
                AppBlocker.showOverlay(displayName)
            }
        }

        val rule = cachedBlockRules.find { it.processName.equals(processName, ignoreCase = true) }
        if (rule?.blockNetwork == true) {
            NetworkBlocker.addRule(processName)
        }
    }

    fun dispose() {
        WinEventHook.stop()
        cacheJob?.cancel()
        scope.cancel()
    }
}
