package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import com.focusflow.data.normalizeProcessKey
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
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
    @Volatile private var breakMonitorJob: Job? = null

    private val usageMs = mutableMapOf<String, Long>()
    private val blockedToday = mutableSetOf<String>()
    private val pendingLimitNotifications = mutableMapOf<String, DailyAllowance>()
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
                usageMs[normalizeProcessKey(processName)] = secondsToMillis(seconds)
            }
        }

        publishReconciledBlocks(loadedAllowances)

        lastTickWallMs = ports.clock.wallMs()
        lastTickMonoNs = ports.clock.monoNs()
        breakMonitorJob = scope.launch {
            var wasActive = ports.breakState.isActive.value
            ports.breakState.isActive.collect { active ->
                if (wasActive && !active) {
                    runCatching { enforceNow() }
                        .onFailure { warn("Could not resume allowance enforcement after Emergency Break", it) }
                }
                wasActive = active
            }
        }
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
        breakMonitorJob?.cancel()
        breakMonitorJob = null
        flushUsageToStore(trackingDate)
        retryPendingCleanup()
        safelySetBlocked(emptySet())
    }

    @Synchronized
    fun reload(): Result<Unit> {
        if (!isUsageStoreAvailable()) {
            return Result.failure(IllegalStateException("Daily allowance database is unavailable"))
        }
        try {
            val refreshed = ports.usageStore.allowances()
                .distinctBy { normalizeProcessKey(it.processName) }
            allowances = refreshed
            publishReconciledBlocks(refreshed)
            clearStoreWarning("read")
            return Result.success(Unit)
        } catch (failure: Throwable) {
            warnStoreOnce("read", "Could not reload daily allowances; keeping the last known list", failure)
            return Result.failure(failure)
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
            ports.foregroundSource.current()?.executableName?.let(::normalizeProcessKey)
        } else {
            null
        }
        val breakActive = ports.breakState.isActive.value

        val hasBlockedApps = synchronized(blockedToday) { blockedToday.isNotEmpty() }
        val shouldScanProcesses = !ports.isWindows || hasBlockedApps
        val runningProcesses = if (shouldScanProcesses) {
            ports.runningProcessSource.all()
        } else {
            emptyList()
        }
        if (!ports.isWindows && runningProcesses == null) return

        val runningMap: Map<String, List<RunningProcess>> = runningProcesses.orEmpty()
            .groupBy({ normalizeProcessKey(it.executableName) }, { it })

        for (allowance in currentAllowances) {
            val processName = normalizeProcessKey(allowance.processName)
            val running = runningMap[processName]
            val alreadyBlocked = synchronized(blockedToday) { processName in blockedToday }
            if (alreadyBlocked) {
                if (!breakActive && (ports.isWindows || running != null)) {
                    killProcess(allowance.processName, running)
                }
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
                if (breakActive) {
                    synchronized(pendingLimitNotifications) {
                        pendingLimitNotifications[processName] = allowance
                    }
                } else {
                    killProcess(allowance.processName, running)
                    notifyLimitReached(allowance)
                }
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
        synchronized(blockedToday) {
            blockedToday.clear()
            pendingLimitNotifications.clear()
        }
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
            usageMs.getOrDefault(normalizeProcessKey(processName), 0L) / MILLIS_PER_MINUTE
        }

    fun getRemainingMinutes(allowance: DailyAllowance): Long =
        maxOf(0L, allowance.allowanceMinutes.toLong() - getUsageMinutes(allowance.processName))

    fun getUsageSummary(): List<Pair<DailyAllowance, Long>> =
        allowances.map { allowance -> allowance to getUsageMinutes(allowance.processName) }

    internal fun tickForTest() = tick()

    private fun publishReconciledBlocks(currentAllowances: List<DailyAllowance>) {
        val nextBlocked = currentAllowances.mapNotNull { allowance ->
            val processName = normalizeProcessKey(allowance.processName)
            val usedMs = synchronized(usageMs) { usageMs.getOrDefault(processName, 0L) }
            if (usedMs / MILLIS_PER_MINUTE >= allowance.allowanceMinutes) processName else null
        }.toSet()

        synchronized(blockedToday) {
            blockedToday.clear()
            blockedToday.addAll(nextBlocked)
        }
        synchronized(pendingLimitNotifications) {
            pendingLimitNotifications.keys.retainAll(nextBlocked)
        }
        safelySetBlocked(nextBlocked)
    }

    /** Enforces a reconciled blocked set without waiting for the next polling tick. */
    private fun enforceNow() {
        if (ports.breakState.isActive.value) return

        val currentAllowances = allowances
        val blocked = blockedProcesses
        if (blocked.isEmpty()) return

        val runningProcesses = ports.runningProcessSource.all()
        if (!ports.isWindows && runningProcesses == null) {
            warn("Could not enumerate running processes while resuming allowance enforcement")
            return
        }
        val runningMap = runningProcesses.orEmpty()
            .groupBy({ normalizeProcessKey(it.executableName) }, { it })

        currentAllowances.forEach { allowance ->
            val processName = normalizeProcessKey(allowance.processName)
            if (processName !in blocked) return@forEach
            val running = runningMap[processName]
            if (ports.isWindows || running != null) {
                killProcess(allowance.processName, running)
            }
        }

        val notifications = synchronized(pendingLimitNotifications) {
            val ready = pendingLimitNotifications.filterKeys { it in blocked }.values.toList()
            pendingLimitNotifications.clear()
            ready
        }
        notifications.forEach(::notifyLimitReached)
    }

    private fun notifyLimitReached(allowance: DailyAllowance) {
        runCatching { ports.limitNotifier.dailyLimitReached(allowance) }
            .onFailure { warn("Could not show daily allowance limit notification", it) }
    }

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
