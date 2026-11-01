package com.focusflow.services

import com.focusflow.data.models.Task
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskAlarmCandidatesTest {
    @Test
    fun a1AlarmCandidatesExcludeCompletedSkippedAndUntimedTasks() {
        val open      = Task(id = "open", title = "Open", scheduledTime = "09:00")
        val completed = Task(id = "done", title = "Done", scheduledTime = "09:00", completed = true)
        val skipped   = Task(id = "skip", title = "Skipped", scheduledTime = "09:00", skipped = true)
        val untimed   = Task(id = "none", title = "No time")

        val result = TaskAlarmService.alarmCandidates(listOf(open, completed, skipped, untimed))

        assertEquals(listOf("open"), result.map { it.id })
    }
}
