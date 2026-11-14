package com.focusflow.services

import com.focusflow.enforcement.EnforcementLog
import com.focusflow.enforcement.ProcessMonitor
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Launching(val startedAtMs: Long) : LaunchState
    data class Failed(
        val code: LaunchFailureCode,
        val reason: String,
        val canRetry: Boolean
    ) : LaunchState
}

enum class LaunchFailureCode {
    NotAllowed,
    BlockedByRule,
    NotFound,
    NeedsElevation,
    AccessDenied,
    StoreAppUnsupported,
    UnsupportedCandidate,
    SpawnFailed,
    ExitedEarly,
    NoWindow,
    RaiseFailed,
    Cancelled,
    Internal
}

enum class ExecutablePreflightResult {
    Supported,
    NotFound,
    UnsupportedCandidate
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
    val zOrder: Int = 0,
    /** True when the window is minimised: still restorable, but not on screen. */
    val isMinimized: Boolean = false
)

internal fun orderLauncherWindows(windows: List<WindowRef>): List<WindowRef> =
    windows.distinctBy(WindowRef::handle)
        .sortedWith(
            compareByDescending<WindowRef> { it.ownerless }
                .thenByDescending { it.hasTitle }
                .thenBy { it.toolWindow }
                .thenBy { it.isMinimized }
                .thenByDescending { it.reasonableSize }
                .thenBy { it.zOrder }
        )

/**
 * True only while an app that FocusFlow itself started (at [ownLaunchStartedAtMs])
 * may still be bringing up its first window. Background or helper processes that
 * merely share the app's executable name do not count, because they can be created
 * continuously (Edge) without any window ever being on its way.
 */
internal fun hasPendingOwnLaunch(
    processes: List<Pair<Long, Long?>>,
    ownLaunchStartedAtMs: Long?,
    nowMs: Long,
    staleIdentities: Set<ProcessIdentity>
): Boolean {
    val launchedAt = ownLaunchStartedAtMs ?: return false
    return processes.any { (pid, createdAt) ->
        if (createdAt == null) return@any false
        val age = nowMs - createdAt
        ProcessIdentity(pid, createdAt) !in staleIdentities &&
            age in 0..LauncherLaunchCoordinator.ATTACH_WINDOW_MS &&
            createdAt >= launchedAt - LauncherLaunchCoordinator.ATTACH_SPAWN_SLACK_MS
    }
}

/** Whether a process runs with administrator rights; [Unknown] when it can't be proven. */
enum class ProcessElevation { Elevated, NotElevated, Unknown }

sealed interface SpawnResult {
    /** [method] names the launch strategy, so it can be remembered once it is verified. */
    data class Started(
        val pid: Long,
        val creationTimeMs: Long? = null,
        val method: String? = null
    ) : SpawnResult

    /**
     * The app was handed to the running Windows shell, which starts it with the
     * user's normal rights. No app PID is known, so the coordinator finds the new
     * process by name/path and gives it a longer start-up grace period.
     */
    data class Handoff(val method: String) : SpawnResult

    data class Failed(val message: String, val canRetry: Boolean = true) : SpawnResult
}

data class RaiseResult(
    val success: Boolean,
    val tier: String,
    val tierResults: List<String> = emptyList(),
    val foregroundPid: Long? = null,
    val foregroundRootOwnerHandle: Long? = null
)

/**
 * OS operations used by the launch state machine. The coordinator calls these
 * only from its worker scope, and fake implementations keep the state machine
 * deterministic in tests.
 */
