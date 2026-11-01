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
            expireStaleCopies()
            while (isActive) {
                val now = java.time.LocalTime.now()
                // Sleep until the next midnight  +1 minute so rollover is clean
                val secondsUntilMidnight = (24 * 3600L) - (now.toSecondOfDay().toLong()) + 60L
                delay(secondsUntilMidnight * 1000L)
                generateForToday()
                expireStaleCopies()
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
            // Series the user deleted or moved today's copy of — never re-create those.
            val removedToday = Database.getRecurringSkipParentIds(today)
            // Dedup key: title + scheduled time — prevents both cross-template title collisions
            // AND correctly allows two different templates that share a name but differ by time.
            val existingTasks = Database.getTasksForDate(today)
            val existingKeys = existingTasks
                .map { "${it.title.lowercase().trim()}|${it.scheduledTime?.toString() ?: ""}" }
                .toMutableSet()
            val existingParentIds = existingTasks.mapNotNull { it.recurringParentId }.toMutableSet()

            for (template in templates) {
                if (!shouldGenerateOn(template, today)) continue
                if (template.id in removedToday) continue
                val dedupKey = "${template.title.lowercase().trim()}|${template.scheduledTime?.toString() ?: ""}"
                if (template.id in existingParentIds || dedupKey in existingKeys) continue

                val newTask = template.copy(
                    id            = UUID.randomUUID().toString(),
                    scheduledDate = today,
                    scheduledTime = template.scheduledTime,
                    completed     = false,
                    skipped       = false,
                    recurringParentId = template.id,
                    recurringUntil = null,   // only the series original carries an end date
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

    /**
     * Closes missed repeats so they do not pile up as "Overdue": open repeat copies dated before
     * yesterday are marked skipped (yesterday's stays open for a one-day grace). Never deletes.
     * Deliberately separate from [generateForToday] so generation alone never closes anything.
     */
    @Synchronized
    fun expireStaleCopies(today: LocalDate = LocalDate.now()): Int {
        return try {
            val closed = Database.expireStaleSeriesCopies(today.minusDays(1))
            if (closed > 0) System.err.println("[RecurringTaskService] closed $closed missed repeat(s)")
            closed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: VirtualMachineError) {
            throw fatal
        } catch (failure: Throwable) {
            System.err.println("[RecurringTaskService] expireStaleCopies failed: ${failure.message}")
            0
        }
    }

    /**
     * Whether [template] should create a copy on [day]. Checks, in order:
     *  1. the repeat type is daily / weekdays / weekly (anything else never repeats),
     *  2. start guard — a template with a scheduled date never repeats before it
     *     (a null date means "no start guard"),
     *  3. end guard — never after [Task.recurringUntil] (that day itself is allowed),
     *  4. the type's own rule (weekly follows the weekday of the start date, or of the
     *     creation date when the template has no date).
     */
    internal fun shouldGenerateOn(template: Task, day: LocalDate): Boolean {
        val type = template.recurringType?.lowercase()
        if (type != "daily" && type != "weekdays" && type != "weekly") return false  // unknown/null: no task spam
        val start = template.scheduledDate
        if (start != null && day.isBefore(start)) return false
        val until = template.recurringUntil
        if (until != null && day.isAfter(until)) return false
        return when (type) {
            "daily"    -> true
            "weekdays" -> day.dayOfWeek !in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            else       -> day.dayOfWeek == (start ?: template.createdAt.toLocalDate()).dayOfWeek
        }
    }
}
