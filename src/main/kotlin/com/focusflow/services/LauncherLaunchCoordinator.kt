package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Launching(val startedAtMs: Long) : LaunchState
    data class Failed(val reason: String, val canRetry: Boolean) : LaunchState
}

data class RunningInstance(
    val pid: Long,
    val startedAtMs: Long,
    val processName: String,
    val parentPid: Long? = null,
    val creationTimeMs: Long? = startedAtMs,
    val executablePath: String? = null
)

data class ProcessSnapshotEntry(
    val pid: Long,
    val parentPid: Long? = null,
    val creationTimeMs: Long? = null,
    val processName: String = "",
    val executablePath: String? = null
)

data class WindowRef(val handle: Long, val pid: Long)

sealed interface SpawnResult {
    data class Started(val pid: Long) : SpawnResult
    data class Failed(val message: String, val canRetry: Boolean = true) : SpawnResult
}

data class RaiseResult(
    val success: Boolean,
    val tier: String,
    val tierResults: List<String> = emptyList()
)

/**
 * OS operations used by the launch state machine. The coordinator calls these
 * only from its worker scope, and fake implementations keep the state machine
 * deterministic in tests.
 */
interface LaunchPlatform {
    fun nowMs(): Long
    fun findInstances(app: FocusLauncherApp): List<RunningInstance>
    fun findWindows(pids: Set<Long>): List<WindowRef>
    fun processSnapshot(app: FocusLauncherApp): List<ProcessSnapshotEntry> =
        findInstances(app).map { instance ->
            ProcessSnapshotEntry(
                pid = instance.pid,
                parentPid = instance.parentPid,
                creationTimeMs = instance.creationTimeMs,
                processName = instance.processName,
                executablePath = instance.executablePath
            )
        }
    fun resolveProcessPath(process: ProcessSnapshotEntry): String? = process.executablePath
    fun findWindows(pids: Set<Long>, attemptId: String): List<WindowRef> =
        findWindows(pids)
    fun spawn(app: FocusLauncherApp): SpawnResult
    fun spawn(app: FocusLauncherApp, attemptId: String): SpawnResult = spawn(app)
    suspend fun raise(window: WindowRef): RaiseResult
    suspend fun raise(window: WindowRef, attemptId: String): RaiseResult = raise(window)
    fun focusFlowElevated(): Boolean? = null
    fun lastForegroundLockCall(): String? = null
    fun processIsAlive(pid: Long): Boolean? = null
    fun processIsAlive(pid: Long, creationTimeMs: Long?): Boolean? = processIsAlive(pid)
    fun finishAttempt(attemptId: String) {}

    suspend fun processSnapshotAsync(app: FocusLauncherApp): List<ProcessSnapshotEntry> =
        withContext(Dispatchers.IO) { processSnapshot(app) }

    suspend fun findWindowsAsync(pids: Set<Long>, attemptId: String): List<WindowRef> =
        withContext(Dispatchers.IO) { findWindows(pids, attemptId) }

    suspend fun spawnAsync(app: FocusLauncherApp, attemptId: String): SpawnResult =
        withContext(Dispatchers.IO) { spawn(app, attemptId) }
}

