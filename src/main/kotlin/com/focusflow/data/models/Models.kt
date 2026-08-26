package com.focusflow.data.models

import java.time.LocalDate
import java.time.LocalDateTime

data class Task(
    val id: String,
    val title: String,
    val description: String = "",
    val durationMinutes: Int = 25,
    val scheduledDate: LocalDate? = null,
    val scheduledTime: String? = null,
    val completed: Boolean = false,
    val skipped: Boolean = false,
    val recurring: Boolean = false,
    val recurringType: String? = null,
    val priority: String = "medium",
    val tags: List<String> = emptyList(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val completedAt: LocalDateTime? = null,
    val focusMode: Boolean = false,
    val focusIntensity: String = "standard",
    val focusBlockedApps: List<String> = emptyList(),
    val focusRequirePin: Boolean = false
)

data class FocusSession(
    val id: String,
    val taskId: String?,
    val taskName: String,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime?,
    val plannedMinutes: Int,
    val actualMinutes: Int = 0,
    val completed: Boolean = false,
    val interrupted: Boolean = false,
    val notes: String = ""
)

data class BlockRule(
    val id: String,
    val processName: String,
    val displayName: String,
    val enabled: Boolean = true,
    val blockNetwork: Boolean = false
)

data class BlockSchedule(
    val id: String,
    val name: String,
    val daysOfWeek: List<Int>,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
    val processNames: List<String> = emptyList()
)

/**
 * Block schedules use an integer representation so that 24:00 can be stored as
 * an explicit end-of-day value. LocalTime cannot represent 24:00.
 */
fun BlockSchedule.hasValidTimeRange(): Boolean =
    startHour in 0..23 &&
    startMinute in 0..59 &&
    endHour in 0..24 &&
    endMinute in 0..59 &&
    (endHour != 24 || endMinute == 0)

/**
 * Returns whether this schedule is active at [now].
 *
 * The calculation is kept with the model so the enforcement service and UI
 * cannot accidentally implement different rules for overnight schedules or
 * the special 24:00 end-of-day value.
 */
fun BlockSchedule.isActiveAt(now: LocalDateTime): Boolean {
    if (!enabled || !hasValidTimeRange()) return false

    val currentMinutes = now.hour * 60 + now.minute
    val startMinutes = startHour * 60 + startMinute
    val endMinutes = if (endHour == 24) 24 * 60 else endHour * 60 + endMinute
    val today = now.dayOfWeek.value

    // 24:00 is the end of the named day, not midnight at the start of it.
    if (endMinutes == 24 * 60) {
        return today in daysOfWeek && currentMinutes >= startMinutes
    }

    if (endMinutes > startMinutes) {
        return today in daysOfWeek &&
            currentMinutes >= startMinutes &&
            currentMinutes < endMinutes
    }

    // Equal start/end is an empty window (the 00:00–24:00 full-day case was
    // handled above). A start later than the end crosses midnight.
    if (endMinutes == startMinutes) return false

    val previousDay = if (today == 1) 7 else today - 1
    return (today in daysOfWeek && currentMinutes >= startMinutes) ||
        (previousDay in daysOfWeek && currentMinutes < endMinutes)
}

data class DailyAllowance(
    val processName: String,
    val displayName: String,
    val allowanceMinutes: Int
)

data class DailyNote(
    val date: LocalDate,
    val content: String,
    val mood: Int = 3,
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

data class TemptationEntry(
    val processName: String,
    val displayName: String,
    val timestamp: LocalDateTime
)

data class AppSettings(
    val sessionPinHash: String? = null,
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroBreakMinutes: Int = 5,
    val pomodoroLongBreakMinutes: Int = 15,
    val pomodoroSessionsBeforeLongBreak: Int = 4,
    val soundAversion: Boolean = false,
    val weeklyReportEnabled: Boolean = true,
    val alwaysOnEnforcement: Boolean = false,
    val startWithWindows: Boolean = false,
    val overlayMessage: String = "Stay focused. You've got this.",
    val theme: String = "dark",
    val userDisplayName: String = "",
    val dailyFocusGoalMinutes: Int = 120
)

data class Habit(
    val id: String,
    val name: String,
    val emoji: String = "✅",
    val createdAt: java.time.LocalDate = java.time.LocalDate.now()
)

data class HabitEntry(
    val habitId: String,
    val date: java.time.LocalDate,
    val done: Boolean = true
)

enum class NetworkRuleMode { DOMAIN, KEYWORD }

data class NetworkCutoffRule(
    val id: String,
    val pattern: String,
    val mode: NetworkRuleMode,
    val targetProcess: String? = null,
    val targetDisplayName: String? = null,
    val enabled: Boolean = true
)

enum class Screen {
    DASHBOARD, TASKS, FOCUS, FOCUS_LAUNCHER, BLOCK_APPS, STATS, NOTES, HABITS, REPORTS,
    PROFILE, SETTINGS, ACTIVE, KEYWORD_BLOCKER, BLOCK_DEFENSE, HOW_TO_USE, CHANGELOG,
    WINDOWS_SETUP, VPN_NETWORK, CONTACT, NUCLEAR_MODE, STANDALONE_BLOCK
}

data class SessionState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val taskName: String = "",
    val totalSeconds: Int = 0,
    val elapsedSeconds: Int = 0,
    val blockedProcesses: List<String> = emptyList()
)

data class DayFocusStats(
    val date: LocalDate,
    val totalMinutes: Int,
    val sessionsCount: Int
)

data class DayCompletionStats(
    val date: LocalDate,
    val completedCount: Int,
    val totalCount: Int,
    val focusMinutes: Int
)

data class StandaloneBlock(
    val processNames: List<String>,
    val untilMs: Long,
    val startMs: Long? = null   // null = start immediately; epoch ms = scheduled start
)

data class CustomBlockPreset(
    val id: String,
    val name: String,
    val emoji: String = "🚫",
    val processNames: List<String>,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
