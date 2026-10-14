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
    val processName: String
)

data class WindowRef(val handle: Long, val pid: Long)

sealed interface SpawnResult {
    data class Started(val pid: Long) : SpawnResult
    data class Failed(val message: String, val canRetry: Boolean = true) : SpawnResult
}

data class RaiseResult(val success: Boolean, val tier: String)

/**
 * OS operations used by the launch state machine. The coordinator calls these
 * only from its worker scope, and fake implementations keep the state machine
 * deterministic in tests.
 */
interface LaunchPlatform {
    fun nowMs(): Long
    fun findInstances(app: FocusLauncherApp): List<RunningInstance>
    fun findWindows(pids: Set<Long>): List<WindowRef>
    fun spawn(app: FocusLauncherApp): SpawnResult
    suspend fun raise(window: WindowRef): RaiseResult
    fun focusFlowElevated(): Boolean? = null
}

class LauncherLaunchCoordinator(
    private val platform: LaunchPlatform,
    private val scope: CoroutineScope,
    private val isAllowed: (FocusLauncherApp) -> Boolean = { true },
    private val onRaised: (FocusLauncherApp) -> Unit = {}
) {
    companion object {
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

    private val lock = Any()
    private val _states = MutableStateFlow<Map<String, LaunchState>>(emptyMap())
    val states: StateFlow<Map<String, LaunchState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val failureResetJobs = mutableMapOf<String, Job>()
    private val lastSpawnAtMs = mutableMapOf<String, Long>()
    private val waitOnlyRetryKeys = mutableSetOf<String>()
    private val elevationLogged = AtomicBoolean(false)

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
                log("Ignored launch for non-session app=${app.processName}")
                return false
            }

            val current = _states.value[key]
            if (current is LaunchState.Launching) {
                log("Ignored duplicate click app=${app.processName}")
                return false
            }
            if (current is LaunchState.Failed && !current.canRetry) {
                return false
            }

            failureResetJobs.remove(key)?.cancel()
            val waitOnlyRetry = waitOnlyRetryKeys.remove(key)
            val requestGeneration = generation
            val launchStartedAt = platform.nowMs()
            _states.update { it + (key to LaunchState.Launching(launchStartedAt)) }
            log(
                "state app=${app.processName} ${current ?: LaunchState.Idle} -> " +
                    "Launching atMs=$launchStartedAt retryWaitOnly=$waitOnlyRetry"
            )

            val job = scope.launch(start = CoroutineStart.LAZY) {
                executeRequest(app, key, requestGeneration, waitOnlyRetry)
            }
            jobs[key] = job
            job.invokeOnCompletion {
                synchronized(lock) {
                    if (jobs[key] === job) jobs.remove(key)
                }
            }
            job.start()
        }

        logElevationOnce()
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
        waitOnlyRetry: Boolean
    ) {
        val attemptStartedAt = platform.nowMs()
        try {
            if (!isCurrent(app, requestGeneration)) return

            val instances = platform.findInstances(app)
            log("instances app=${app.processName} count=${instances.size} pids=${instances.map { it.pid }}")
            val pids = instances.mapTo(mutableSetOf()) { it.pid }
            val windows = platform.findWindows(pids)

            if (windows.isNotEmpty()) {
                log("window found app=${app.processName} afterMs=${platform.nowMs() - attemptStartedAt}")
                raiseWithRetries(app, key, windows.first(), attemptStartedAt, requestGeneration)
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
                if (waitOnlyRetry) {
                    log("retry app=${app.processName} will only wait/focus; no spawn")
                } else if (hasRecentInstance) {
                    log("app=${app.processName} already has a recent instance; waiting for its window")
                } else {
                    log("app=${app.processName} spawn budget active; waiting before another spawn")
                }
                val window = awaitWindow(app, pids, requestGeneration)
                if (window == null) {
                    fail(
                        key,
                        requestGeneration,
                        "${app.displayName} is taking longer than usual",
                        canRetry = true,
                        waitOnlyNextRetry = !waitOnlyRetry
                    )
                } else {
                    log("window found app=${app.processName} afterMs=${platform.nowMs() - attemptStartedAt}")
                    raiseWithRetries(app, key, window, attemptStartedAt, requestGeneration)
                }
                return
            }

            synchronized(lock) {
                if (generation != requestGeneration) return
                lastSpawnAtMs[key] = platform.nowMs()
            }
            log("spawn attempt app=${app.processName} atMs=${platform.nowMs()}")
            when (val result = platform.spawn(app)) {
                is SpawnResult.Failed -> {
                    log("spawn failed app=${app.processName} reason=${result.message}")
                    fail(
                        key,
                        requestGeneration,
                        result.message,
                        canRetry = result.canRetry
                    )
                }
                is SpawnResult.Started -> {
                    log("spawn started app=${app.processName} pid=${result.pid}")
                    pids += result.pid
                    val window = awaitWindow(app, pids, requestGeneration)
                    if (window == null) {
                        fail(
                            key,
                            requestGeneration,
                            "${app.displayName} is taking longer than usual",
                            canRetry = true,
                            waitOnlyNextRetry = !waitOnlyRetry
                        )
                    } else {
                        log("window found app=${app.processName} afterMs=${platform.nowMs() - attemptStartedAt}")
                        raiseWithRetries(app, key, window, attemptStartedAt, requestGeneration)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message?.takeIf(String::isNotBlank)
                ?: "Launch failed (${failure.javaClass.simpleName})"
            EnforcementLog.warn("Launcher", "launch failed app=${app.processName}", failure)
            fail(key, requestGeneration, reason, canRetry = true)
        }
    }

    private suspend fun awaitWindow(
        app: FocusLauncherApp,
        initialPids: Set<Long>,
        requestGeneration: Long
    ): WindowRef? {
        val startedAt = platform.nowMs()
        var pids = initialPids.toMutableSet()
        var lastProcessRefreshAt = startedAt

        while (true) {
            if (!isCurrent(app, requestGeneration)) return null
            val windows = platform.findWindows(pids)
            if (windows.isNotEmpty()) return windows.first()

            val now = platform.nowMs()
            val elapsed = now - startedAt
            val remaining = WINDOW_WAIT_MS - elapsed
            if (remaining <= 0L) return null

            if (now - lastProcessRefreshAt >= PROCESS_REFRESH_MS) {
                val instances = platform.findInstances(app)
                log("refresh instances app=${app.processName} count=${instances.size}")
                pids = instances.mapTo(mutableSetOf()) { it.pid }
                lastProcessRefreshAt = now
            }
            delay(minOf(WINDOW_POLL_MS, remaining))
        }
    }

    private suspend fun raiseWithRetries(
        app: FocusLauncherApp,
        key: String,
        initialWindow: WindowRef,
        attemptStartedAt: Long,
        requestGeneration: Long
    ) {
        var window = initialWindow
        repeat(MAX_RAISE_ATTEMPTS) { index ->
            if (!isCurrent(app, requestGeneration)) return
            val result = platform.raise(window)
            log(
                "raise app=${app.processName} pid=${window.pid} tier=${result.tier} " +
                    "success=${result.success} attempt=${index + 1}"
            )
            if (result.success) {
                if (!isCurrent(app, requestGeneration)) return
                runCatching { onRaised(app) }
                    .onFailure { EnforcementLog.warn("Launcher", "post-raise callback failed app=${app.processName}", it) }
                log("state app=${app.processName} Launching -> Idle afterMs=${platform.nowMs() - attemptStartedAt}")
                setIdle(key, requestGeneration)
                return
            }
            if (index < MAX_RAISE_ATTEMPTS - 1) {
                delay(RAISE_RETRY_DELAY_MS)
                val refreshed = platform.findWindows(setOf(window.pid))
                if (refreshed.isNotEmpty()) window = refreshed.first()
            }
        }
        fail(
            key,
            requestGeneration,
            "Couldn't bring ${app.displayName} to the front",
            canRetry = true
        )
    }

    private fun fail(
        key: String,
        requestGeneration: Long,
        reason: String,
        canRetry: Boolean,
        waitOnlyNextRetry: Boolean = false
    ) {
        val failed = LaunchState.Failed(reason, canRetry)
        synchronized(lock) {
            if (generation != requestGeneration) return
            if (waitOnlyNextRetry) waitOnlyRetryKeys += key
            _states.update { it + (key to failed) }
            log("state app=$key Launching -> Failed canRetry=$canRetry reason=$reason")
            failureResetJobs.remove(key)?.cancel()
            val resetJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(FAILURE_DISPLAY_MS)
                synchronized(lock) {
                    if (generation == requestGeneration && _states.value[key] == failed) {
                        _states.update { it + (key to LaunchState.Idle) }
                        log("state app=$key Failed -> Idle afterMs=$FAILURE_DISPLAY_MS")
                    }
                    failureResetJobs.remove(key)
                }
            }
            failureResetJobs[key] = resetJob
            resetJob.start()
        }
    }

    private fun setIdle(key: String, requestGeneration: Long) {
        synchronized(lock) {
            if (generation != requestGeneration) return
            _states.update { it + (key to LaunchState.Idle) }
            waitOnlyRetryKeys.remove(key)
        }
    }

    private fun isCurrent(app: FocusLauncherApp, requestGeneration: Long): Boolean =
        generation == requestGeneration && isAllowed(app)

    private fun logElevationOnce() {
        if (!elevationLogged.compareAndSet(false, true)) return
        scope.launch {
            val elevated = runCatching { platform.focusFlowElevated() }.getOrNull()
            if (elevated != null) log("FocusFlow elevated=$elevated")
        }
    }

    private fun log(message: String) {
        EnforcementLog.info("Launcher", message)
    }

    private fun keyFor(processName: String): String = processName.lowercase(Locale.ROOT)
}
