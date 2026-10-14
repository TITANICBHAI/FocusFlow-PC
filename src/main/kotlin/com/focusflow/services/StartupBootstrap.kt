package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.AppBlocker
import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.FloatingBlockOverlay
import com.focusflow.enforcement.KillSwitchService
import com.focusflow.enforcement.NetworkBlocker
import com.focusflow.enforcement.NuclearMode
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.WatchdogInstaller
import kotlinx.coroutines.CancellationException
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
        } catch (cancelled: CancellationException) {
            failure = cancelled
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            failure = fatal
            throw fatal
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

internal fun alwaysOnEnabledFromSetting(setting: Database.SettingRead): Boolean =
    when (setting) {
        is Database.SettingRead.Present -> when (setting.value) {
            "true" -> true
            "false" -> false
            else -> {
                EnforcementLog.warn(
                    "StartupBootstrap",
                    "Always-On setting is malformed; enabling enforcement until it can be confirmed"
                )
                true
            }
        }
        Database.SettingRead.Absent -> false
        is Database.SettingRead.Unreadable -> {
            EnforcementLog.warn(
                "StartupBootstrap",
                "Always-On setting could not be read; enabling enforcement until it can be confirmed",
                setting.cause
            )
            true
        }
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
                alwaysOnEnabledFromSetting(Database.readSetting("always_on_enforcement"))
            SoundAversion.isEnabled = Database.getSetting("sound_aversion") != "false"
            FocusSessionService.pomodoroMode = Database.getSetting("pomodoro_mode") == "true"
            AppBlocker.overlayEnabled =
                Database.getSetting("block_overlay_enabled") != "false"
            FloatingBlockOverlay.overlayMessage =
                Database.getSetting("overlay_message") ?: "Stay focused. You've got this."

            com.focusflow.enforcement.WindowsSessionStateMonitor.start()
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
                            when (val saved = Database.writeSetting("always_on_enforcement", newState.toString())) {
                                Database.SettingWrite.Ok -> ProcessMonitor.alwaysOnEnabled = newState
                                is Database.SettingWrite.Failed -> {
                                    EnforcementLog.warn(
                                        "StartupBootstrap",
                                        "Could not persist Always-On state; leaving the current state unchanged",
                                        saved.cause
                                    )
                                    SystemTrayManager.showNotification(
                                        "Could Not Save Setting",
                                        "Always-On enforcement was not changed because its setting could not be saved."
                                    )
                                    return@TrayCallbacks
                                }
                            }
                            val status = if (newState) "ON" else "OFF"
                            SystemTrayManager.showNotification(
                                "FocusFlow Blocking $status",
                                "Always-on enforcement is now $status"
                            )
                        },
                        onKillSwitch = {
                            val wasActive = KillSwitchService.isActive.value
                            val activated = KillSwitchService.toggle()
                            when {
                                !KillSwitchService.settingsAvailable.value &&
                                    !wasActive ->
                                    SystemTrayManager.showNotification(
                                        "Emergency Break Unavailable",
                                        "The saved daily budget could not be read or saved. No break was started."
                                    )
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
