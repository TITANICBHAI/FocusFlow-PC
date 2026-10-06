package com.focusflow.services

import com.focusflow.data.Database
import com.focusflow.enforcement.KillSwitchService
import com.focusflow.enforcement.ProcessMonitor
import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.getForegroundProcessNameAndPid
import com.focusflow.enforcement.isWindows
import com.focusflow.enforcement.killProcessByName
import com.focusflow.services.allowance.AllowanceEngine
import com.focusflow.services.allowance.AllowancePorts
import com.focusflow.services.allowance.BlockedSetSink
import com.focusflow.services.allowance.BreakState
import com.focusflow.services.allowance.Clock
import com.focusflow.services.allowance.ForegroundInfo
import com.focusflow.services.allowance.ForegroundSource
import com.focusflow.services.allowance.FailureLogger
import com.focusflow.services.allowance.ProcessKiller
import com.focusflow.services.allowance.RunningProcess
import com.focusflow.services.allowance.RunningProcessSource
import com.focusflow.services.allowance.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.LocalDate

/**
 * Compatibility facade for existing UI and application call sites.
 * Tracking behavior lives in [AllowanceEngine]; this object supplies the
 * production database, Windows APIs, process killer, clock, and blocked-set sink.
 */
object DailyAllowanceTracker {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val engine = AllowanceEngine(createProductionPorts(), scope)

    val blockedProcesses: Set<String>
        get() = engine.blockedProcesses

    fun start() = engine.start()
    fun stop() = engine.stop()
    fun reload() = engine.reload()
    fun getUsageMinutes(processName: String): Long = engine.getUsageMinutes(processName)
    fun getRemainingMinutes(allowance: com.focusflow.data.models.DailyAllowance): Long =
        engine.getRemainingMinutes(allowance)
    fun getUsageSummary(): List<Pair<com.focusflow.data.models.DailyAllowance, Long>> =
        engine.getUsageSummary()

    private fun createProductionPorts() = AllowancePorts(
        clock = object : Clock {
            override fun wallMs(): Long = System.currentTimeMillis()
            override fun monoNs(): Long = System.nanoTime()
            override fun today(): LocalDate = LocalDate.now()
        },
        foregroundSource = ForegroundSource {
            getForegroundProcessNameAndPid()?.let { (name, pid) ->
                ForegroundInfo(name, pid)
            }
        },
        runningProcessSource = RunningProcessSource {
            try {
                val ownPid = ProcessHandle.current().pid()
                ProcessHandle.allProcesses()
                    .toList()
                    .filter { process -> process.pid() != ownPid }
                    .mapNotNull { process ->
                        val command = process.info().command().orElse(null) ?: return@mapNotNull null
                        RunningProcess(java.io.File(command).name.lowercase(), process.pid())
                    }
            } catch (_: Exception) {
                null
            }
        },
        processKiller = RealProcessKiller,
        breakState = object : BreakState {
            override val isActive = KillSwitchService.isActive
        },
        usageStore = object : UsageStore {
            override fun isAvailable() = Database.isReady
            override fun allowances() = Database.getDailyAllowances()
            override fun usage(date: LocalDate) = Database.getDailyUsage(date)
            override fun upsertUsage(date: LocalDate, processName: String, seconds: Long) =
                Database.upsertDailyUsage(date, processName, seconds)
            override fun deleteUsageBefore(date: LocalDate) =
                Database.deleteDailyUsageBefore(date)
        },
        blockedSetSink = BlockedSetSink { blocked ->
            ProcessMonitor.dailyAllowanceBlockedProcesses = blocked
        },
        isWindows = isWindows,
        failureLogger = FailureLogger { tag, message, cause ->
            EnforcementLog.warn(tag, message, cause)
        }
    )

    private object RealProcessKiller : ProcessKiller {
        private val ownPid = ProcessHandle.current().pid()

        override fun kill(processName: String) {
            if (isWindows) {
                killProcessByName(processName)
            }
        }

        override fun kill(processName: String, runningPids: List<Long>) {
            if (isWindows) {
                killProcessByName(processName)
            } else {
                runningPids
                    .filter { pid -> pid != ownPid }
                    .forEach { pid ->
                        ProcessHandle.of(pid).ifPresent { process ->
                            runCatching { process.destroyForcibly() }
                        }
                    }
            }
        }
    }
}
