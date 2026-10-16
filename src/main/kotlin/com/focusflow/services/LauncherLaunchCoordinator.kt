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
import kotlin.coroutines.coroutineContext

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

data class WindowRef(
    val handle: Long,
    val pid: Long,
    val ownerless: Boolean = true,
    val hasTitle: Boolean = true,
    val toolWindow: Boolean = false,
    val reasonableSize: Boolean = true,
    val zOrder: Int = 0
)

internal fun orderLauncherWindows(windows: List<WindowRef>): List<WindowRef> =
    windows.distinctBy(WindowRef::handle)
        .sortedWith(
            compareByDescending<WindowRef> { it.ownerless }
                .thenByDescending { it.hasTitle }
                .thenBy { it.toolWindow }
                .thenByDescending { it.reasonableSize }
                .thenBy { it.zOrder }
        )

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
    fun unsupportedReason(app: FocusLauncherApp): String? = null
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

        const val WINDOW_WAIT_MS = 60_000L
        const val CHAIN_GONE_GRACE_MS = 3_000L
        const val ATTACH_WINDOW_MS = 90_000L
        const val SPAWN_DEBOUNCE_MS = 2_000L
        const val WATCHDOG_MARGIN_MS = 5_000L
        const val WINDOW_SELECTION_SETTLE_MS = 300L
        const val WINDOW_POLL_MS = 150L
        const val PROCESS_REFRESH_MS = 500L
        const val FAILURE_DISPLAY_MS = 6_000L
        const val RAISE_RETRY_DELAY_MS = 150L
        const val MAX_RAISE_ATTEMPTS = 3

        private const val ATTEMPT_WATCHDOG_MS = WINDOW_WAIT_MS + WATCHDOG_MARGIN_MS

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
        SpawnDebounced,
        StoreAppUnsupported,
        ExitedEarly,
        NoWindow,
        RaiseFailed,
        Internal,
        Cancelled
    }

    private sealed interface AwaitWindowResult {
        data class Found(val window: WindowRef) : AwaitWindowResult
        data object ExitedEarly : AwaitWindowResult
        data object NoWindow : AwaitWindowResult
        data object Superseded : AwaitWindowResult
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
        var spawnCreationTimeMs: Long? = null
        var trackedPids: Set<Long> = emptySet()
        var trackedIdentities: Set<ProcessIdentity> = emptySet()
        var focusFlowElevated: Boolean? = null
        var lastObservedPids: Set<Long>? = null
        var lastObservedWindows: Set<WindowRef>? = null
        var pollChangeLogs = 0
        var suppressedPollChanges = 0
    }

    private val lock = Any()
    private val _states = MutableStateFlow<Map<String, LaunchState>>(emptyMap())
    val states: StateFlow<Map<String, LaunchState>> = _states.asStateFlow()

    private val activeAttempts = mutableMapOf<String, AttemptTrace>()
    private val jobs = mutableMapOf<String, Job>()
    private val watchdogJobs = mutableMapOf<String, Job>()
    private val failureResetJobs = mutableMapOf<String, Job>()
    private val failedAttemptIds = mutableMapOf<String, String>()
    private val lastSpawnAtMs = mutableMapOf<String, Long>()
    private val timedOutChains = mutableMapOf<String, Set<ProcessIdentity>>()

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
            failedAttemptIds.remove(key)
            val now = platform.nowMs()
            lastSpawnAtMs.entries.removeIf { now - it.value >= SPAWN_DEBOUNCE_MS }
            val launchStartedAt = platform.nowMs()
            val trace = AttemptTrace(
                id = "launcher-${attemptSequence.incrementAndGet()}",
                app = app,
                startedAtMs = launchStartedAt
            )
            activeAttempts[key] = trace
            _states.update { it + (key to LaunchState.Launching(launchStartedAt)) }
            logAttempt(trace, "event=begin")

            val job = scope.launch(start = CoroutineStart.LAZY) {
                executeRequest(app, key, trace)
            }
            jobs[key] = job
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException && !trace.summaryLogged.get()) {
                    finishCancelled(key, trace)
                }
                synchronized(lock) {
                    if (jobs[key] === job) jobs.remove(key)
                }
            }
            val watchdog = scope.launch(start = CoroutineStart.LAZY) {
                delay(ATTEMPT_WATCHDOG_MS)
                watchdogExpired(key, trace)
            }
            watchdogJobs[key] = watchdog
            watchdog.invokeOnCompletion {
                synchronized(lock) {
                    if (watchdogJobs[key] === watchdog) watchdogJobs.remove(key)
                }
            }
            watchdog.start()
            job.start()
        }

        return true
    }

    /** Stops all in-flight launch work when the kiosk session is lifted. */
    fun cancelAll() {
        val cancelled = mutableListOf<AttemptTrace>()
        val workers: List<Job>
        synchronized(lock) {
            cancelled += activeAttempts.values
            activeAttempts.clear()
            workers = jobs.values.toList() + watchdogJobs.values
            jobs.clear()
            watchdogJobs.clear()
            failureResetJobs.values.forEach(Job::cancel)
            failureResetJobs.clear()
            failedAttemptIds.clear()
            lastSpawnAtMs.clear()
            timedOutChains.clear()
            _states.value = emptyMap()
        }
        workers.forEach(Job::cancel)
        cancelled.forEach { completeAttempt(it, AttemptFailureCode.Cancelled) }
    }

    /** Cancels just one tile's launch attempt without killing an app it started. */
    fun cancel(app: FocusLauncherApp) {
        val key = keyFor(app.processName)
        val trace: AttemptTrace?
        val worker: Job?
        val watchdog: Job?
        synchronized(lock) {
            trace = activeAttempts.remove(key)
            worker = jobs.remove(key)
            watchdog = watchdogJobs.remove(key)
            failureResetJobs.remove(key)?.cancel()
            failedAttemptIds.remove(key)
            timedOutChains.remove(key)
            lastSpawnAtMs.remove(key)
            _states.update { it - key }
        }
        worker?.cancel()
        watchdog?.cancel()
        trace?.let { completeAttempt(it, AttemptFailureCode.Cancelled) }
    }

    private suspend fun executeRequest(
        app: FocusLauncherApp,
        key: String,
        trace: AttemptTrace
    ) {
        val tracker = ProcessLineageTracker(
            app = app,
            chainStartedAtMs = trace.startedAtMs,
            resolvePath = platform::resolveProcessPath
        )
        try {
            trace.focusFlowElevated = runCatching { platform.focusFlowElevated() }.getOrNull()
            if (!isCurrent(app, key, trace)) return

            platform.unsupportedReason(app)?.let { reason ->
                fail(
                    key,
                    trace,
                    reason,
                    AttemptFailureCode.StoreAppUnsupported,
                    canRetry = false
                )
                return
            }

            logAttempt(trace, "phase=snapshot start")
            val snapshotStartedAt = platform.nowMs()
            val (snapshot, pids, windows) = try {
                val foundSnapshot = platform.processSnapshotAsync(app)
                val foundPids = tracker.update(foundSnapshot)
                val foundWindows = orderLauncherWindows(
                    platform.findWindowsAsync(foundPids, trace.id)
                )
                Triple(foundSnapshot, foundPids, foundWindows)
            } finally {
                trace.snapshotMs = elapsedSince(snapshotStartedAt)
            }
            updateTraceProcesses(trace, tracker)
            observe(trace, pids, windows, "snapshot")
            logAttempt(
                trace,
                "phase=snapshot durationMs=${trace.snapshotMs} " +
                    "instances=${tracker.appInstances(snapshot).size} " +
                    "pids=${formatPids(pids)} windows=${windows.size}"
            )

            if (windows.isNotEmpty()) {
                handleWindowWait(
                    app,
                    key,
                    trace,
                    tracker,
                    awaitWindow(app, key, tracker, trace, "existing", windows.first())
                )
                return
            }

            val staleIdentities = liveTimedOutIdentities(key, tracker.identities())
            val hasRecentChain = tracker.liveProcesses().any { process ->
                val createdAt = process.creationTimeMs ?: return@any false
                val identity = ProcessIdentity(process.pid, createdAt)
                val age = platform.nowMs() - createdAt
                identity !in staleIdentities && age in 0..ATTACH_WINDOW_MS
            }

            if (hasRecentChain) {
                handleWindowWait(
                    app,
                    key,
                    trace,
                    tracker,
                    awaitWindow(app, key, tracker, trace, "attach")
                )
                return
            }

            val now = platform.nowMs()
            val lastSpawnAt = synchronized(lock) { lastSpawnAtMs[key] }
            if (lastSpawnAt != null && now - lastSpawnAt < SPAWN_DEBOUNCE_MS) {
                fail(
                    key,
                    trace,
                    "A launch just started. Try again in a moment.",
                    AttemptFailureCode.SpawnDebounced,
                    canRetry = true
                )
                return
            }

            val spawnStartedAt = platform.nowMs()
            val result = platform.spawnAsync(app, trace.id)
            trace.spawnMs = elapsedSince(spawnStartedAt)
            logAttempt(trace, "phase=spawn durationMs=${trace.spawnMs} result=${spawnResultName(result)}")
            when (result) {
                is SpawnResult.Failed -> {
                    fail(
                        key,
                        trace,
                        result.message,
                        AttemptFailureCode.SpawnFailed,
                        canRetry = result.canRetry
                    )
                }
                is SpawnResult.Started -> {
                    trace.spawnPid = result.pid
                    synchronized(lock) {
                        if (activeAttempts[key] !== trace) return
                        lastSpawnAtMs[key] = platform.nowMs()
                    }
                    tracker.addSpawnPid(result.pid)
                    updateTraceProcesses(trace, tracker)
                    logAttempt(trace, "event=spawned pid=${result.pid}")
                    handleWindowWait(
                        app,
                        key,
                        trace,
                        tracker,
                        awaitWindow(app, key, tracker, trace, "spawn")
                    )
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
                trace,
                reason,
                AttemptFailureCode.Internal,
                canRetry = true
            )
        } finally {
            if (!trace.summaryLogged.get()) {
                finishCancelled(key, trace)
            }
        }
    }

    private suspend fun handleWindowWait(
        app: FocusLauncherApp,
        key: String,
        trace: AttemptTrace,
        tracker: ProcessLineageTracker,
        result: AwaitWindowResult
    ) {
        when (result) {
            is AwaitWindowResult.Found ->
                raiseWithRetries(app, key, result.window, trace)
            AwaitWindowResult.ExitedEarly ->
                fail(
                    key,
                    trace,
                    "${app.displayName} closed before its window appeared.",
                    AttemptFailureCode.ExitedEarly,
                    canRetry = true
                )
            AwaitWindowResult.NoWindow ->
                fail(
                    key,
                    trace,
                    "${app.displayName} did not show a window before the launch timed out.",
                    AttemptFailureCode.NoWindow,
                    canRetry = true,
                    staleIdentities = tracker.identities()
                )
            AwaitWindowResult.Superseded -> Unit
        }
    }

    private suspend fun awaitWindow(
        app: FocusLauncherApp,
        key: String,
        tracker: ProcessLineageTracker,
        trace: AttemptTrace,
        reason: String,
        initialCandidate: WindowRef? = null
    ): AwaitWindowResult {
        logAttempt(
            trace,
            "phase=awaitWindow start reason=$reason pids=${formatPids(tracker.livePids())}"
        )
        val startedAt = platform.nowMs()
        var lastProcessRefreshAt = startedAt
        var candidate = initialCandidate
        var candidateSince = initialCandidate?.let { startedAt }
        var chainGoneSince: Long? = null

        while (true) {
            if (!isCurrent(app, key, trace)) {
                return AwaitWindowResult.Superseded
            }

            val now = platform.nowMs()
            if (now - lastProcessRefreshAt >= PROCESS_REFRESH_MS) {
                val snapshot = platform.processSnapshotAsync(app)
                tracker.update(snapshot)
                updateTraceProcesses(trace, tracker)
                lastProcessRefreshAt = now
            }

            val pids = tracker.livePids()
            val windows = orderLauncherWindows(platform.findWindowsAsync(pids, trace.id))
            observe(trace, pids, windows, "awaitWindow")
            val best = windows.firstOrNull()
            if (best == null) {
                candidate = null
                candidateSince = null
            } else if (candidate != best) {
                candidate = best
                candidateSince = now
            } else if (candidateSince != null &&
                now - candidateSince >= WINDOW_SELECTION_SETTLE_MS
            ) {
                logAttempt(trace, "phase=awaitWindow result=window elapsedMs=${elapsedSince(startedAt)}")
                return AwaitWindowResult.Found(best)
            }

            val elapsed = now - startedAt
            val remaining = WINDOW_WAIT_MS - elapsed
            if (remaining <= 0L) {
                logAttempt(trace, "phase=awaitWindow result=timeout elapsedMs=$elapsed")
                return AwaitWindowResult.NoWindow
            }

            if (pids.isEmpty()) {
                if (chainGoneSince == null) {
                    chainGoneSince = now
                } else if (now - chainGoneSince >= CHAIN_GONE_GRACE_MS) {
                    logAttempt(
                        trace,
                        "phase=awaitWindow result=exitedEarly elapsedMs=${elapsedSince(startedAt)}"
                    )
                    return AwaitWindowResult.ExitedEarly
                }
            } else {
                chainGoneSince = null
            }
            delay(minOf(WINDOW_POLL_MS, remaining))
        }
    }

    private suspend fun raiseWithRetries(
        app: FocusLauncherApp,
        key: String,
        initialWindow: WindowRef,
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
                if (!isCurrent(app, key, trace)) return
                val result = platform.raise(window, trace.id)
                if (result.tierResults.isNotEmpty()) {
                    trace.tierResults += result.tierResults
                } else {
                    trace.tierResults += "${result.tier}=${result.success}"
                }
                if (result.success) {
                    if (finishSuccess(key, trace)) {
                        trace.raiseMs = elapsedSince(trace.raiseStartedAtMs ?: platform.nowMs())
                        runCatching { onRaised(app) }
                            .onFailure { logAttemptWarning(trace, "post-raise callback failed", it) }
                        completeAttempt(trace, null)
                    }
                    return
                }
                if (index < MAX_RAISE_ATTEMPTS - 1) {
                    delay(RAISE_RETRY_DELAY_MS)
                    val refreshed = orderLauncherWindows(
                        platform.findWindowsAsync(setOf(window.pid), trace.id)
                    )
                    observe(trace, setOf(window.pid), refreshed, "raise-refresh")
                    refreshed.firstOrNull()?.let { window = it }
                }
            }
        } finally {
            trace.raiseMs = elapsedSince(trace.raiseStartedAtMs ?: platform.nowMs())
        }
        fail(
            key,
            trace,
            "Couldn't bring ${app.displayName} to the front",
            AttemptFailureCode.RaiseFailed,
            canRetry = true
        )
    }

    private fun fail(
        key: String,
        trace: AttemptTrace,
        reason: String,
        failureCode: AttemptFailureCode,
        canRetry: Boolean,
        staleIdentities: Set<ProcessIdentity> = emptySet()
    ) {
        val failed = LaunchState.Failed(reason, canRetry)
        var accepted = false
        synchronized(lock) {
            if (activeAttempts[key] !== trace) return
            activeAttempts.remove(key)
            jobs.remove(key)
            watchdogJobs.remove(key)?.cancel()
            if (failureCode == AttemptFailureCode.NoWindow && staleIdentities.isNotEmpty()) {
                timedOutChains[key] = staleIdentities
            }
            _states.update { it + (key to failed) }
            failureResetJobs.remove(key)?.cancel()
            failedAttemptIds[key] = trace.id
            val resetJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(FAILURE_DISPLAY_MS)
                synchronized(lock) {
                    if (failedAttemptIds[key] == trace.id && _states.value[key] == failed) {
                        _states.update { it + (key to LaunchState.Idle) }
                        failedAttemptIds.remove(key)
                    }
                    if (failureResetJobs[key] === coroutineContext[Job]) {
                        failureResetJobs.remove(key)
                    }
                }
            }
            failureResetJobs[key] = resetJob
            resetJob.start()
            accepted = true
        }
        if (accepted) completeAttempt(trace, failureCode, reason)
    }

    private fun finishSuccess(key: String, trace: AttemptTrace): Boolean =
        synchronized(lock) {
            if (activeAttempts[key] !== trace) return@synchronized false
            activeAttempts.remove(key)
            jobs.remove(key)
            watchdogJobs.remove(key)?.cancel()
            failureResetJobs.remove(key)?.cancel()
            failedAttemptIds.remove(key)
            timedOutChains.remove(key)
            _states.update { it + (key to LaunchState.Idle) }
            true
        }

    private fun finishCancelled(key: String, trace: AttemptTrace) {
        val finished = synchronized(lock) {
            if (activeAttempts[key] !== trace) return@synchronized false
            activeAttempts.remove(key)
            jobs.remove(key)
            watchdogJobs.remove(key)?.cancel()
            failureResetJobs.remove(key)?.cancel()
            failedAttemptIds.remove(key)
            _states.update { it + (key to LaunchState.Idle) }
            true
        }
        if (finished) completeAttempt(trace, AttemptFailureCode.Cancelled)
    }

    private fun watchdogExpired(key: String, trace: AttemptTrace) {
        val failed = LaunchState.Failed("Launch timed out. Tap to retry.", canRetry = true)
        val worker: Job?
        val accepted = synchronized(lock) {
            if (activeAttempts[key] !== trace) return
            activeAttempts.remove(key)
            worker = jobs.remove(key)
            watchdogJobs.remove(key)
            if (trace.trackedIdentities.isNotEmpty()) {
                timedOutChains[key] = trace.trackedIdentities
            }
            _states.update { it + (key to failed) }
            failureResetJobs.remove(key)?.cancel()
            failedAttemptIds[key] = trace.id
            val resetJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(FAILURE_DISPLAY_MS)
                synchronized(lock) {
                    if (failedAttemptIds[key] == trace.id && _states.value[key] == failed) {
                        _states.update { it + (key to LaunchState.Idle) }
                        failedAttemptIds.remove(key)
                    }
                    if (failureResetJobs[key] === coroutineContext[Job]) {
                        failureResetJobs.remove(key)
                    }
                }
            }
            failureResetJobs[key] = resetJob
            resetJob.start()
            true
        }
        if (accepted) {
            worker?.cancel()
            completeAttempt(trace, AttemptFailureCode.NoWindow, "watchdog-timeout")
        }
    }

    private fun updateTraceProcesses(
        trace: AttemptTrace,
        tracker: ProcessLineageTracker
    ) {
        trace.trackedPids = tracker.livePids()
        trace.trackedIdentities = tracker.identities()
        trace.spawnCreationTimeMs = trace.spawnPid?.let { tracker.process(it)?.creationTimeMs }
    }

    private fun liveTimedOutIdentities(
        key: String,
        current: Set<ProcessIdentity>
    ): Set<ProcessIdentity> = synchronized(lock) {
        val retained = timedOutChains[key].orEmpty().intersect(current)
        if (retained.isEmpty()) timedOutChains.remove(key) else timedOutChains[key] = retained
        retained
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
            runCatching {
                platform.processIsAlive(pid, trace.spawnCreationTimeMs)
            }.getOrNull()?.toString() ?: "unknown"
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

    private fun isCurrent(app: FocusLauncherApp, key: String, trace: AttemptTrace): Boolean =
        synchronized(lock) { activeAttempts[key] === trace } && isAllowed(app)

    private fun keyFor(processName: String): String = processName.lowercase(Locale.ROOT)
}
