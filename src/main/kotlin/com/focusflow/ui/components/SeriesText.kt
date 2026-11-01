package com.focusflow.ui.components

import com.focusflow.services.EndDateProblem
import com.focusflow.services.SeriesInfo

/**
 * All new English text for repeating-task series, kept in one place (owner decision RD-5:
 * translations are deferred, so these are not in the i18n files yet).
 */
internal object SeriesText {
    const val endsLabel = "Ends"
    const val chipNever = "Never"
    const val chipOneWeek = "1 week"
    const val chipOneMonth = "1 month"
    const val chipThreeMonths = "3 months"
    const val endsOnLabel = "Ends on (YYYY-MM-DD)"

    const val stopButton = "Stop repeating"
    const val stopConfirmTitle = "Stop repeating?"

    const val scopeLabel = "Apply changes to:"
    const val scopeOnlyThis = "Only this task"
    const val scopeFuture = "This and all future tasks"
    const val dateHint = "Changing the date moves only this task."
    const val originalNote = "Changes here apply to all future repeats."

    const val deleteOnlyButton = "Delete only this task"
    const val deleteAndStopButton = "Delete and stop repeating"

    fun stopConfirmBody(title: String): String =
        "\"$title\" will not be created again. Tasks that already exist stay in your list."

    fun deleteCopyBody(title: String): String =
        "\"$title\" repeats. Delete only this task and the repeat continues, or delete it and stop repeating."

    fun deleteOriginalBody(title: String): String =
        "\"$title\" is the original task of a repeating series. Deleting it also stops the repeat."

    fun endDateProblem(problem: EndDateProblem): String = when (problem) {
        EndDateProblem.BAD_FORMAT   -> "Use the format YYYY-MM-DD"
        EndDateProblem.BEFORE_START -> "The end date can't be before the start date"
    }

    /** One line for the banner at the top of the edit window. */
    fun describeSeries(info: SeriesInfo): String {
        val template = info.template
        val n = info.occurrences
        val created = if (n == 1) "1 task created" else "$n tasks created"
        if (!template.recurring) return "Repeating was stopped · $created"

        val type = template.recurringType?.lowercase()
        val time = template.scheduledTime
        val repeats = buildString {
            append("Repeats")
            if (type != null) append(' ').append(type)
            if (!time.isNullOrBlank()) append(" at ").append(time)
        }
        val ends = template.recurringUntil?.let { "Ends on $it" } ?: "Never ends"
        return "$repeats · $created · $ends"
    }
}
