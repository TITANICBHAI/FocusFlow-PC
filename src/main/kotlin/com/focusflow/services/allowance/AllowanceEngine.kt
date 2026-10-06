package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import com.focusflow.services.SystemTrayManager
import java.awt.TrayIcon
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Platform- and persistence-independent allowance tracking logic.
 * All external reads and side effects are routed through [ports].
 */
class AllowanceEngine(
    private val ports: AllowancePorts,
    private val scope: CoroutineScope
) {
    @Volatile private var job: Job? = null

    private val usageMs = mutableMapOf<String, Long>()
    private val blockedToday = mutableSetOf<String>()
    private val pendingUsageWrites = mutableMapOf<LocalDate, MutableMap<String, Long>>()
    private val warnedStoreOperations = mutableSetOf<String>()

    @Volatile private var allowances: List<DailyAllowance> = emptyList()
    @Volatile private var trackingDate: LocalDate = ports.clock.today()

    private var persistTick = 0
    private var lastTickWallMs: Long? = null
    private var lastTickMonoNs: Long? = null
    private var pendingCleanupBefore: LocalDate? = null
    private var gapWarningEmitted = false
    private var consecutiveLoopFailures = 0

    val blockedProcesses: Set<String>
        get() = synchronized(blockedToday) { blockedToday.toSet() }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        if (!isUsageStoreAvailable()) return

        val today = ports.clock.today()
        val loadDate = if (today.isAfter(trackingDate)) today else trackingDate
        val loadedAllowances: List<DailyAllowance>
        val persistedUsage: Map<String, Long>
        try {
            loadedAllowances = ports.usageStore.allowances()
            persistedUsage = ports.usageStore.usage(loadDate)
            clearStoreWarning("read")
        } catch (failure: Throwable) {
            warnStoreOnce("read", "Could not load daily allowance state; tracking was not started", failure)
            return
        }

        trackingDate = loadDate
        allowances = loadedAllowances
        synchronized(usageMs) {
            usageMs.clear()
            persistedUsage.forEach { (processName, seconds) ->
                usageMs[processName.lowercase()] = secondsToMillis(seconds)
            }
        }

        val blocked = synchronized(blockedToday) {
            blockedToday.clear()
            loadedAllowances.forEach { allowance ->
                val used = synchronized(usageMs) {
                    usageMs.getOrDefault(allowance.processName.lowercase(), 0L)
                }
                if (used / MILLIS_PER_MINUTE >= allowance.allowanceMinutes) {
                    blockedToday.add(allowance.processName.lowercase())
                }
            }
            blockedToday.toSet()
        }
        safelySetBlocked(blocked)

        lastTickWallMs = ports.clock.wallMs()
        lastTickMonoNs = ports.clock.monoNs()
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                    consecutiveLoopFailures = 0
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    consecutiveLoopFailures++
                    if (consecutiveLoopFailures == 1 ||
                        consecutiveLoopFailures == 5 ||
                        consecutiveLoopFailures % 10 == 0
                    ) {
                        warn(
                            "Allowance tick failed (${consecutiveLoopFailures} consecutive failure(s)); " +
                                "the loop will retry",
                            failure
                        )
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        flushUsageToStore(trackingDate)
        retryPendingCleanup()
        safelySetBlocked(emptySet())
    }

    fun reload() {
        if (!isUsageStoreAvailable()) return
        try {
            allowances = ports.usageStore.allowances()
            clearStoreWarning("read")
        } catch (failure: Throwable) {
            warnStoreOnce("read", "Could not reload daily allowances; keeping the last known list", failure)
        }
    }

    private fun tick() {
        // Advance both clocks before any early return or external call can fail.
        val elapsedMs = elapsedSinceLastTickMs()
        val observedDate = ports.clock.today()
        if (observedDate.isAfter(trackingDate)) advanceTrackingDate(observedDate)

        persistTick++
        if (persistTick >= PERSIST_EVERY_TICKS) {
            persistTick = 0
            flushUsageToStore(trackingDate)
        }
        retryPendingCleanup()

        val currentAllowances = allowances
        if (currentAllowances.isEmpty()) return

        val foregroundProcess = if (ports.isWindows) {
            ports.foregroundSource.current()?.executableName?.lowercase()
        } else {
            null
        }

        val hasBlockedApps = synchronized(blockedToday) { blockedToday.isNotEmpty() }
        val shouldScanProcesses = !ports.isWindows || hasBlockedApps
        val runningProcesses = if (shouldScanProcesses) {
            ports.runningProcessSource.all()
        } else {
            emptyList()
        }
        if (!ports.isWindows && runningProcesses == null) return

        val runningMap: Map<String, List<RunningProcess>> = runningProcesses.orEmpty()
            .groupBy({ it.executableName.lowercase() }, { it })

        for (allowance in currentAllowances) {
            val processName = allowance.processName.lowercase()
            val running = runningMap[processName]
            val alreadyBlocked = synchronized(blockedToday) { processName in blockedToday }
            if (alreadyBlocked) {
                if (ports.isWindows || running != null) killProcess(allowance.processName, running)
                continue
            }

            val isForeground = if (ports.isWindows) {
                foregroundProcess != null && foregroundProcess == processName
            } else {
                running != null
            }

            if (!isForeground || elapsedMs <= 0L) continue

            val nextUsageMs = synchronized(usageMs) {
                val previous = usageMs.getOrDefault(processName, 0L)
                val next = addSaturated(previous, elapsedMs)
                usageMs[processName] = next
                next
            }
            if (nextUsageMs / MILLIS_PER_MINUTE >= allowance.allowanceMinutes) {
                val blocked = synchronized(blockedToday) {
                    blockedToday.add(processName)
                    blockedToday.toSet()
                }
                safelySetBlocked(blocked)
                killProcess(allowance.processName, running)
                SystemTrayManager.showNotification(
                    "Daily Limit Reached",
                    "${allowance.displayName} has used all ${allowance.allowanceMinutes}m today. Blocked until midnight.",
                    TrayIcon.MessageType.WARNING
                )
            }
        }
    }

    private fun elapsedSinceLastTickMs(): Long {
        val nowWallMs = ports.clock.wallMs()
        val nowMonoNs = ports.clock.monoNs()
        val previousWallMs = lastTickWallMs
        val previousMonoNs = lastTickMonoNs
        lastTickWallMs = nowWallMs
        lastTickMonoNs = nowMonoNs

        if (previousWallMs == null || previousMonoNs == null) return 0L
        val wallDeltaMs = nowWallMs - previousWallMs
        val monoDeltaNs = nowMonoNs - previousMonoNs
        val monoDeltaMs = monoDeltaNs / NANOS_PER_MILLI
        val elapsedMs = minOf(wallDeltaMs, monoDeltaMs)

        if (wallDeltaMs < 0L || monoDeltaNs < 0L || elapsedMs < 0L || elapsedMs > MAX_GAP_MS) {
            if (!gapWarningEmitted) {
                gapWarningEmitted = true
                warn(
                    "Discarded an allowance interval after a clock jump or long pause " +
                        "(wall=${wallDeltaMs}ms, monotonic=${monoDeltaMs}ms)"
                )
            }
            return 0L
        }
        return elapsedMs
    }

    private fun advanceTrackingDate(newDate: LocalDate) {
        flushUsageToStore(trackingDate)
        synchronized(usageMs) { usageMs.clear() }
        synchronized(blockedToday) { blockedToday.clear() }
        trackingDate = newDate
        persistTick = 0
        pendingCleanupBefore = pendingCleanupBefore?.let { maxOf(it, newDate) } ?: newDate
        safelySetBlocked(emptySet())
        retryPendingCleanup()
    }

    private fun flushUsageToStore(date: LocalDate) {
        val snapshot = synchronized(usageMs) {
            usageMs.mapValues { (_, milliseconds) -> milliseconds / MILLIS_PER_SECOND }
        }
        synchronized(pendingUsageWrites) {
            val pendingForDate = pendingUsageWrites.getOrPut(date, ::mutableMapOf)
            snapshot.forEach { (processName, seconds) -> pendingForDate[processName] = seconds }
        }
        retryPendingUsageWrites()
    }

    private fun retryPendingUsageWrites() {
        if (!isUsageStoreAvailable()) return
        val snapshot = synchronized(pendingUsageWrites) {
            pendingUsageWrites.mapValues { (_, rows) -> rows.toMap() }
        }

        snapshot.forEach { (date, rows) ->
            rows.forEach { (processName, seconds) ->
                try {
                    ports.usageStore.upsertUsage(date, processName, seconds)
                    clearStoreWarning("upsert")
                    synchronized(pendingUsageWrites) {
                        val pendingForDate = pendingUsageWrites[date] ?: return@synchronized
                        if (pendingForDate[processName] == seconds) pendingForDate.remove(processName)
                        if (pendingForDate.isEmpty()) pendingUsageWrites.remove(date)
                    }
                } catch (failure: Throwable) {
                    warnStoreOnce("upsert", "Could not persist daily allowance usage; will retry", failure)
                }
            }
        }
    }

    private fun retryPendingCleanup() {
        val cutoff = pendingCleanupBefore ?: return
        if (!isUsageStoreAvailable()) return
        val hasOlderPendingWrites = synchronized(pendingUsageWrites) {
            pendingUsageWrites.keys.any { it.isBefore(cutoff) }
        }
        if (hasOlderPendingWrites) return

        try {
            ports.usageStore.deleteUsageBefore(cutoff)
            clearStoreWarning("delete")
            pendingCleanupBefore = null
            synchronized(pendingUsageWrites) {
                pendingUsageWrites.keys.removeAll { it.isBefore(cutoff) }
            }
        } catch (failure: Throwable) {
            warnStoreOnce("delete", "Could not remove expired daily usage; will retry", failure)
        }
    }

    private fun isUsageStoreAvailable(): Boolean {
        return try {
            val available = ports.usageStore.isAvailable()
            if (available) {
                clearStoreWarning("availability")
            } else {
                warnStoreOnce("availability", "Usage store is unavailable; allowance tracking will not start", null)
            }
            available
        } catch (failure: Throwable) {
            warnStoreOnce("availability", "Could not check usage-store availability", failure)
            false
        }
    }

    private fun warnStoreOnce(operation: String, message: String, cause: Throwable?) {
        val firstWarning = synchronized(warnedStoreOperations) { warnedStoreOperations.add(operation) }
        if (firstWarning) warn(message, cause)
    }

    private fun clearStoreWarning(operation: String) {
        synchronized(warnedStoreOperations) { warnedStoreOperations.remove(operation) }
    }

    private fun safelySetBlocked(blocked: Set<String>) {
        try {
            ports.blockedSetSink.set(blocked)
        } catch (failure: Throwable) {
            warnStoreOnce("blocked-set", "Could not update the daily allowance blocked-process set", failure)
        }
    }

    private fun warn(message: String, cause: Throwable? = null) {
        runCatching {
            ports.failureLogger.warn("DailyAllowanceTracker", message, cause)
        }
    }

    private fun killProcess(processName: String, running: List<RunningProcess>?) {
        ports.processKiller.kill(processName, running.orEmpty().map { it.pid })
    }

    fun getUsageMinutes(processName: String): Long =
        synchronized(usageMs) {
            usageMs.getOrDefault(processName.lowercase(), 0L) / MILLIS_PER_MINUTE
        }

    fun getRemainingMinutes(allowance: DailyAllowance): Long =
        maxOf(0L, allowance.allowanceMinutes.toLong() - getUsageMinutes(allowance.processName))

    fun getUsageSummary(): List<Pair<DailyAllowance, Long>> =
        allowances.map { allowance -> allowance to getUsageMinutes(allowance.processName) }

    internal fun tickForTest() = tick()

    private fun secondsToMillis(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        return if (seconds > Long.MAX_VALUE / MILLIS_PER_SECOND) {
            Long.MAX_VALUE
        } else {
            seconds * MILLIS_PER_SECOND
        }
    }

    private fun addSaturated(current: Long, addition: Long): Long =
        if (Long.MAX_VALUE - current < addition) Long.MAX_VALUE else current + addition

    private companion object {
        const val TICK_INTERVAL_MS = 10_000L
        const val MAX_GAP_MS = 25_000L
        const val PERSIST_EVERY_TICKS = 6
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
