package com.focusflow.ui.components

import com.focusflow.data.models.Task
import com.focusflow.services.EndDateProblem
import com.focusflow.services.SeriesInfo
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SeriesTextTest {
    private fun template(type: String?, time: String?, until: LocalDate?, recurring: Boolean = true) = Task(
        id = "t", title = "Read", scheduledTime = time, recurring = recurring,
        recurringType = type, recurringUntil = until
    )

    @Test
    fun x1DescribeSeries() {
        assertEquals(
            "Repeats daily at 09:00 · 87 tasks created · Never ends",
            SeriesText.describeSeries(SeriesInfo(template("daily", "09:00", null), 87))
        )
        assertEquals(
            "Repeats weekdays · 1 task created · Ends on 2026-04-01",
            SeriesText.describeSeries(SeriesInfo(template("weekdays", null, LocalDate.of(2026, 4, 1)), 1))
        )
        assertEquals(
            "Repeating was stopped · 3 tasks created",
            SeriesText.describeSeries(SeriesInfo(template(null, "09:00", null, recurring = false), 3))
        )
    }

    @Test
    fun x2EveryEndDateProblemHasItsOwnMessage() {
        val texts = EndDateProblem.values().map { SeriesText.endDateProblem(it) }
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals(texts.size, texts.toSet().size)
        assertNotEquals(texts[0], texts[1])
    }
}
