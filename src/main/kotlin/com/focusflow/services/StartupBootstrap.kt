package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.AppBlocker
import com.focusflow.enforcement.FloatingBlockOverlay
import com.focusflow.enforcement.KillSwitchService
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.WatchdogInstaller
import java.util.concurrent.atomic.AtomicBoolean

internal class StartOnce {
    private val started = AtomicBoolean(false)
    @Volatile private var failure: Throwable? = null

    fun run(action: () -> Unit): StartOnceResult {
        if (!started.compareAndSet(false, true)) {
            return failure?.let(StartOnceResult::Failed) ?: StartOnceResult.AlreadyStarted
        }
        return try {
            action()
            StartOnceResult.Started
        } catch (cause: Throwable) {
            failure = cause
            StartOnceResult.Failed(cause)
        }
    }
}

sealed interface StartOnceResult {
    data object Started : StartOnceResult
    data object AlreadyStarted : StartOnceResult
    data class Failed(val cause: Throwable) : StartOnceResult
}

/**
 * Starts every DB-dependent background service once, after Database.init()
 * has reported Ready. Compose recomposition cannot repeat this work.
 */
object StartupBootstrap {
    private val startup = StartOnce()

    fun startServices(onRestore: () -> Unit, onQuit: () -> Unit): StartOnceResult =
        startup.run {
            AutoBackupService.start()

            ProcessMonitor.alwaysOnEnabled =
                Database.getSetting("always_on_enforcement") == "true"
            SoundAversion.isEnabled = Database.getSetting("sound_aversion") != "false"
            FocusSessionService.pomodoroMode = Database.getSetting("pomodoro_mode") == "true"
            AppBlocker.overlayEnabled =
                Database.getSetting("block_overlay_enabled") != "false"
            FloatingBlockOverlay.overlayMessage =
                Database.getSetting("overlay_message") ?: "Stay focused. You've got this."

            ProcessMonitor.start()
            BreakEnforcer.loadSettings()
            NuclearMode.loadFromDb()
            TaskAlarmService.start()
            KillSwitchService.loadFromDb()
            WatchdogInstaller.install()
            RecurringTaskService.start()
            BlockScheduleService.start()
            StandaloneBlockService.loadFromDb()

            try {
                FocusLauncherService.restoreInterruptedSession()
            } catch (_: Throwable) {
                try {
                    FocusLauncherService.emergencyRestoreWindows()
                } catch (_: Throwable) {
                    // Keep the normal app available if OS-state restoration fails.
                }
            }

            DailyAllowanceTracker.start()
            NetworkBlocker.syncFromFirewall()
            if (HostsBlocker.getBlockedDomains().isNotEmpty()) {
                HostsBlocker.startMonitor()
            }

            WeeklyReportService.onReportReady = { report ->
                NotificationService.weeklyReport(report)
            }
            WeeklyReportService.startScheduler()

            if (SystemTrayManager.isSupported) {
                SystemTrayManager.install(
                    SystemTrayManager.TrayCallbacks(
                        onRestore = onRestore,
                        onQuit = onQuit,
                        onToggleBlocking = {
                            val newState = !ProcessMonitor.alwaysOnEnabled
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
        }
}
