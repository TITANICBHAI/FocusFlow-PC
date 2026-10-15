package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.Task
import kotlinx.coroutines.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * RecurringTaskService
 *
 * Mirrors Android's RecurringTaskManager.
 * On startup (and every day at midnight) it inspects all recurring task
 * templates and creates a fresh copy for today when none already exists.
 *
 * Recurrence rules (stored in Task.recurringType):
 *   "daily"    — every calendar day
 *   "weekdays" — Monday–Friday only
 *   "weekly"   — once a week, on the same day-of-week as the template
 */
object RecurringTaskService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: start() writes on the Compose application thread; stop() reads on the
    // "FocusFlow-Shutdown" daemon thread (Main.kt). Without @Volatile the shutdown
    // thread may see a stale null and skip the cancel.
    @Volatile private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            generateForToday()
            while (isActive) {
                val now = java.time.LocalTime.now()
                // Sleep until the next midnight  +1 minute so rollover is clean
                val secondsUntilMidnight = (24 * 3600L) - (now.toSecondOfDay().toLong()) + 60L
                delay(secondsUntilMidnight * 1000L)
                generateForToday()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Public so SettingsScreen / tests can trigger a manual refresh.
     *
     * @Synchronized: prevents duplicate task creation when two callers race.
     * On startup, RecurringTaskService.start() calls this from its coroutine,
     * and the UI's "Refresh" button may also call it from Dispatchers.IO.
     * Without synchronisation, both callers read the same `existing` set before
     * either DB insert is committed, pass the dedup check, and both insert a
     * fresh copy of every template — producing duplicate tasks for the day.
     */
    @Synchronized fun generateForToday(today: LocalDate = LocalDate.now()) {
        try {
            val templates = Database.getRecurringTemplates()
            // Dedup key: title + scheduled time — prevents both cross-template title collisions
            // AND correctly allows two different templates that share a name but differ by time.
            val existingTasks = Database.getTasksForDate(today)
            val existingKeys = existingTasks
                .map { "${it.title.lowercase().trim()}|${it.scheduledTime?.toString() ?: ""}" }
                .toMutableSet()
            val existingParentIds = existingTasks.mapNotNull { it.recurringParentId }.toMutableSet()

            for (template in templates) {
                if (!shouldGenerateToday(template, today)) continue
                val dedupKey = "${template.title.lowercase().trim()}|${template.scheduledTime?.toString() ?: ""}"
                if (template.id in existingParentIds || dedupKey in existingKeys) continue

                val newTask = template.copy(
                    id            = UUID.randomUUID().toString(),
                    scheduledDate = today,
                    scheduledTime = template.scheduledTime,
                    completed     = false,
                    skipped       = false,
                    recurringParentId = template.id,
                    createdAt     = LocalDateTime.now()
                )
                Database.upsertTask(newTask)
                existingParentIds += template.id
                existingKeys += dedupKey
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            System.err.println("[RecurringTaskService] generateForToday failed: ${failure.message}")
        }
    }

    private fun shouldGenerateToday(template: Task, today: LocalDate): Boolean {
        return when (template.recurringType?.lowercase()) {
            "daily"    -> true
            "weekdays" -> today.dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            "weekly"   -> {
                val origin = template.scheduledDate ?: return true
                today.dayOfWeek == origin.dayOfWeek
            }
            else       -> false  // skip unknown/null types to prevent accidental task spam
        }
    }
}
