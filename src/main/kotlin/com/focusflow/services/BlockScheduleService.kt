package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.data.models.BlockSchedule
import com.focusflow.data.models.hasValidTimeRange
import com.focusflow.data.models.isActiveAt
import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.ProcessMonitor
import kotlinx.coroutines.*
import java.time.DayOfWeek
import java.time.LocalDateTime

object BlockScheduleService {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // @Volatile: start() writes on the Compose application thread; stop() reads on the
    // "FocusFlow-Shutdown" daemon thread (Main.kt). Without @Volatile the shutdown
    // thread may see a stale null, leaving schedule enforcement running after teardown.
    @Volatile private var schedulerJob: Job? = null

    @Volatile var activeScheduleNames: List<String> = emptyList()
        private set

    fun start() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                tick()
                delay(60_000)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    private fun tick() {
        try {
            val schedules = Database.getBlockSchedules().filter { it.enabled }
            val now = LocalDateTime.now()

            val active = mutableListOf<String>()
            val blockedProcesses = mutableSetOf<String>()

            for (schedule in schedules) {
                if (!schedule.hasValidTimeRange()) {
                    EnforcementLog.warn(
                        "BlockScheduleService",
                        "Skipping schedule '${schedule.name}' with invalid time " +
                            "${schedule.startHour}:${schedule.startMinute}–" +
                            "${schedule.endHour}:${schedule.endMinute}"
                    )
                    continue
                }

                if (schedule.isActiveAt(now)) {
                    active.add(schedule.name)
                    blockedProcesses.addAll(schedule.processNames.map { it.lowercase() })
                }
            }

            activeScheduleNames = active
            ProcessMonitor.scheduleBlockedProcesses = blockedProcesses
        } catch (e: Exception) {
            EnforcementLog.warn(
                "BlockScheduleService",
                "tick() threw — schedule enforcement may be stale until next tick",
                e
            )
        }
    }

    fun forceCheck() = scope.launch { tick() }
}
