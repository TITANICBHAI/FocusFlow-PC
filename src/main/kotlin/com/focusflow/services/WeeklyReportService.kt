package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.EnforcementLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object WeeklyReportService {

    data class WeeklyReport(
        val weekLabel:         String,
        val totalMinutes:      Long,
        val sessionsCompleted: Int,
        val tasksCompleted:    Int,
        val blockedAttempts:   Int,
        val avgDailyMinutes:   Long,
        val currentStreakDays: Int,
        val generatedAt:       String,
        val topBlockedApps:    List<Pair<String, Int>> = emptyList()
    ) {
        val hoursFormatted: String get() = "${totalMinutes / 60}h ${totalMinutes % 60}m"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: startScheduler() writes on the Compose application thread;
    // stopScheduler() reads on the "FocusFlow-Shutdown" daemon thread (Main.kt).
    // Without @Volatile the shutdown thread may see a stale null and skip the cancel.
    @Volatile private var schedulerJob: Job? = null

    @Volatile var latestReport: WeeklyReport? = null
        private set

    private val _hasNewReport = MutableStateFlow(false)
    val hasNewReport: StateFlow<Boolean> = _hasNewReport

    var onReportReady: ((WeeklyReport) -> Unit)? = null

    fun startScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            checkAndGenerateSafely()
            while (isActive) {
                val now = LocalDateTime.now()
                val nextMidnight = now.toLocalDate().plusDays(1).atTime(0, 1)
                val millisUntil = Duration.between(now, nextMidnight).toMillis()
                delay(millisUntil.coerceAtLeast(60_000L))
                checkAndGenerateSafely()
            }
        }
    }

    private suspend fun checkAndGenerateSafely() {
        try {
            checkAndGenerate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            EnforcementLog.warn(
                "WeeklyReportService",
                "Report check failed; it will retry on the next scheduled check",
                failure
            )
        }
    }

    fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
        // Release the callback reference so this singleton does not hold a strong
        // reference to any Compose-scoped lambda that captured composable state.
        // Without this, navigating away leaves a leaked closure in memory indefinitely.
        onReportReady = null
    }

    private suspend fun checkAndGenerate() {
        val lastStr = Database.getSetting("weekly_report_last_generated")
        val today   = LocalDate.now()
        val last    = lastStr?.let {
            try { LocalDate.parse(it) } catch (_: Exception) { null }
        }

        // Most recent Monday (or today if today is Monday)
        val mostRecentMonday = today.with(DayOfWeek.MONDAY).let { monday ->
            if (monday.isAfter(today)) monday.minusWeeks(1) else monday
        }

        // Generate if: never generated, OR last generation was before the most recent Monday
        // This catches up if the app wasn't open on Monday
        val due = last == null || last.isBefore(mostRecentMonday)

        if (due) {
            val report = generate()
            latestReport = report
            _hasNewReport.value = true
            onReportReady?.invoke(report)
            Database.setSetting(
                "weekly_report_last_generated",
                today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            )
        }
    }

    fun generate(): WeeklyReport {
        val today     = LocalDate.now()
        val weekStart = today.with(DayOfWeek.MONDAY).let { monday ->
            if (monday.isAfter(today)) monday.minusWeeks(1) else monday
        }.minusWeeks(1)
        val weekEnd   = weekStart.plusDays(6)
        val fmt       = DateTimeFormatter.ofPattern("MMM d")
        val weekLabel = "${weekStart.format(fmt)} \u2013 ${weekEnd.format(fmt)}"

        val sessions  = Database.getSessionsInRange(weekStart.toString(), weekEnd.toString())
        val tasks     = Database.getCompletedTasksInRange(weekStart.toString(), weekEnd.toString())
        val attempts  = Database.getTemptationsInRange(weekStart.toString(), weekEnd.toString())
        val streak    = Database.getCurrentStreak()
        val breakdown = Database.getTemptationBreakdownInRange(weekStart.toString(), weekEnd.toString())

        // Only completed sessions count toward minutes and session totals —
        // consistent with the daily stats screen and the updateDailyFocusMinutes logic.
        val completed = sessions.filter { it.completed }
        val totalMinutes = completed.sumOf { it.actualMinutes.toLong() }

        // Count only days that had at least one completed session — avoids artificially low averages
        val activeDays = completed.map { it.startTime.toLocalDate() }.toSet().size.coerceAtLeast(1)
        val avgDailyMinutes = if (completed.isEmpty()) 0L else totalMinutes / activeDays

        return WeeklyReport(
            weekLabel         = weekLabel,
            totalMinutes      = totalMinutes,
            sessionsCompleted = completed.size,
            tasksCompleted    = tasks,
            blockedAttempts   = attempts,
            avgDailyMinutes   = avgDailyMinutes,
            currentStreakDays = streak,
            generatedAt       = today.format(DateTimeFormatter.ofPattern("MMM d, yyyy")),
            topBlockedApps    = breakdown
        )
    }

    fun dismissNewReportBadge() { _hasNewReport.value = false }
}
