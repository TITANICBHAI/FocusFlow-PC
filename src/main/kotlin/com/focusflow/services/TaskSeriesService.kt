package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.Task
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** What a task row is in relation to a repeating series. */
enum class SeriesRole {
    /** An ordinary one-off task. */
    NONE,
    /** The series original: `recurring = 1` and no parent. It defines the series. */
    TEMPLATE,
    /** A copy created for one day from a series original (it has a parent id). */
    COPY
}

/** How far an edit of a repeat copy reaches. */
enum class SeriesEditScope { ONLY_THIS, THIS_AND_FUTURE }

/** Quick choices for the "Ends" control. */
enum class EndChip { NEVER, ONE_WEEK, ONE_MONTH, THREE_MONTHS }

enum class EndDateProblem { BAD_FORMAT, BEFORE_START }

/** Series-level values chosen while editing a copy with scope [SeriesEditScope.THIS_AND_FUTURE]. */
data class SeriesEdit(val type: String, val until: LocalDate?)

/** The series original plus how many tasks (original + copies) the series has produced. */
data class SeriesInfo(val template: Task, val occurrences: Int)

sealed class EndDateResult {
    data class Valid(val date: LocalDate?) : EndDateResult()
    data class Invalid(val problem: EndDateProblem) : EndDateResult()
}

/**
 * All decisions about repeating-task series live here so both screens behave the same
 * and every rule is unit-testable (there are no UI tests).
 *
 * Pure helpers ([roleOf], [applyToSeries], [endDateForChip], [resolveEndDate]) are safe to call
 * during composition. Every other function touches the database: call them on Dispatchers.IO.
 */
object TaskSeriesService {

    // ── Pure helpers ─────────────────────────────────────────────────────────

    fun roleOf(task: Task): SeriesRole = when {
        task.recurringParentId != null -> SeriesRole.COPY
        task.recurring                 -> SeriesRole.TEMPLATE
        else                           -> SeriesRole.NONE
    }

    /**
     * The series original after "this and all future tasks" was chosen on a copy.
     * Only the task's definition changes; identity, date, completion state and creation
     * time stay those of [template].
     */
    fun applyToSeries(template: Task, edited: Task, series: SeriesEdit): Task = template.copy(
        title            = edited.title,
        description      = edited.description,
        durationMinutes  = edited.durationMinutes,
        scheduledTime    = edited.scheduledTime,
        priority         = edited.priority,
        tags             = edited.tags,
        focusMode        = edited.focusMode,
        focusIntensity   = edited.focusIntensity,
        focusBlockedApps = edited.focusBlockedApps,
        focusRequirePin  = edited.focusRequirePin,
        recurringType    = series.type,
        recurringUntil   = series.until
    )

    fun endDateForChip(start: LocalDate, chip: EndChip): LocalDate? = when (chip) {
        EndChip.NEVER        -> null
        EndChip.ONE_WEEK     -> start.plusWeeks(1).minusDays(1)
        EndChip.ONE_MONTH    -> start.plusMonths(1).minusDays(1)
        EndChip.THREE_MONTHS -> start.plusMonths(3).minusDays(1)
    }

    /** Blank = never ends. Otherwise an ISO date (yyyy-MM-dd) that is not before [start]. */
    fun resolveEndDate(text: String, start: LocalDate): EndDateResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return EndDateResult.Valid(null)
        val date = try {
            LocalDate.parse(trimmed)
        } catch (_: DateTimeParseException) {
            return EndDateResult.Invalid(EndDateProblem.BAD_FORMAT)
        }
        return if (date.isBefore(start)) EndDateResult.Invalid(EndDateProblem.BEFORE_START)
        else EndDateResult.Valid(date)
    }

    // ── Database work (call on Dispatchers.IO) ───────────────────────────────

    /** The series this task belongs to, or null for a plain task or when the original no longer exists. */
    fun seriesInfo(task: Task): SeriesInfo? {
        val seriesId = seriesIdOf(task) ?: return null
        val template = Database.getTaskById(seriesId) ?: return null
        return SeriesInfo(template, Database.countSeriesOccurrences(seriesId))
    }

    /**
     * Saves an edit. Order of work leaves a safe state if it stops part-way:
     * (1) removed-day marker for a moved copy, (2) series original update ("all future" only),
     * (3) the opened row, (4) stop the series if its original was un-ticked.
     */
    fun saveEdit(
        original: Task,
        edited: Task,
        scope: SeriesEditScope,
        series: SeriesEdit?,
        today: LocalDate = LocalDate.now()
    ) {
        val role = roleOf(original)
        val parentId = original.recurringParentId
        val originalDate = original.scheduledDate

        if (role == SeriesRole.COPY && parentId != null && originalDate != null &&
            edited.scheduledDate != originalDate
        ) {
            Database.addRecurringSkip(parentId, originalDate)
        }

        val futureSeries: SeriesEdit? =
            if (role == SeriesRole.COPY && scope == SeriesEditScope.THIS_AND_FUTURE) series else null
        if (futureSeries != null && parentId != null) {
            val template = Database.getTaskById(parentId)
            if (template != null) Database.upsertTask(applyToSeries(template, edited, futureSeries))
        }

        val toSave = if (role == SeriesRole.COPY) {
            edited.copy(recurringType = futureSeries?.type ?: original.recurringType, recurringUntil = null)
        } else {
            edited
        }
        Database.upsertTask(toSave)

        if (role == SeriesRole.TEMPLATE && original.recurring && !edited.recurring) {
            Database.stopRecurringSeries(original.id, today)
        }
    }

    /**
     * "Delete only this task". A copy leaves a removed-day marker so the series does not
     * re-create it. Deleting the series original necessarily ends the series.
     */
    fun deleteOccurrence(task: Task, today: LocalDate = LocalDate.now()) {
        when (roleOf(task)) {
            SeriesRole.NONE     -> Database.deleteTask(task.id)
            SeriesRole.TEMPLATE -> deleteAndStop(task, today)
            SeriesRole.COPY     -> {
                val parentId = task.recurringParentId
                val date = task.scheduledDate
                if (parentId != null && date != null) Database.addRecurringSkip(parentId, date)
                Database.deleteTask(task.id)
            }
        }
    }

    /** "Delete and stop repeating": end the series (nothing else is deleted), then delete this row. */
    fun deleteAndStop(task: Task, today: LocalDate = LocalDate.now()) {
        val seriesId = seriesIdOf(task)
        if (seriesId != null) Database.stopRecurringSeries(seriesId, today)
        Database.deleteTask(task.id)
    }

    /** "Stop repeating": end the series and keep every row. */
    fun stopRepeating(task: Task, today: LocalDate = LocalDate.now()) {
        val seriesId = seriesIdOf(task) ?: return
        Database.stopRecurringSeries(seriesId, today)
    }

    private fun seriesIdOf(task: Task): String? =
        task.recurringParentId ?: if (task.recurring) task.id else null
}
