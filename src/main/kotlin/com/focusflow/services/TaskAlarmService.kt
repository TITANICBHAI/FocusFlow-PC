package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.EnforcementLog
import kotlinx.coroutines.*
import java.awt.TrayIcon
import java.util.concurrent.ConcurrentHashMap
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object TaskAlarmService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: start() writes on the Compose application thread; stop() reads on the
    // "FocusFlow-Shutdown" daemon thread (Main.kt). Without @Volatile the shutdown
    // thread may see a stale null and skip the cancel, allowing notifications to fire
    // after the system tray has already been removed.
    @Volatile private var schedulerJob: Job? = null
    private val firedToday:    MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val persistLock = Any()  // guards read-modify-write on alarm_fired_ids
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    // Tracks the date for which firedToday was last populated.
    // Compared on every tick — no more time-window race condition at midnight.
    @Volatile private var lastClearedDate: LocalDate = LocalDate.now()
    @Volatile private var settingsLoaded: Boolean = false

    fun start() {
        if (schedulerJob?.isActive == true) return
        // Restore any alarms already fired today (survives app restarts)
        settingsLoaded = loadFiredIdsFromDb()
        schedulerJob = scope.launch {
            while (isActive) {
                if (!settingsLoaded) settingsLoaded = loadFiredIdsFromDb()
                if (settingsLoaded) checkAlarms()
                delay(30_000)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    // ── Persistence helpers ──────────────────────────────────────────────────

    /**
     * Load today's already-fired alarm IDs from the DB so a restart doesn't
     * re-fire alarms that already fired earlier today.
     */
    internal fun loadFiredIdsFromDb(): Boolean {
        val today = LocalDate.now()
        val storedDate = when (val read = Database.readSetting("alarm_fired_date")) {
            is Database.SettingRead.Present -> runCatching {
                LocalDate.parse(read.value)
            }.getOrElse {
                EnforcementLog.warn("TaskAlarmService", "Saved alarm date is malformed", it)
                return false
            }
            Database.SettingRead.Absent -> null
            is Database.SettingRead.Unreadable -> {
                EnforcementLog.warn("TaskAlarmService", "Could not read saved alarm date", read.cause)
                return false
            }
        }
        if (storedDate == today) {
            val ids = when (val read = Database.readSetting("alarm_fired_ids")) {
                is Database.SettingRead.Present -> read.value
                Database.SettingRead.Absent -> ""
                is Database.SettingRead.Unreadable -> {
                    EnforcementLog.warn("TaskAlarmService", "Could not read fired alarm IDs", read.cause)
                    return false
                }
            }
            if (ids.isNotBlank()) {
                firedToday.addAll(ids.split(",").filter { it.isNotBlank() })
            }
            lastClearedDate = today
            return true
        } else {
            if (!persistDailyReset(today)) return false
            firedToday.clear()
            lastClearedDate = today
            return true
        }
    }

    /**
     * Persist a fired alarm ID so it isn't re-fired after an app restart.
     * Persistence failure is logged; the in-memory set still prevents duplicates until restart.
     */
    private fun persistFiredId(id: String) {
        // synchronized: prevents two alarms firing in the same 30-second poll from
        // each reading the same `existing` string and overwriting each other's appended ID.
        synchronized(persistLock) {
            val existing = when (val read = Database.readSetting("alarm_fired_ids")) {
                is Database.SettingRead.Present -> read.value
                Database.SettingRead.Absent -> ""
                is Database.SettingRead.Unreadable -> {
                    EnforcementLog.warn("TaskAlarmService", "Could not read fired alarm IDs", read.cause)
                    return
                }
            }
            val updated  = if (existing.isBlank()) id else "$existing,$id"
            when (val write = Database.writeSetting("alarm_fired_ids", updated)) {
                Database.SettingWrite.Ok -> Unit
                is Database.SettingWrite.Failed ->
                    EnforcementLog.warn("TaskAlarmService", "Could not persist fired alarm ID", write.cause)
            }
        }
    }

    private fun persistDailyReset(today: LocalDate): Boolean =
        when (val write = Database.writeSettings(
            linkedMapOf(
                "alarm_fired_ids" to "",
                "alarm_fired_date" to today.toString()
            )
        )) {
            Database.SettingWrite.Ok -> true
            is Database.SettingWrite.Failed -> {
                EnforcementLog.warn("TaskAlarmService", "Could not persist daily alarm reset", write.cause)
                false
            }
        }

    // ── Alarm check ──────────────────────────────────────────────────────────

    private fun checkAlarms() {
        try {
            val today = LocalDate.now()
            if (!settingsLoaded) {
                settingsLoaded = loadFiredIdsFromDb()
                if (!settingsLoaded) return
            }

            // Date-change reset — works regardless of when the 30-second poll lands.
            // Replaces the old `hour == 0 && minute < 1` time-window that had a race
            // condition if the poll happened to skip past the narrow midnight window.
            if (today != lastClearedDate) {
                if (!persistDailyReset(today)) return
                firedToday.clear()
                lastClearedDate = today
            }

            val now = LocalDateTime.now()
            val tasks = Database.getTasksForDate(today)
                .filter { !it.completed && it.scheduledTime != null }

            for (task in tasks) {
                if (task.id in firedToday) continue
                val scheduledTime = try {
                    LocalTime.parse(task.scheduledTime!!, timeFmt)
                } catch (_: Exception) { continue }

                val scheduled = LocalDateTime.of(today, scheduledTime)
                val diffSeconds = java.time.Duration.between(now, scheduled).seconds

                if (diffSeconds in -60L..0L) {
                    firedToday.add(task.id)
                    persistFiredId(task.id)
                    SystemTrayManager.showNotification(
                        title   = "Task due: ${task.title}",
                        message = "Scheduled for ${task.scheduledTime} • ${task.durationMinutes}m",
                        type    = TrayIcon.MessageType.INFO
                    )
                    if (SoundAversion.isEnabled) SoundAversion.playSessionStart()
                } else if (diffSeconds in 1L..300L) {
                    val minsLeft = (diffSeconds / 60).toInt() + 1
                    if (minsLeft == 5 || minsLeft == 1) {
                        val reminderKey = "${task.id}_${minsLeft}min"
                        if (reminderKey !in firedToday) {
                            firedToday.add(reminderKey)
                            persistFiredId(reminderKey)
                            SystemTrayManager.showNotification(
                                title   = "Starting soon: ${task.title}",
                                message = "Due in $minsLeft minute${if (minsLeft == 1) "" else "s"}",
                                type    = TrayIcon.MessageType.INFO
                            )
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            EnforcementLog.warn(
                "TaskAlarmService",
                "Alarm polling failed; it will retry on the next poll",
                failure
            )
        }
    }

    internal fun firedIdsForTest(): Set<String> = firedToday.toSet()
    internal fun addFiredIdForTest(id: String) {
        firedToday += id
    }

    internal fun resetForTest() {
        stop()
        settingsLoaded = false
        firedToday.clear()
        lastClearedDate = LocalDate.now()
    }

    fun testAlarm(taskTitle: String) {
        SystemTrayManager.showNotification(
            title   = "Test Alarm: $taskTitle",
            message = "Alarms are working correctly.",
            type    = TrayIcon.MessageType.INFO
        )
    }
}