interface LaunchPlatform {
    fun nowMs(): Long
    fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L
    fun findInstances(app: FocusLauncherApp): List<RunningInstance>
    fun findWindows(pids: Set<Long>): List<WindowRef>
    fun unsupportedReason(app: FocusLauncherApp): String? = null
    fun validateExecutable(app: FocusLauncherApp): ExecutablePreflightResult {
        val path = app.exePath?.trim()?.takeIf(String::isNotEmpty)
            ?: return ExecutablePreflightResult.NotFound
        val basename = path.replace('\\', '/').substringAfterLast('/')
        val basenameWithoutExtension = basename.substringBeforeLast('.', basename)
        val suspiciousCandidate = Regex(
            "(?i)(^|[._ -])(uninstall(?:er)?|unins\\w*|updater?|installer|setup)([._ -]|$)"
        ).containsMatchIn(basenameWithoutExtension)
        if (suspiciousCandidate) return ExecutablePreflightResult.UnsupportedCandidate

        val isWindowsHost = System.getProperty("os.name")
            ?.startsWith("Windows", ignoreCase = true) == true
        if (isWindowsHost) {
            val extension = basename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in setOf("exe", "com")) {
                return ExecutablePreflightResult.UnsupportedCandidate
            }
            val executable = File(path)
            if (!executable.isFile || !executable.canRead()) {
                return ExecutablePreflightResult.NotFound
            }
        }
        return ExecutablePreflightResult.Supported
    }

    suspend fun validateExecutableAsync(app: FocusLauncherApp): ExecutablePreflightResult =
        withContext(Dispatchers.IO) { validateExecutable(app) }

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

    /**
     * Elevation of exactly the process identified by [pid] and [creationTimeMs]. A
     * process that is gone, or whose PID now belongs to a different process, is
     * [ProcessElevation.Unknown], never a guess.
     */
    fun processElevation(pid: Long, creationTimeMs: Long): ProcessElevation =
        ProcessElevation.Unknown

    /**
     * Ends exactly the process identified by [pid] and [creationTimeMs] (one the
     * launcher itself started with admin rights). Returns false, and does nothing,
     * if that identity no longer matches a live process.
     */
    fun terminateProcess(pid: Long, creationTimeMs: Long): Boolean = false

    /**
     * Called once a launch method has produced a window that was raised and, for a
     * shell hand-off, whose processes were proven to run with normal rights. Only
     * then may the method be remembered as the one to try first.
     */
    fun launchVerified(method: String) {}

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
    private val logInfo: (String) -> Unit = { EnforcementLog.info("Launcher", it) },
    private val ruleBlockReason: (FocusLauncherApp) -> String? = {
        ProcessMonitor.launcherBlockReason(it.processName)
    }
) {
    companion object {
        private val attemptSequence = java.util.concurrent.atomic.AtomicLong()
        private const val MAX_POLL_CHANGE_LOGS = 1

        const val WINDOW_WAIT_MS = 60_000L
        const val CHAIN_GONE_GRACE_MS = 3_000L
        const val HANDOFF_GONE_GRACE_MS = 20_000L
        const val UNKNOWN_ELEVATION_GRACE_MS = 5_000L
        const val ATTACH_SPAWN_SLACK_MS = 500L
        const val ATTACH_WINDOW_MS = 90_000L
        const val SPAWN_DEBOUNCE_MS = 2_000L
        const val WATCHDOG_MARGIN_MS = 5_000L
        const val WINDOW_SELECTION_SETTLE_MS = 300L
        const val WINDOW_POLL_MS = 150L
        const val PROCESS_REFRESH_MS = 500L
        const val FAILURE_DISPLAY_MS = 6_000L
        const val PREFLIGHT_CHECK_TIMEOUT_MS = 500L
        const val RAISE_RETRY_DELAY_MS = 100L
        const val MAX_RAISE_ATTEMPTS = 3
        const val RAISE_PHASE_BUDGET_MS = 2_500L

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
        NotAllowed,
        BlockedByRule,
        NotFound,
        NeedsElevation,
        AccessDenied,
        UnsupportedCandidate,
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
        /** Launch strategy that produced this attempt's app (null: nothing was started). */
        var launchMethod: String? = null
        var handoffMethod: String? = null
        var handoffStartedAtMs: Long? = null
        val unelevatedIdentities = mutableSetOf<ProcessIdentity>()
        val elevationUnknownSince = mutableMapOf<ProcessIdentity, Long>()
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
    private val lastSpawnAtMonotonicMs = mutableMapOf<String, Long>()
    private val lastSpawnAtWallMs = mutableMapOf<String, Long>()

    /** Shell hand-off still in progress per app: method and when it began. */
    private val lastHandoff = mutableMapOf<String, Pair<String, Long>>()
    private val timedOutChains = mutableMapOf<String, Set<ProcessIdentity>>()

    /**
     * Marks the tile Launching before scheduling any OS work, so a second click
     * in the same UI frame is rejected instead of starting another process.
     */
    fun request(app: FocusLauncherApp): Boolean {
        val key = app.processName.lowercase(Locale.ROOT)
        synchronized(lock) {
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
            val now = platform.monotonicNowMs()
            lastSpawnAtMonotonicMs.entries.removeIf {
                now >= it.value && now - it.value >= SPAWN_DEBOUNCE_MS
            }
            val wallNow = platform.nowMs()
            lastSpawnAtWallMs.entries.removeIf { wallNow - it.value > ATTACH_WINDOW_MS }
            lastHandoff.keys.removeIf { it !in lastSpawnAtWallMs }
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
            lastSpawnAtMonotonicMs.clear()
            lastSpawnAtWallMs.clear()
            lastHandoff.clear()
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
            lastSpawnAtMonotonicMs.remove(key)
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

            if (!isAllowed(app)) {
                fail(
                    key,
                    trace,
                    "This app is no longer available in the current Focus Launcher session.",
                    AttemptFailureCode.NotAllowed,
                    canRetry = false
                )
                return
            }
            ruleBlockReason(app)?.let { reason ->
                fail(
                    key,
                    trace,
                    "Blocked by FocusFlow: $reason.",
                    AttemptFailureCode.BlockedByRule,
                    canRetry = true
                )
                return
            }

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
                if (!isCurrent(app, key, trace)) return
                val foundPids = tracker.update(foundSnapshot)
                val foundWindows = orderLauncherWindows(
                    platform.findWindowsAsync(foundPids, trace.id)
                )
                if (!isCurrent(app, key, trace)) return
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

            // Only wait for a window when FocusFlow itself started this app a moment
            // ago and its process chain is still coming up. A background or helper
            // process (Edge keeps creating them) is not evidence that a window is
            // on its way, so it must not block a fresh start.
            val staleIdentities = liveTimedOutIdentities(key, tracker.identities())
            val ownLaunchSince = synchronized(lock) { lastSpawnAtWallMs[key] }
            val hasRecentChain = hasPendingOwnLaunch(
                processes = tracker.liveProcesses().map { it.pid to it.creationTimeMs },
                ownLaunchStartedAtMs = ownLaunchSince,
                nowMs = platform.nowMs(),
                staleIdentities = staleIdentities
            )

            if (hasRecentChain) {
                // An earlier attempt may have handed this app to the shell; its
                // processes still need the same normal-rights check.
                synchronized(lock) { lastHandoff[key] }?.let { (method, startedAt) ->
                    trace.launchMethod = method
                    trace.handoffMethod = method
                    trace.handoffStartedAtMs = startedAt
                }
                handleWindowWait(
                    app,
                    key,
                    trace,
                    tracker,
                    awaitWindow(app, key, tracker, trace, "attach")
                )
                return
            }

            val now = platform.monotonicNowMs()
            val lastSpawnAt = synchronized(lock) { lastSpawnAtMonotonicMs[key] }
            if (lastSpawnAt != null &&
                now >= lastSpawnAt &&
                now - lastSpawnAt < SPAWN_DEBOUNCE_MS
            ) {
                fail(
                    key,
                    trace,
                    "A launch just started. Try again in a moment.",
                    AttemptFailureCode.SpawnDebounced,
                    canRetry = true
                )
                return
            }

            val executableCheck = withTimeoutOrNull(PREFLIGHT_CHECK_TIMEOUT_MS) {
                platform.validateExecutableAsync(app)
            }
            if (!isCurrent(app, key, trace)) return
            when (executableCheck) {
                null -> {
                    fail(
                        key,
                        trace,
                        "FocusFlow couldn't verify this app path quickly enough. No launch was started.",
                        AttemptFailureCode.Internal,
                        canRetry = true
                    )
                    return
                }
                ExecutablePreflightResult.NotFound -> {
                    fail(
                        key,
                        trace,
                        "The app executable path is missing or unreadable.",
                        AttemptFailureCode.NotFound,
                        canRetry = false
                    )
                    return
                }
                ExecutablePreflightResult.UnsupportedCandidate -> {
                    fail(
                        key,
                        trace,
                        "This app candidate is not supported by Focus Launcher.",
                        AttemptFailureCode.UnsupportedCandidate,
                        canRetry = false
                    )
                    return
                }
                ExecutablePreflightResult.Supported -> Unit
            }

            if (!isAllowed(app)) {
                fail(
                    key,
                    trace,
                    "This app is no longer available in the current Focus Launcher session.",
                    AttemptFailureCode.NotAllowed,
                    canRetry = false
                )
                return
            }
            ruleBlockReason(app)?.let { reason ->
                fail(
                    key,
                    trace,
                    "Blocked by FocusFlow: $reason.",
                    AttemptFailureCode.BlockedByRule,
                    canRetry = true
                )
                return
            }

            val spawnStartedAt = platform.nowMs()
            val result = platform.spawnAsync(app, trace.id)
            if (!isCurrent(app, key, trace)) return
            trace.spawnMs = elapsedSince(spawnStartedAt)
            logAttempt(trace, "phase=spawn durationMs=${trace.spawnMs} result=${spawnResultName(result)}")
            when (result) {
                is SpawnResult.Failed -> {
                    val spawnFailure = classifySpawnFailure(result.message)
                    logAttempt(
                        trace,
                        "phase=spawn result=failed rawOsError=${sanitizeLogField(result.message).take(160)}"
                    )
                    logAttemptWarning(
                        trace,
                        "spawn failed; raw OS error retained in the log",
                        IllegalStateException(result.message)
                    )
                    fail(
                        key,
                        trace,
                        spawnFailure.reason,
                        spawnFailure.code,
                        canRetry = result.canRetry
                    )
                }
                is SpawnResult.Started -> {
                    trace.spawnPid = result.pid
                    trace.spawnCreationTimeMs = result.creationTimeMs
                    trace.launchMethod = result.method
                    synchronized(lock) {
                        if (activeAttempts[key] !== trace) return
                        lastSpawnAtMonotonicMs[key] = platform.monotonicNowMs()
                        lastSpawnAtWallMs[key] = spawnStartedAt
                    }
                    tracker.addSpawnPid(result.pid, result.creationTimeMs)
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
                is SpawnResult.Handoff -> {
                    trace.launchMethod = result.method
                    trace.handoffMethod = result.method
                    trace.handoffStartedAtMs = spawnStartedAt
                    synchronized(lock) {
                        if (activeAttempts[key] !== trace) return
                        lastSpawnAtMonotonicMs[key] = platform.monotonicNowMs()
                        lastSpawnAtWallMs[key] = spawnStartedAt
                        lastHandoff[key] = result.method to spawnStartedAt
                    }
                    logAttempt(trace, "event=spawned handoff=${result.method}")
                    handleWindowWait(
                        app,
                        key,
                        trace,
                        tracker,
                        awaitWindow(
                            app,
                            key,
                            tracker,
                            trace,
                            "spawn-handoff",
                            chainGoneGraceMs = HANDOFF_GONE_GRACE_MS,
                            handoffStartedAtMs = spawnStartedAt
                        )
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
                raiseWithRetries(app, key, result.window, tracker, trace)
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

    private enum class HandoffVerdict {
        /** Nothing judged here is elevated, and the window owner is proven safe. */
        Safe,

        /** The window owner's elevation can't be read yet; keep waiting, don't raise. */
        Pending,

        /** The attempt was failed (and any elevated process we started closed). */
        Failed
    }

    /**
     * An app started through the Windows shell must run with the user's normal
     * rights, and nothing may be brought to the front until that is proven.
     *
     * Judged are only processes created after this hand-off began, identified by
     * PID *and* creation time, so a recycled PID, an instance the person already had
     * running (possibly as administrator on purpose) and unrelated processes are
     * never touched. If any such process is elevated, those processes are closed
     * (identity re-checked at the moment of closing) and the attempt fails.
     *
     * If the process that owns the window about to be raised cannot be read
     * ([ProcessElevation.Unknown]) it is not accepted: the verdict stays Pending for
     * [UNKNOWN_ELEVATION_GRACE_MS], then the attempt fails without raising or closing
     * anything, because "couldn't check" is not evidence of elevation.
     *
     * [windowPid] is the owner of the window about to be raised; null for the early
     * periodic check, which only looks for elevated processes.
     */
    private fun evaluateHandoff(
        app: FocusLauncherApp,
        key: String,
        trace: AttemptTrace,
        tracker: ProcessLineageTracker,
        windowPid: Long?
    ): HandoffVerdict {
        val handoffStartedAt = trace.handoffStartedAtMs ?: return HandoffVerdict.Safe
        val now = platform.nowMs()
        val ours = tracker.liveProcesses().mapNotNull { process ->
            val createdAt = process.creationTimeMs ?: return@mapNotNull null
            if (createdAt < handoffStartedAt - ATTACH_SPAWN_SLACK_MS) return@mapNotNull null
            ProcessIdentity(process.pid, createdAt)
        }

        val elevated = mutableListOf<ProcessIdentity>()
        for (identity in ours) {
            if (identity in trace.unelevatedIdentities) continue
            val state = runCatching {
                platform.processElevation(identity.pid, identity.creationTimeMs)
            }.getOrDefault(ProcessElevation.Unknown)
            when (state) {
                ProcessElevation.Elevated -> elevated += identity
                ProcessElevation.NotElevated -> {
                    trace.unelevatedIdentities += identity
                    trace.elevationUnknownSince.remove(identity)
                }
                ProcessElevation.Unknown ->
                    if (trace.elevationUnknownSince.putIfAbsent(identity, now) == null) {
                        logAttempt(trace, "event=handoff-elevation-unknown pid=${identity.pid}")
                    }
            }
        }

        if (elevated.isNotEmpty()) {
            val closed = elevated.filter { identity ->
                runCatching {
                    platform.terminateProcess(identity.pid, identity.creationTimeMs)
                }.getOrDefault(false)
            }
            logAttempt(
                trace,
                "event=handoff-elevated-rejected method=${trace.handoffMethod} " +
                    "pids=${formatPids(elevated.map { it.pid })} closed=${formatPids(closed.map { it.pid })}"
            )
            fail(
                key,
                trace,
                "Windows started ${app.displayName} with administrator rights, " +
                    "which Focus Launcher doesn't allow.",
                AttemptFailureCode.NeedsElevation,
                canRetry = false
            )
            return HandoffVerdict.Failed
        }

        if (windowPid == null) return HandoffVerdict.Safe
        val owner = ours.firstOrNull { it.pid == windowPid } ?: return HandoffVerdict.Safe
        if (owner in trace.unelevatedIdentities) return HandoffVerdict.Safe

        val unknownSince = trace.elevationUnknownSince[owner] ?: now
        if (now - unknownSince < UNKNOWN_ELEVATION_GRACE_MS) return HandoffVerdict.Pending
        logAttempt(trace, "event=handoff-elevation-unverifiable pid=${owner.pid}")
        fail(
            key,
            trace,
            "Couldn't confirm that ${app.displayName} is running with normal rights, " +
                "so it was not opened.",
            AttemptFailureCode.Internal,
            canRetry = true
        )
        return HandoffVerdict.Failed
    }

    private suspend fun awaitWindow(
        app: FocusLauncherApp,
        key: String,
        tracker: ProcessLineageTracker,
        trace: AttemptTrace,
        reason: String,
        initialCandidate: WindowRef? = null,
        chainGoneGraceMs: Long = CHAIN_GONE_GRACE_MS,
        handoffStartedAtMs: Long? = null
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
                if (!isCurrent(app, key, trace)) return AwaitWindowResult.Superseded
                tracker.update(snapshot)
                updateTraceProcesses(trace, tracker)
                lastProcessRefreshAt = now
                // Check hand-off processes as soon as they exist, not only once a
                // window shows up, so a windowless elevated process is caught too.
                if (evaluateHandoff(app, key, trace, tracker, null) == HandoffVerdict.Failed) {
                    return AwaitWindowResult.Superseded
                }
            }

            val pids = tracker.livePids()
            val windows = orderLauncherWindows(platform.findWindowsAsync(pids, trace.id))
            if (!isCurrent(app, key, trace)) return AwaitWindowResult.Superseded
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
                when (evaluateHandoff(app, key, trace, tracker, best.pid)) {
                    HandoffVerdict.Safe -> {
                        logAttempt(
                            trace,
                            "phase=awaitWindow result=window elapsedMs=${elapsedSince(startedAt)}"
                        )
                        return AwaitWindowResult.Found(best)
                    }
                    HandoffVerdict.Failed -> return AwaitWindowResult.Superseded
                    HandoffVerdict.Pending -> Unit
                }
            }

            val elapsed = now - startedAt
            val remaining = WINDOW_WAIT_MS - elapsed
            if (remaining <= 0L) {
                logAttempt(trace, "phase=awaitWindow result=timeout elapsedMs=$elapsed")
                return AwaitWindowResult.NoWindow
            }

            // After a shell hand-off, older background processes of the app say nothing
            // about whether the hand-off worked. Only a process created since then does.
            val chainAlive = if (handoffStartedAtMs == null) {
                pids.isNotEmpty()
            } else {
                tracker.liveProcesses().any { process ->
                    val createdAt = process.creationTimeMs ?: return@any false
                    createdAt >= handoffStartedAtMs - ATTACH_SPAWN_SLACK_MS
                }
            }
            if (!chainAlive) {
                if (chainGoneSince == null) {
                    chainGoneSince = now
                } else if (now - chainGoneSince >= chainGoneGraceMs) {
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
        tracker: ProcessLineageTracker,
        trace: AttemptTrace
    ) {
        if (trace.raiseStartedAtMs == null) {
            trace.raiseStartedAtMs = platform.nowMs()
            logAttempt(
                trace,
                "phase=raise start pid=${initialWindow.pid} hwnd=${initialWindow.handle}"
            )
        }
        val raiseStartedAtMonotonicMs = platform.monotonicNowMs()
        var sawCandidate = false
        var failureCode = AttemptFailureCode.RaiseFailed
        try {
            for (index in 0 until MAX_RAISE_ATTEMPTS) {
                if (!isCurrent(app, key, trace)) return

                val remainingBeforeSnapshot = RAISE_PHASE_BUDGET_MS -
                    (platform.monotonicNowMs() - raiseStartedAtMonotonicMs)
                if (remainingBeforeSnapshot <= 0L) break
                val snapshot = withTimeoutOrNull(remainingBeforeSnapshot) {
                    platform.processSnapshotAsync(app)
                } ?: break
                if (!isCurrent(app, key, trace)) return
                tracker.update(snapshot)
                updateTraceProcesses(trace, tracker)

                val trackedPids = tracker.livePids()
                val remainingBeforeWindows = RAISE_PHASE_BUDGET_MS -
                    (platform.monotonicNowMs() - raiseStartedAtMonotonicMs)
                if (remainingBeforeWindows <= 0L) break
                val windows = withTimeoutOrNull(remainingBeforeWindows) {
                    orderLauncherWindows(platform.findWindowsAsync(trackedPids, trace.id))
                } ?: break
                if (!isCurrent(app, key, trace)) return
                observe(trace, trackedPids, windows, "raise-reselect")
                val window = windows.firstOrNull()
                if (window == null) {
                    failureCode = AttemptFailureCode.NoWindow
                    trace.tierResults += "raise-attempt-${index + 1}=no-window"
                    if (index < MAX_RAISE_ATTEMPTS - 1) {
                        val remaining = RAISE_PHASE_BUDGET_MS -
                            (platform.monotonicNowMs() - raiseStartedAtMonotonicMs)
                        if (remaining > 0L) delay(minOf(RAISE_RETRY_DELAY_MS, remaining))
                    }
                    continue
                }
                if (evaluateHandoff(app, key, trace, tracker, window.pid) != HandoffVerdict.Safe) {
                    // Failed: already reported. Pending: not proven yet, so never raised.
                    if (!isCurrent(app, key, trace)) return
                    failureCode = AttemptFailureCode.RaiseFailed
                    trace.tierResults += "raise-attempt-${index + 1}=elevation-unverified"
                    if (index < MAX_RAISE_ATTEMPTS - 1) delay(RAISE_RETRY_DELAY_MS)
                    continue
                }
                sawCandidate = true
                failureCode = AttemptFailureCode.RaiseFailed

                val remainingBeforeRaise = RAISE_PHASE_BUDGET_MS -
                    (platform.monotonicNowMs() - raiseStartedAtMonotonicMs)
                if (remainingBeforeRaise <= 0L) break
                val result = withTimeoutOrNull(remainingBeforeRaise) {
                    platform.raise(window, trace.id)
                } ?: break
                if (!isCurrent(app, key, trace)) return
                if (result.tierResults.isNotEmpty()) {
                    trace.tierResults += result.tierResults
                } else {
                    trace.tierResults += "${result.tier}=${result.success}"
                }

                val foregroundBelongsToChain =
                    result.foregroundPid != null && result.foregroundPid in trackedPids
                val foregroundOwnedByTarget =
                    result.foregroundRootOwnerHandle == window.handle
                if (foregroundBelongsToChain || foregroundOwnedByTarget) {
                    trace.tierResults += if (foregroundBelongsToChain) {
                        "foreground-owner=tracked-pid"
                    } else {
                        "foreground-owner=target-root"
                    }
                    if (finishSuccess(key, trace)) {
                        rememberVerifiedMethod(trace)
                        trace.raiseMs =
                            platform.monotonicNowMs() - raiseStartedAtMonotonicMs
                        runCatching { onRaised(app) }
                            .onFailure { logAttemptWarning(trace, "post-raise callback failed", it) }
                        completeAttempt(trace, null)
                    }
                    return
                }
                if (index < MAX_RAISE_ATTEMPTS - 1) {
                    val remaining = RAISE_PHASE_BUDGET_MS -
                        (platform.monotonicNowMs() - raiseStartedAtMonotonicMs)
                    if (remaining <= 0L) break
                    delay(minOf(RAISE_RETRY_DELAY_MS, remaining))
                    if (!isCurrent(app, key, trace)) return
                }
            }
        } finally {
            trace.raiseMs = platform.monotonicNowMs() - raiseStartedAtMonotonicMs
        }
        if (!sawCandidate) failureCode = AttemptFailureCode.NoWindow
        val reason = if (failureCode == AttemptFailureCode.NoWindow) {
            "${app.displayName} no longer has a window to raise."
        } else {
            "Couldn't bring ${app.displayName} to the front"
        }
        fail(
            key,
            trace,
            reason,
            failureCode,
            canRetry = true
        )
    }

    /**
     * A method is remembered only after the app it started was raised and, for a
     * shell hand-off, at least one process it created was proven to be unelevated.
     */
    private fun rememberVerifiedMethod(trace: AttemptTrace) {
        val method = trace.launchMethod ?: return
        if (trace.handoffMethod != null && trace.unelevatedIdentities.isEmpty()) return
        runCatching { platform.launchVerified(method) }
            .onFailure { logAttemptWarning(trace, "could not record verified method", it) }
    }

    private fun fail(
        key: String,
        trace: AttemptTrace,
        reason: String,
        failureCode: AttemptFailureCode,
        canRetry: Boolean,
        staleIdentities: Set<ProcessIdentity> = emptySet()
    ) {
        val failed = LaunchState.Failed(
            code = publicFailureCode(failureCode),
            reason = reason,
            canRetry = canRetry
        )
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

    private data class ClassifiedSpawnFailure(
        val code: AttemptFailureCode,
        val reason: String
    )

    private fun classifySpawnFailure(message: String): ClassifiedSpawnFailure {
        val errorCode = Regex("(?i)(?:createprocess\\s+)?error\\s*=\\s*(\\d{1,4})")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return when {
            message.contains("Microsoft Store", ignoreCase = true) ->
                ClassifiedSpawnFailure(
                    AttemptFailureCode.StoreAppUnsupported,
                    "Microsoft Store apps cannot be launched from Focus Launcher yet."
                )
            errorCode == 2 || errorCode == 3 ->
                ClassifiedSpawnFailure(
                    AttemptFailureCode.NotFound,
                    "The app executable could not be found."
                )
            errorCode == 5 ->
                ClassifiedSpawnFailure(
                    AttemptFailureCode.AccessDenied,
                    "Windows denied access to this app."
                )
            errorCode == 193 ->
                ClassifiedSpawnFailure(
                    AttemptFailureCode.UnsupportedCandidate,
                    "This file is not a supported Windows app."
                )
            errorCode == 740 ->
                ClassifiedSpawnFailure(
                    AttemptFailureCode.NeedsElevation,
                    "This app requires administrator access."
                )
            else ->
                ClassifiedSpawnFailure(
                    AttemptFailureCode.SpawnFailed,
                    "Couldn't start this app. Tap to retry."
                )
        }
    }

    private fun publicFailureCode(code: AttemptFailureCode): LaunchFailureCode =
        when (code) {
            AttemptFailureCode.NotAllowed -> LaunchFailureCode.NotAllowed
            AttemptFailureCode.BlockedByRule -> LaunchFailureCode.BlockedByRule
            AttemptFailureCode.NotFound -> LaunchFailureCode.NotFound
            AttemptFailureCode.NeedsElevation -> LaunchFailureCode.NeedsElevation
            AttemptFailureCode.AccessDenied -> LaunchFailureCode.AccessDenied
            AttemptFailureCode.StoreAppUnsupported -> LaunchFailureCode.StoreAppUnsupported
            AttemptFailureCode.UnsupportedCandidate -> LaunchFailureCode.UnsupportedCandidate
            AttemptFailureCode.SpawnFailed,
            AttemptFailureCode.SpawnDebounced -> LaunchFailureCode.SpawnFailed
            AttemptFailureCode.ExitedEarly -> LaunchFailureCode.ExitedEarly
            AttemptFailureCode.NoWindow -> LaunchFailureCode.NoWindow
            AttemptFailureCode.RaiseFailed -> LaunchFailureCode.RaiseFailed
            AttemptFailureCode.Cancelled -> LaunchFailureCode.Cancelled
            AttemptFailureCode.Internal -> LaunchFailureCode.Internal
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
            // The launch we started has produced its window; processes left behind
            // when the user later closes the app are not a launch in progress.
            lastSpawnAtWallMs.remove(key)
            lastHandoff.remove(key)
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
        val failed = LaunchState.Failed(
            code = LaunchFailureCode.NoWindow,
            reason = "Launch timed out waiting for the app window. Tap to retry.",
            canRetry = true
        )
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
        trace.spawnCreationTimeMs = trace.spawnCreationTimeMs
            ?: trace.spawnPid?.let { tracker.process(it)?.creationTimeMs }
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
            is SpawnResult.Handoff -> "handoff"
            is SpawnResult.Failed -> "failed"
        }

    private fun formatWindows(windows: Collection<WindowRef>): String =
        windows.sortedWith(compareBy(WindowRef::pid, WindowRef::handle))
            .joinToString(prefix = "[", postfix = "]") { "${it.pid}:${it.handle}" }

    private fun formatLogList(values: Collection<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { sanitizeLogField(it) }

    private fun sanitizeLogField(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex("\\s{2,}"), " ")

    private fun isCurrent(app: FocusLauncherApp, key: String, trace: AttemptTrace): Boolean {
        if (!synchronized(lock) { activeAttempts[key] === trace }) return false
        if (isAllowed(app)) return true

        fail(
            key,
            trace,
            "This app is no longer available in the current Focus Launcher session.",
            AttemptFailureCode.NotAllowed,
            canRetry = false
        )
        return false
    }

    private fun keyFor(processName: String): String = processName.lowercase(Locale.ROOT)
}
