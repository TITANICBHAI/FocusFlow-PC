package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

interface Clock {
    fun wallMs(): Long
    fun monoNs(): Long
    fun today(): LocalDate
}

data class ForegroundInfo(
    val executableName: String,
    val pid: Long
)

fun interface ForegroundSource {
    fun current(): ForegroundInfo?
}

data class RunningProcess(
    val executableName: String,
    val pid: Long
)

fun interface RunningProcessSource {
    /** Return null if enumeration failed, preserving the tracker's current skip-tick behavior. */
    fun all(): List<RunningProcess>?
}

fun interface ProcessKiller {
    fun kill(processName: String)

    /**
     * Allows the non-Windows adapter to keep using the process IDs discovered in
     * this tick, while simple fakes can implement only the name-based operation.
     */
    fun kill(processName: String, runningPids: List<Long>) = kill(processName)
}

interface BreakState {
    val isActive: StateFlow<Boolean>
}

interface UsageStore {
    fun isAvailable(): Boolean = true
    fun allowances(): List<DailyAllowance>
    fun usage(date: LocalDate): Map<String, Long>
    fun upsertUsage(date: LocalDate, processName: String, seconds: Long)
    fun deleteUsageBefore(date: LocalDate)
}

fun interface BlockedSetSink {
    fun set(blocked: Set<String>)
}

fun interface FailureLogger {
    fun warn(tag: String, message: String, cause: Throwable?)
}

data class AllowancePorts(
    val clock: Clock,
    val foregroundSource: ForegroundSource,
    val runningProcessSource: RunningProcessSource,
    val processKiller: ProcessKiller,
    val breakState: BreakState,
    val usageStore: UsageStore,
    val blockedSetSink: BlockedSetSink,
    val isWindows: Boolean,
    val failureLogger: FailureLogger = FailureLogger { _, _, _ -> }
)
