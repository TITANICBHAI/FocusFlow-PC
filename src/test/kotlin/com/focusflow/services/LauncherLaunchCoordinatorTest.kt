package com.focusflow.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LauncherLaunchCoordinatorTest {
    private val app = FocusLauncherApp(
        processName = "sample.exe",
        displayName = "Sample",
        exePath = "C:\\Apps\\sample.exe"
    )

    @Test
    fun duplicateClicksOnlyScheduleOneSpawn() = runTest {
        val platform = FakeLaunchPlatform(this)
        val coordinator = coordinator(platform, backgroundScope)

        assertTrue(coordinator.request(app))
        assertIs<LaunchState.Launching>(coordinator.states.value[app.processName])
        assertFalse(coordinator.request(app))
        runCurrent()

        assertEquals(1, platform.spawnCount)
        coordinator.cancelAll()
    }

    @Test
    fun spawnFailureIsVisibleAndRetryable() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnResult = SpawnResult.Failed("test spawn failure")
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()

        val failed = assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])
        assertEquals("test spawn failure", failed.reason)
        assertTrue(failed.canRetry)
        assertEquals(1, platform.spawnCount)
        coordinator.cancelAll()
    }

    @Test
    fun everyTerminalAttemptEmitsOneSummaryWithTraceFields() = runTest {
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            spawnResult = SpawnResult.Failed("test spawn failure")
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()

        platform.spawnResult = SpawnResult.Started(101L)
        platform.windowAppearsAtMs = platform.nowMs()
        val secondApp = FocusLauncherApp(
            processName = "second.exe",
            displayName = "Second",
            exePath = "C:\\Apps\\second.exe"
        )
        coordinator.request(secondApp)
        runCurrent()

        val summaries = logs.filter { " summary=true " in " $it " }
        assertEquals(2, summaries.size)
        val failedSummary = summaries.single { "exe=sample.exe" in it }
        val openedSummary = summaries.single { "exe=second.exe" in it }
        assertTrue("result=Failed(code=SpawnFailed)" in failedSummary)
        assertTrue("spawnPid=none" in failedSummary)
        assertTrue("spawnAlive=not-spawned" in failedSummary)
        assertTrue("snapshotMs=" in failedSummary)
        assertTrue("spawnMs=" in failedSummary)
        assertTrue("firstWindowMs=" in failedSummary)
        assertTrue("raiseMs=" in failedSummary)
        assertTrue("focusFlowElevated=true" in failedSummary)
        assertTrue("foregroundLockCall=lock=true" in failedSummary)
        assertTrue("result=Opened" in openedSummary)
        assertTrue("spawnPid=101" in openedSummary)
        assertTrue("spawnAlive=true" in openedSummary)
        assertTrue("tierResults=[fake=true]" in openedSummary)

        val attemptIds = summaries.map { Regex("attemptId=([^ ]+)").find(it)!!.groupValues[1] }
        assertNotEquals(attemptIds[0], attemptIds[1])
        attemptIds.forEach { id ->
            assertEquals(1, summaries.count { "attemptId=$id " in it })
        }
        coordinator.cancelAll()
    }

    @Test
    fun delayedWindowIsRaisedAfterThreePolls() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            windowAppearsAtMs = 300L
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(300L)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        assertEquals(1, platform.raisedWindows.size)
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        assertEquals(3, platform.windowPollsForSpawnedPid)
        coordinator.cancelAll()
    }

    @Test
    fun timeoutCanRetryByWaitingButDoesNotSpawnAgain() = runTest {
        val platform = FakeLaunchPlatform(this)
        val logs = mutableListOf<String>()
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_WAIT_MS)
        runCurrent()

        val failed = assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])
        assertTrue(failed.canRetry)
        assertEquals(1, platform.spawnCount)
        val firstAttemptId = Regex("attemptId=([^ ]+)")
            .find(logs.first())!!
            .groupValues[1]
        val firstAttemptLogs = logs.filter { it.startsWith("attemptId=$firstAttemptId ") }
        assertTrue(firstAttemptLogs.size <= 10, "attempt logged ${firstAttemptLogs.size} lines")
        assertEquals(1, firstAttemptLogs.count { " summary=true " in " $it " })

        assertTrue(coordinator.request(app))
        runCurrent()
        assertEquals(1, platform.spawnCount)
        assertIs<LaunchState.Launching>(coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun existingWindowIsRaisedWithoutSpawning() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            instances += RunningInstance(42L, nowMs(), app.processName)
            windows += WindowRef(handle = 420L, pid = 42L)
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()

        assertEquals(0, platform.spawnCount)
        assertEquals(listOf(WindowRef(420L, 42L)), platform.raisedWindows)
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun oldInstanceWithoutWindowAllowsOneNewSpawn() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            instances += RunningInstance(41L, nowMs() - 30_000L, app.processName)
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        coordinator.cancelAll()
    }

    @Test
    fun spawnBudgetPreventsAnotherSpawnWithinTwentySeconds() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnResult = SpawnResult.Failed("first attempt failed")
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        assertEquals(1, platform.spawnCount)

        advanceTimeBy(1_000L)
        platform.spawnResult = SpawnResult.Started(100L)
        coordinator.request(app)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        assertIs<LaunchState.Launching>(coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun cancelAllStopsAWindowRaiseThatHasNotStarted() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            windowAppearsAtMs = 150L
            raiseGate = CompletableDeferred()
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(0, platform.raisedWindows.size)

        coordinator.cancelAll()
        runCurrent()

        assertTrue(platform.raisedWindows.isEmpty())
        assertTrue(coordinator.states.value.isEmpty())
    }

    private fun coordinator(
        platform: FakeLaunchPlatform,
        scope: kotlinx.coroutines.CoroutineScope,
        logs: MutableList<String>? = null
    ) = LauncherLaunchCoordinator(
        platform = platform,
        scope = scope,
        isAllowed = { true },
        logInfo = { line -> logs?.add(line) }
    )

    private class FakeLaunchPlatform(private val testScope: TestScope) : LaunchPlatform {
        val instances = mutableListOf<RunningInstance>()
        val windows = mutableListOf<WindowRef>()
        val raisedWindows = mutableListOf<WindowRef>()
        var spawnResult: SpawnResult = SpawnResult.Started(100L)
        var spawnCount = 0
        var spawnedPid: Long? = null
        var windowAppearsAtMs: Long? = null
        var raiseGate: CompletableDeferred<Unit>? = null
        var windowPollsForSpawnedPid = 0

        override fun nowMs(): Long = testScope.testScheduler.currentTime

        override fun findInstances(app: FocusLauncherApp): List<RunningInstance> =
            instances.toList()

        override fun findWindows(pids: Set<Long>): List<WindowRef> {
            val pid = spawnedPid
            if (pid != null && pid in pids) {
                windowPollsForSpawnedPid++
                if (windowAppearsAtMs != null && nowMs() >= windowAppearsAtMs!!) {
                    return listOf(WindowRef(handle = pid + 1_000L, pid = pid))
                }
            }
            return windows.filter { it.pid in pids }
        }

        override fun spawn(app: FocusLauncherApp): SpawnResult {
            spawnCount++
            return spawnResult.also { result ->
                if (result is SpawnResult.Started) spawnedPid = result.pid
            }
        }

        override suspend fun raise(window: WindowRef): RaiseResult {
            raiseGate?.await()
            raisedWindows += window
            return RaiseResult(success = true, tier = "fake")
        }

        override fun focusFlowElevated(): Boolean? = true

        override fun lastForegroundLockCall(): String? = "lock=true"

        override fun processIsAlive(pid: Long): Boolean? = pid == spawnedPid
    }
}
