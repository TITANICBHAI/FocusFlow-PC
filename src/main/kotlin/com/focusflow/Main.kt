package com.focusflow

import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.focusflow.data.Database
import com.focusflow.enforcement.AppBlocker
import com.focusflow.enforcement.KillSwitchService
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.RegistryLockdown
import com.focusflow.enforcement.WatchdogInstaller
import com.focusflow.services.*
import com.focusflow.services.FocusLauncherService

fun main() = application {
    // ── Crash reporter — MUST be first, before any other service ──────────────
    // Installs handlers for:
    //   • All Java/Kotlin threads (Thread.setDefaultUncaughtExceptionHandler)
    //   • AWT Event Dispatch Thread (sun.awt.exception.handler)
    //   • Kotlin coroutines (fall-through to thread handler via SupervisorJob)
    // Writes a detailed report to Desktop/~/.focusflow/tmpdir with a Swing dialog.
    CrashReporter.install()

    // ── Startup registry janitor ───────────────────────────────────────────────
    // Unconditionally remove any leftover registry lockdown keys from a previous
    // session that was terminated before RegistryLockdown.disable() could run
    // (e.g. SIGKILL, power loss, OOM kill by the OS — scenarios where the JVM
    // shutdown hook cannot fire). This is safe: tryDelete() is a no-op when the
    // key doesn't exist, so a clean-boot startup is unaffected.
    try { RegistryLockdown.disable() } catch (_: Throwable) {}

    try {
        Database.init()
    } catch (e: Exception) {
        // Database.init() has its own internal recovery. If it still throws,
        // CrashReporter already installed the handler, so this is just logged.
        CrashReporter.report(Thread.currentThread(), e, source = "Database.init()")
    }

    // Auto-backup: daily rolling backup of SQLite database
    AutoBackupService.start()

    ProcessMonitor.alwaysOnEnabled   = Database.getSetting("always_on_enforcement") == "true"
    SoundAversion.isEnabled          = Database.getSetting("sound_aversion") != "false"
    FocusSessionService.pomodoroMode = Database.getSetting("pomodoro_mode") == "true"
    AppBlocker.overlayEnabled        = Database.getSetting("block_overlay_enabled") != "false"

    ProcessMonitor.start()

    BreakEnforcer.loadSettings()
    NuclearMode.loadFromDb()
    TaskAlarmService.start()

    // Kill switch — restore today's remaining budget from the DB
    KillSwitchService.loadFromDb()

    // Watchdog — register (or overwrite) the Task Scheduler entry that relaunches
    // FocusFlow every 2 minutes if it isn't running. No admin rights required.
    WatchdogInstaller.install()

    // Recurring tasks — auto-generate daily/weekday/weekly copies each morning
    RecurringTaskService.start()

    // Block schedules — recurring time-window enforcement
    BlockScheduleService.start()

    // Standalone block — restore a block that survived a restart
    StandaloneBlockService.loadFromDb()

    // Focus Launcher — restore taskbar and clear crash guard if we crashed while locked
    try { FocusLauncherService.loadFromDb() } catch (_: Throwable) {
        // Absolute fallback: if loadFromDb itself throws, at minimum restore the taskbar
        try { FocusLauncherService.emergencyRestoreWindows() } catch (_: Throwable) {}
    }

    // Daily allowances — per-app usage caps that reset at midnight
    DailyAllowanceTracker.start()

    // Sync existing FocusFlow firewall rules from Windows Firewall on startup
    // so rules created in a previous session are recognised without re-applying.
    NetworkBlocker.syncFromFirewall()

    // Start the hosts-file integrity monitor — re-applies blocks if an external
    // tool (antivirus, etc.) removes our entries while the app is running.
    if (HostsBlocker.getBlockedDomains().isNotEmpty()) {
        HostsBlocker.startMonitor()
    }

    WeeklyReportService.onReportReady = { report ->
        NotificationService.weeklyReport(report)
    }
    WeeklyReportService.startScheduler()

    var windowVisible by remember { mutableStateOf(true) }

    val windowState = rememberWindowState(
        width     = 1100.dp,
        height    = 720.dp,
        placement = WindowPlacement.Floating
    )

    val launcherActive   by FocusLauncherService.isActive.collectAsState()
    val launcherBreak    by FocusLauncherService.breakActive.collectAsState()
    val isKioskMode      = launcherActive && !launcherBreak

    LaunchedEffect(isKioskMode, launcherBreak) {
        when {
            isKioskMode -> {
                // Full kiosk: go fullscreen and keep the window visible/on-top.
                windowVisible = true
                windowState.placement = WindowPlacement.Fullscreen
            }
            launcherBreak -> {
                // Break is active while a session is still running.
                // Taskbar has been restored by FocusLauncherService.startBreak() but
                // the window is still fullscreen from kiosk mode — restore it to a
                // normal floating window so the user can actually reach their desktop.
                windowState.placement = WindowPlacement.Floating
            }
            !launcherActive -> {
                // Session fully ended: return window to floating.
                windowState.placement = WindowPlacement.Floating
            }
        }
    }

    // Dynamic title: tracks active session countdown in the OS window title bar
    val sessionState by FocusSessionService.state.collectAsState()
    val windowTitle = when {
        sessionState.isActive && sessionState.isPaused ->
            "FocusFlow — ${sessionState.taskName} (paused)"
        sessionState.isActive -> {
            val remSec = (sessionState.totalSeconds - sessionState.elapsedSeconds).coerceAtLeast(0)
            val remMin = remSec / 60
            val remS   = remSec % 60
            "FocusFlow — ${sessionState.taskName} (${remMin}m ${remS.toString().padStart(2,'0')}s left)"
        }
        else -> "FocusFlow"
    }

    // Keep the kill-switch tray menu item label in sync with the live countdown
    val ksRemaining by KillSwitchService.remainingSecondsToday.collectAsState()
    val ksActive    by KillSwitchService.isActive.collectAsState()
    LaunchedEffect(ksRemaining, ksActive) {
        val m = ksRemaining / 60
        val s = (ksRemaining % 60).toString().padStart(2, '0')
        val label = when {
            ksRemaining <= 0 -> "Emergency Break (exhausted for today)"
            ksActive         -> "Stop Break — ${m}m ${s}s remaining today"
            else             -> "Emergency Break (${m}m ${s}s left today)"
        }
        SystemTrayManager.updateKillSwitchItem(label)
    }

    // Shared shutdown action — runs ALL service teardown on a dedicated daemon
    // thread so the AWT Event Dispatch Thread never blocks. A hung service (e.g.
    // ProcessMonitor waiting on a thread join) would otherwise freeze the UI and
    // trigger a Windows "Not Responding" dialog.
    val doShutdown: () -> Unit = {
        Thread({
            FocusLauncherService.exit()
            KillSwitchService.deactivate()
            FocusSessionService.end(completed = false)
            // dispose() cancels the internal coroutine scope (timer job etc.) cleanly
            // after end() has already cleared enforcement state. Without this the scope
            // and its timer coroutine remain live until the JVM exits — not harmful, but
            // it prevents a fully clean teardown of FocusSessionService's internal state.
            FocusSessionService.dispose()
            WeeklyReportService.stopScheduler()
            TaskAlarmService.stop()
            RecurringTaskService.stop()
            BlockScheduleService.stop()
            DailyAllowanceTracker.stop()
            AutoBackupService.stop()
            NuclearMode.disable()
            // Join the background firewall-cleanup thread before the JVM exits so
            // firewall rules are never left active due to process death.
            NuclearMode.awaitCleanup()
            ProcessMonitor.dispose()
            AppBlocker.dispose()
            SystemTrayManager.remove()
            exitApplication()
        }, "FocusFlow-Shutdown").also { it.isDaemon = true }.start()
    }

    if (SystemTrayManager.isSupported) {
        SystemTrayManager.install(
            SystemTrayManager.TrayCallbacks(
                onRestore = { windowVisible = true },
                onQuit = doShutdown,
                onToggleBlocking = {
                    val newState = !ProcessMonitor.alwaysOnEnabled
                    // Disabling enforcement requires the GlobalPin if one is set
                    if (!newState && GlobalPin.isSet()) {
                        SystemTrayManager.showNotification(
                            "PIN Required",
                            "Open FocusFlow to disable enforcement — a PIN is required."
                        )
                        return@TrayCallbacks
                    }
                    ProcessMonitor.alwaysOnEnabled = newState
                    Database.setSetting("always_on_enforcement", newState.toString())
                    val status = if (newState) "ON" else "OFF"
                    SystemTrayManager.showNotification(
                        "FocusFlow Blocking $status",
                        "Always-on enforcement is now $status"
                    )
                },
                onKillSwitch = {
                    val activated = KillSwitchService.toggle()
                    when {
                        !activated -> SystemTrayManager.showNotification(
                            "Emergency Break Exhausted",
                            "You've used your 5-minute daily break budget. Resets at midnight."
                        )
                        KillSwitchService.isActive.value -> {
                            val secs = KillSwitchService.remainingSecondsToday.value
                            val m = secs / 60
                            val s = (secs % 60).toString().padStart(2, '0')
                            SystemTrayManager.showNotification(
                                "Emergency Break — Enforcement Paused",
                                "${m}m ${s}s remaining in your daily budget."
                            )
                        }
                        else -> {
                            val secs = KillSwitchService.remainingSecondsToday.value
                            val m = secs / 60
                            val s = (secs % 60).toString().padStart(2, '0')
                            SystemTrayManager.showNotification(
                                "Enforcement Resumed",
                                "${m}m ${s}s of emergency break budget remaining today."
                            )
                        }
                    }
                }
            )
        )
    }

    // Probe the classloader before calling painterResource — on some JVM environments
    // (Linux sandbox, headless CI) the context classloader may not include the resources
    // directory, causing painterResource to throw IllegalArgumentException.
    // Null icon is safe: Window() accepts Painter? and simply shows the OS default.
    val iconAvailable = remember {
        Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("focusflow_256.png")
            ?.also { it.close() } != null
    }
    val appIcon = if (iconAvailable) painterResource("focusflow_256.png") else null

    if (windowVisible) {
        Window(
            onCloseRequest = {
                if (isKioskMode) return@Window  // Cannot close window during kiosk mode
                if (SystemTrayManager.isSupported) {
                    windowVisible = false
                    SystemTrayManager.showNotification(
                        "FocusFlow is still running",
                        "Blocking stays active. Right-click the tray icon to quit."
                    )
                } else {
                    doShutdown()
                }
            },
            state       = windowState,
            title       = if (isKioskMode) "FocusFlow — Kiosk Mode" else windowTitle,
            icon        = appIcon,
            alwaysOnTop = isKioskMode
        ) {
            App()
        }
    }
}