class LauncherLaunchCoordinator(
    private val platform: LaunchPlatform,
    private val scope: CoroutineScope,
    private val isAllowed: (FocusLauncherApp) -> Boolean = { true },
    private val onRaised: (FocusLauncherApp) -> Unit = {},
    private val logInfo: (String) -> Unit = { EnforcementLog.info("Launcher", it) }
) {
    companion object {
        private val attemptSequence = java.util.concurrent.atomic.AtomicLong()
        private const val MAX_POLL_CHANGE_LOGS = 1

        const val WINDOW_WAIT_MS = 20_000L
        const val SPAWN_BUDGET_MS = 20_000L
        const val WINDOW_POLL_MS = 150L
        const val PROCESS_REFRESH_MS = 1_000L
        const val FAILURE_DISPLAY_MS = 6_000L
        const val RAISE_RETRY_DELAY_MS = 150L
        const val MAX_RAISE_ATTEMPTS = 3

        private val applicationScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val shared: LauncherLaunchCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            LauncherLaunchCoordinator(
                platform = JvmLauncherLaunchPlatform,
                scope = applicationScope,
                isAllowed = FocusLauncherService::isLaunchAllowed,
                onRaised = { app -> FocusLauncherService.onForegroundChanged(app.processName) }
            )
        }
    }

    private enum class AttemptFailureCode {
        SpawnFailed,
        NoWindow,
        RaiseFailed,
        Internal,
        Cancelled
    }

    private class AttemptTrace(
        val id: String,
        val app: FocusLauncherApp,
        val startedAtMs: Long
    ) {
        val summaryLogged = AtomicBoolean(false)
        val windowsSeen = linkedSetOf<WindowRef>()
        val tierResults = mutableListOf<String>()
        var snapshotMs: Long? = null
        var spawnMs: Long? = null
        var firstWindowMs: Long? = null
        var raiseStartedAtMs: Long? = null
        var raiseMs: Long? = null
        var spawnPid: Long? = null
        var trackedPids: Set<Long> = emptySet()
        var focusFlowElevated: Boolean? = null
        var lastObservedPids: Set<Long>? = null
        var lastObservedWindows: Set<WindowRef>? = null
        var pollChangeLogs = 0
        var suppressedPollChanges = 0
    }

    private val lock = Any()
    private val _states = MutableStateFlow<Map<String, LaunchState>>(emptyMap())
    val states: StateFlow<Map<String, LaunchState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val failureResetJobs = mutableMapOf<String, Job>()
    private val lastSpawnAtMs = mutableMapOf<String, Long>()
    private val waitOnlyRetryKeys = mutableSetOf<String>()

    @Volatile
    private var generation = 0L

    /**
     * Marks the tile Launching before scheduling any OS work, so a second click
     * in the same UI frame is rejected instead of starting another process.
     */
    fun request(app: FocusLauncherApp): Boolean {
        val key = app.processName.lowercase(Locale.ROOT)
        synchronized(lock) {
            if (!isAllowed(app)) {
                logInfo("Ignored launch for non-session app=${app.processName}")
                return false
            }

            val current = _states.value[key]
            if (current is LaunchState.Launching) {
                logInfo("Ignored duplicate click app=${app.processName}")
                return false
            }
            if (current is LaunchState.Failed && !current.canRetry) {
                return false
            }

            failureResetJobs.remove(key)?.cancel()
            val waitOnlyRetry = waitOnlyRetryKeys.remove(key)
            val requestGeneration = generation
            val launchStartedAt = platform.nowMs()
            val trace = AttemptTrace(
                id = "launcher-${attemptSequence.incrementAndGet()}",
                app = app,
                startedAtMs = launchStartedAt
            )
            _states.update { it + (key to LaunchState.Launching(launchStartedAt)) }
            logAttempt(trace, "event=begin retryWaitOnly=$waitOnlyRetry")

            val job = scope.launch(start = CoroutineStart.LAZY) {
                executeRequest(app, key, requestGeneration, waitOnlyRetry, trace)
            }
            jobs[key] = job
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    completeAttempt(trace, AttemptFailureCode.Cancelled)
                }
                synchronized(lock) {
                    if (jobs[key] === job) jobs.remove(key)
                }
            }
            job.start()
        }

        return true
    }

    /**
     * Stops all pending launch/focus work when the kiosk session is lifted or
     * enforcement enters a break. Spawn timestamps remain so the per-app
     * 20-second budget still applies if the user immediately re-enters.
     */
    fun cancelAll() {
        synchronized(lock) {
            generation += 1
            jobs.values.forEach(Job::cancel)
            jobs.clear()
            failureResetJobs.values.forEach(Job::cancel)
            failureResetJobs.clear()
            waitOnlyRetryKeys.clear()
            _states.value = emptyMap()
        }
    }

    private suspend fun executeRequest(
        app: FocusLauncherApp,
        key: String,
        requestGeneration: Long,
        waitOnlyRetry: Boolean,
        trace: AttemptTrace
    ) {
        try {
            trace.focusFlowElevated = runCatching { platform.focusFlowElevated() }.getOrNull()
            if (!isCurrent(app, requestGeneration)) return

            logAttempt(trace, "phase=snapshot start")
            val snapshotStartedAt = platform.nowMs()
            val (instances, pids, windows) = try {
                val foundInstances = platform.findInstances(app)
                val foundPids = foundInstances.mapTo(mutableSetOf()) { it.pid }
                val foundWindows = platform.findWindows(foundPids, trace.id)
                Triple(foundInstances, foundPids, foundWindows)
            } finally {
                trace.snapshotMs = elapsedSince(snapshotStartedAt)
            }
            trace.trackedPids = pids.toSet()
            observe(trace, pids, windows, "snapshot")
            logAttempt(
                trace,
                "phase=snapshot durationMs=${trace.snapshotMs} " +
                    "instances=${instances.size} pids=${formatPids(pids)} windows=${windows.size}"
            )

            if (windows.isNotEmpty()) {
                raiseWithRetries(app, key, windows.first(), requestGeneration, trace)
                return
            }

            val now = platform.nowMs()
            val hasRecentInstance = instances.any { instance ->
                val age = now - instance.startedAtMs
                age in 0 until WINDOW_WAIT_MS
            }
            val withinSpawnBudget = synchronized(lock) {
                val lastSpawnAt = lastSpawnAtMs[key] ?: return@synchronized false
                now - lastSpawnAt < SPAWN_BUDGET_MS
            }

            if (waitOnlyRetry || hasRecentInstance || withinSpawnBudget) {
                val waitReason = when {
                    waitOnlyRetry -> "retry"
                    hasRecentInstance -> "recent-instance"
                    else -> "spawn-budget"
                }
                val window = awaitWindow(app, pids, requestGeneration, trace, waitReason)
                if (window == null) {
                    fail(
                        key,
                        requestGeneration,
                        trace,
                        "${app.displayName} is taking longer than usual",
                        AttemptFailureCode.NoWindow,
                        canRetry = true,
                        waitOnlyNextRetry = !waitOnlyRetry
                    )
                } else {
                    raiseWithRetries(app, key, window, requestGeneration, trace)
                }
                return
            }

            synchronized(lock) {
                if (generation != requestGeneration) return
                lastSpawnAtMs[key] = platform.nowMs()
            }
            val spawnStartedAt = platform.nowMs()
            val result = platform.spawn(app, trace.id)
            trace.spawnMs = elapsedSince(spawnStartedAt)
            logAttempt(trace, "phase=spawn durationMs=${trace.spawnMs} result=${spawnResultName(result)}")
            when (result) {
                is SpawnResult.Failed -> {
                    fail(
                        key,
                        requestGeneration,
                        trace,
                        result.message,
                        AttemptFailureCode.SpawnFailed,
                        canRetry = result.canRetry
                    )
                }
                is SpawnResult.Started -> {
                    trace.spawnPid = result.pid
                    pids += result.pid
                    trace.trackedPids = pids.toSet()
                    logAttempt(trace, "event=spawned pid=${result.pid}")
                    val window = awaitWindow(app, pids, requestGeneration, trace, "spawn")
                    if (window == null) {
                        fail(
                            key,
                            requestGeneration,
                            trace,
                            "${app.displayName} is taking longer than usual",
                            AttemptFailureCode.NoWindow,
                            canRetry = true,
                            waitOnlyNextRetry = !waitOnlyRetry
                        )
                    } else {
                        raiseWithRetries(app, key, window, requestGeneration, trace)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf(String::isNotBlank)
                ?: "Launch failed (${failure.javaClass.simpleName})"
            logAttemptWarning(trace, "launch failed", failure)
            fail(
                key,
                requestGeneration,
                trace,
                reason,
                AttemptFailureCode.Internal,
                canRetry = true
            )
        } finally {
            if (!trace.summaryLogged.get()) {
                completeAttempt(trace, AttemptFailureCode.Cancelled)
            }
        }
    }

    private suspend fun awaitWindow(
        app: FocusLauncherApp,
        initialPids: Set<Long>,
        requestGeneration: Long,
        trace: AttemptTrace,
        reason: String
    ): WindowRef? {
        logAttempt(
            trace,
            "phase=awaitWindow start reason=$reason pids=${formatPids(initialPids)}"
        )
        val startedAt = platform.nowMs()
        var pids = initialPids.toMutableSet()
        var lastProcessRefreshAt = startedAt

        while (true) {
            if (!isCurrent(app, requestGeneration)) return null
            val windows = platform.findWindows(pids, trace.id)
            observe(trace, pids, windows, "awaitWindow")
            if (windows.isNotEmpty()) {
                logAttempt(trace, "phase=awaitWindow result=window elapsedMs=${elapsedSince(startedAt)}")
                return windows.first()
            }

            val now = platform.nowMs()
            val elapsed = now - startedAt
            val remaining = WINDOW_WAIT_MS - elapsed
            if (remaining <= 0L) {
                logAttempt(trace, "phase=awaitWindow result=timeout elapsedMs=$elapsed")
                return null
            }

            if (now - lastProcessRefreshAt >= PROCESS_REFRESH_MS) {
                val instances = platform.findInstances(app)
                val refreshedPids = instances.mapTo(mutableSetOf()) { it.pid }
                observe(trace, refreshedPids, emptyList(), "awaitWindow-refresh")
                pids = refreshedPids
                lastProcessRefreshAt = now
            }
            delay(minOf(WINDOW_POLL_MS, remaining))
        }
    }

    private suspend fun raiseWithRetries(
        app: FocusLauncherApp,
        key: String,
        initialWindow: WindowRef,
        requestGeneration: Long,
        trace: AttemptTrace
    ) {
        if (trace.raiseStartedAtMs == null) {
            trace.raiseStartedAtMs = platform.nowMs()
            logAttempt(
                trace,
                "phase=raise start pid=${initialWindow.pid} hwnd=${initialWindow.handle}"
            )
        }
        var window = initialWindow
        try {
            repeat(MAX_RAISE_ATTEMPTS) { index ->
                if (!isCurrent(app, requestGeneration)) return
                val result = platform.raise(window, trace.id)
                if (result.tierResults.isNotEmpty()) {
                    trace.tierResults += result.tierResults
                } else {
                    trace.tierResults += "${result.tier}=${result.success}"
                }
                if (result.success) {
                    if (!isCurrent(app, requestGeneration)) return
                    runCatching { onRaised(app) }
                        .onFailure { logAttemptWarning(trace, "post-raise callback failed", it) }
                    if (setIdle(key, requestGeneration)) {
                        trace.raiseMs = elapsedSince(trace.raiseStartedAtMs ?: platform.nowMs())
                        completeAttempt(trace, null)
                    }
                    return
                }
                if (index < MAX_RAISE_ATTEMPTS - 1) {
                    delay(RAISE_RETRY_DELAY_MS)
                    val refreshed = platform.findWindows(setOf(window.pid), trace.id)
                    observe(trace, setOf(window.pid), refreshed, "raise-refresh")
                    if (refreshed.isNotEmpty()) window = refreshed.first()
                }
            }
        } finally {
            trace.raiseMs = elapsedSince(trace.raiseStartedAtMs ?: platform.nowMs())
        }
        fail(
            key,
            requestGeneration,
            trace,
            "Couldn't bring ${app.displayName} to the front",
            AttemptFailureCode.RaiseFailed,
            canRetry = true
        )
    }

    private fun fail(
        key: String,
        requestGeneration: Long,
        trace: AttemptTrace,
        reason: String,
        failureCode: AttemptFailureCode,
        canRetry: Boolean,
        waitOnlyNextRetry: Boolean = false
    ) {
        val failed = LaunchState.Failed(reason, canRetry)
        var failedCurrentAttempt = false
        synchronized(lock) {
            if (generation != requestGeneration) return
            if (waitOnlyNextRetry) waitOnlyRetryKeys += key
            _states.update { it + (key to failed) }
            failureResetJobs.remove(key)?.cancel()
            val resetJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(FAILURE_DISPLAY_MS)
                synchronized(lock) {
                    if (generation == requestGeneration && _states.value[key] == failed) {
                        _states.update { it + (key to LaunchState.Idle) }
                    }
                    failureResetJobs.remove(key)
                }
            }
            failureResetJobs[key] = resetJob
            resetJob.start()
            failedCurrentAttempt = true
        }
        if (failedCurrentAttempt) completeAttempt(trace, failureCode, reason)
    }

    private fun setIdle(key: String, requestGeneration: Long): Boolean =
        synchronized(lock) {
            if (generation != requestGeneration) return@synchronized false
            _states.update { it + (key to LaunchState.Idle) }
            waitOnlyRetryKeys.remove(key)
            true
        }

    private fun observe(
        trace: AttemptTrace,
        pids: Set<Long>,
        windows: List<WindowRef>,
        phase: String
    ) {
        val currentPids = pids.toSortedSet()
        val currentWindows = windows.toSet()
        val pidsChanged = trace.lastObservedPids != null && trace.lastObservedPids != currentPids
        val windowsChanged =
            trace.lastObservedWindows != null && trace.lastObservedWindows != currentWindows

        trace.trackedPids = currentPids
        trace.windowsSeen += currentWindows
        if (currentWindows.isNotEmpty() && trace.firstWindowMs == null) {
            trace.firstWindowMs = elapsedSince(trace.startedAtMs)
        }

        if (pidsChanged || windowsChanged) {
            if (trace.pollChangeLogs < MAX_POLL_CHANGE_LOGS) {
                logAttempt(
                    trace,
                    "event=poll-change phase=$phase elapsedMs=${elapsedSince(trace.startedAtMs)} " +
                        "pids=${formatPids(currentPids)} windows=${formatWindows(currentWindows)}"
                )
                trace.pollChangeLogs += 1
            } else {
                trace.suppressedPollChanges += 1
            }
        }

        trace.lastObservedPids = currentPids
        trace.lastObservedWindows = currentWindows
    }

    private fun completeAttempt(
        trace: AttemptTrace,
        failureCode: AttemptFailureCode?,
        detail: String? = null
    ) {
        if (!trace.summaryLogged.compareAndSet(false, true)) return

        val totalMs = elapsedSince(trace.startedAtMs)
        val spawnAlive = trace.spawnPid?.let { pid ->
            runCatching { platform.processIsAlive(pid) }.getOrNull()?.toString() ?: "unknown"
        } ?: "not-spawned"
        val foregroundLockCall =
            runCatching { platform.lastForegroundLockCall() }.getOrNull() ?: "unknown"
        val result = failureCode?.let { "Failed(code=${it.name})" } ?: "Opened"
        val summary = buildString {
            append("attemptId=").append(trace.id)
            append(" summary=true")
            append(" exe=").append(sanitizeLogField(trace.app.processName))
            append(" result=").append(result)
            append(" totalMs=").append(totalMs)
            append(" snapshotMs=").append(trace.snapshotMs ?: "unknown")
            append(" spawnMs=").append(trace.spawnMs ?: "not-started")
            append(" firstWindowMs=").append(trace.firstWindowMs ?: "not-seen")
            append(" raiseMs=").append(trace.raiseMs ?: "not-started")
            append(" spawnPid=").append(trace.spawnPid ?: "none")
            append(" spawnAlive=").append(spawnAlive)
            append(" trackedPids=").append(formatPids(trace.trackedPids))
            append(" windowsSeen=").append(formatWindows(trace.windowsSeen))
            append(" focusFlowElevated=").append(trace.focusFlowElevated ?: "unknown")
            append(" foregroundLockCall=").append(sanitizeLogField(foregroundLockCall))
            append(" tierResults=").append(formatLogList(trace.tierResults))
            append(" suppressedPollChanges=").append(trace.suppressedPollChanges)
            detail?.takeIf(String::isNotBlank)?.let {
                append(" detail=").append(sanitizeLogField(it).take(160))
            }
        }
        try {
            logInfo(summary)
        } finally {
            runCatching { platform.finishAttempt(trace.id) }
        }
    }

    private fun logAttempt(trace: AttemptTrace, message: String) {
        runCatching { logInfo("attemptId=${trace.id} $message") }
    }

    private fun logAttemptWarning(trace: AttemptTrace, message: String, failure: Throwable) {
        val detail = failure.message?.let(::sanitizeLogField)?.take(160).orEmpty()
        val suffix = if (detail.isBlank()) "" else " detail=$detail"
        EnforcementLog.warn(
            "Launcher",
            "attemptId=${trace.id} $message error=${failure.javaClass.simpleName}$suffix"
        )
    }

    private fun elapsedSince(startedAtMs: Long): Long =
        (platform.nowMs() - startedAtMs).coerceAtLeast(0L)

    private fun formatPids(pids: Collection<Long>): String =
        pids.sorted().joinToString(prefix = "[", postfix = "]")

    private fun spawnResultName(result: SpawnResult): String =
        when (result) {
            is SpawnResult.Started -> "started"
            is SpawnResult.Failed -> "failed"
        }

    private fun formatWindows(windows: Collection<WindowRef>): String =
        windows.sortedWith(compareBy(WindowRef::pid, WindowRef::handle))
            .joinToString(prefix = "[", postfix = "]") { "${it.pid}:${it.handle}" }

    private fun formatLogList(values: Collection<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { sanitizeLogField(it) }

    private fun sanitizeLogField(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ")

    private fun isCurrent(app: FocusLauncherApp, requestGeneration: Long): Boolean =
        generation == requestGeneration && isAllowed(app)

    private fun keyFor(processName: String): String = processName.lowercase(Locale.ROOT)
}
