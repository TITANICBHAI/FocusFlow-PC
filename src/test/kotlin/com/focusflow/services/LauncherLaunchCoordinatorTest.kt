package com.focusflow.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
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
            val attemptLogs = logs.filter { it.startsWith("attemptId=$id ") }
            assertTrue(attemptLogs.size <= 10, "attempt $id logged ${attemptLogs.size} lines")
        }
        val openedAttemptId = Regex("attemptId=([^ ]+)")
            .find(openedSummary)!!
            .groupValues[1]
        val openedLogs = logs.filter { it.startsWith("attemptId=$openedAttemptId ") }
        assertTrue(openedLogs.any { "phase=snapshot start" in it })
        assertTrue(openedLogs.any { "phase=awaitWindow start reason=spawn" in it })
        assertTrue(openedLogs.any { "phase=raise start" in it })
        coordinator.cancelAll()
    }

    @Test
    fun raiseAndInternalFailuresEachEmitOneSummary() = runTest {
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            instances += RunningInstance(42L, nowMs(), app.processName)
            windows += WindowRef(handle = 420L, pid = 42L)
            raiseSuccess = false
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(
            LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS +
                LauncherLaunchCoordinator.RAISE_RETRY_DELAY_MS *
                (LauncherLaunchCoordinator.MAX_RAISE_ATTEMPTS - 1)
        )
        runCurrent()

        platform.findInstancesFailure = IllegalStateException("test snapshot failure")
        val internalErrorApp = FocusLauncherApp(
            processName = "internal-error.exe",
            displayName = "Internal error",
            exePath = "C:\\Apps\\internal-error.exe"
        )
        coordinator.request(internalErrorApp)
        runCurrent()

        val summaries = logs.filter { " summary=true " in " $it " }
        assertEquals(2, summaries.size)
        val raiseFailed = summaries.single { "exe=sample.exe" in it }
        val internalFailed = summaries.single { "exe=internal-error.exe" in it }
        assertTrue("result=Failed(code=RaiseFailed)" in raiseFailed)
        assertTrue("tierResults=[fake=false, fake=false, fake=false]" in raiseFailed)
        assertTrue("result=Failed(code=Internal)" in internalFailed)
        val attemptIds = summaries.map { Regex("attemptId=([^ ]+)").find(it)!!.groupValues[1] }
        attemptIds.forEach { id ->
            assertEquals(1, summaries.count { "attemptId=$id " in it })
        }
        coordinator.cancelAll()
    }

    @Test
    fun delayedWindowIsRaisedAfterItRemainsStable() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            windowAppearsAtMs = 300L
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        assertEquals(1, platform.raisedWindows.size)
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        assertEquals(5, platform.windowPollsForSpawnedPid)
        coordinator.cancelAll()
    }

    @Test
    fun deadOnArrivalFailsQuicklyAndTheNextTapSpawns() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnedProcessAlive = false
        }
        val logs = mutableListOf<String>()
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(4_000L)
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
        assertTrue(firstAttemptLogs.single { " summary=true " in " $it " }
            .contains("result=Failed(code=ExitedEarly)"))

        assertTrue(coordinator.request(app))
        runCurrent()
        assertEquals(2, platform.spawnCount)
        assertIs<LaunchState.Launching>(coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun backwardWallClockDoesNotExtendSpawnDebounce() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnedProcessAlive = false
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(4_000L)
        runCurrent()
        assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])

        platform.clockOffsetMs = -10_000L
        assertTrue(coordinator.request(app))
        runCurrent()

        assertEquals(2, platform.spawnCount)
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
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()

        assertEquals(0, platform.spawnCount)
        assertEquals(listOf(WindowRef(420L, 42L)), platform.raisedWindows)
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun oldInstanceWithoutWindowAllowsOneNewSpawn() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            instances += RunningInstance(
                41L,
                nowMs() - LauncherLaunchCoordinator.ATTACH_WINDOW_MS - 10_000L,
                app.processName
            )
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        coordinator.cancelAll()
    }

    @Test
    fun quickReopenSpawnsAgainAfterThePreviousProcessExits() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnResults.add(SpawnResult.Started(100L))
            spawnResults.add(SpawnResult.Started(200L))
            windowAppearsAtMs = 0L
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()
        assertEquals(1, platform.spawnCount)

        platform.spawnedProcessEntries.clear()
        platform.windowAppearsAtMs = null
        advanceTimeBy(7_700L)
        coordinator.request(app)
        runCurrent()

        assertEquals(2, platform.spawnCount)
        assertIs<LaunchState.Launching>(coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun cancelAllStopsAWindowRaiseThatHasNotStarted() = runTest {
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            windowAppearsAtMs = 150L
            raiseGate = CompletableDeferred()
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(150L)
        runCurrent()
        assertEquals(0, platform.raisedWindows.size)

        coordinator.cancelAll()
        runCurrent()

        assertTrue(platform.raisedWindows.isEmpty())
        assertTrue(coordinator.states.value.isEmpty())
        val summaries = logs.filter { " summary=true " in " $it " }
        assertEquals(1, summaries.size)
        assertTrue("result=Failed(code=Cancelled)" in summaries.single())
    }

    @Test
    fun timedOutLiveChainIsSpawnedAgainOnTheNextTap() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnResults.add(SpawnResult.Started(100L))
            spawnResults.add(SpawnResult.Started(200L))
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_WAIT_MS)
        runCurrent()
        assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])
        assertEquals(1, platform.spawnCount)

        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertTrue(coordinator.request(app))
        runCurrent()

        assertEquals(2, platform.spawnCount)
        assertIs<LaunchState.Launching>(coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun watchdogInvalidatesWorkerReturningFromSnapshotBeforeItCanSpawn() = runTest {
        val gate = CompletableDeferred<Unit>()
        val platform = FakeLaunchPlatform(this).apply {
            snapshotGate = gate
            snapshotGateIsNonCancellable = true
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(
            LauncherLaunchCoordinator.WINDOW_WAIT_MS +
                LauncherLaunchCoordinator.WATCHDOG_MARGIN_MS
        )
        runCurrent()
        assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])

        platform.snapshotGate = null
        platform.windowAppearsAtMs = platform.nowMs()
        assertTrue(coordinator.request(app))
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        assertEquals(1, platform.spawnCount)

        gate.complete(Unit)
        runCurrent()
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        assertEquals(1, platform.spawnCount)
        coordinator.cancelAll()
    }

    @Test
    fun cancelDuringAwaitLeavesNoStateOrLateRaise() = runTest {
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this)
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        assertEquals(1, platform.spawnCount)

        coordinator.cancel(app)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(coordinator.states.value.isEmpty())
        assertTrue(platform.raisedWindows.isEmpty())
        assertEquals(1, logs.count { " summary=true " in " $it " })
        assertTrue(logs.single { " summary=true " in " $it " }
            .contains("result=Failed(code=Cancelled)"))
        coordinator.cancelAll()
    }

    @Test
    fun cancelDuringSpawnCannotPublishLateState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            spawnGate = gate
            spawnGateIsNonCancellable = true
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        assertEquals(0, platform.spawnCount)

        coordinator.cancel(app)
        runCurrent()
        assertTrue(coordinator.states.value.isEmpty())

        gate.complete(Unit)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        assertTrue(coordinator.states.value.isEmpty())
        assertEquals(1, logs.count { " summary=true " in " $it " })
        assertTrue(logs.single { " summary=true " in " $it " }
            .contains("result=Failed(code=Cancelled)"))
        coordinator.cancelAll()
    }

    @Test
    fun slowLiveProcessOpensOnTheFirstAttempt() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            snapshotProvider = { _ ->
                if (spawnCount == 0) emptyList()
                else listOf(
                    ProcessSnapshotEntry(
                        pid = 100L,
                        creationTimeMs = 0L,
                        processName = app.processName,
                        executablePath = app.exePath
                    )
                )
            }
            windowSchedule = { now, pids ->
                if (now >= 35_000L && 100L in pids) {
                    listOf(WindowRef(handle = 1_100L, pid = 100L))
                } else {
                    emptyList()
                }
            }
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(36_000L)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun stubHandoffUnderTheInstallRootJoinsTheLaunchChain() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            snapshotProvider = { now ->
                when {
                    spawnCount == 0 -> emptyList()
                    now < 1_000L -> listOf(
                        ProcessSnapshotEntry(
                            pid = 100L,
                            creationTimeMs = 0L,
                            processName = app.processName,
                            executablePath = app.exePath
                        )
                    )
                    now < 2_000L -> emptyList()
                    else -> listOf(
                        ProcessSnapshotEntry(
                            pid = 200L,
                            creationTimeMs = 2_000L,
                            processName = "helper.exe",
                            executablePath = "C:\\Apps\\helper.exe"
                        )
                    )
                }
            }
            windowSchedule = { now, pids ->
                if (now >= 6_000L && 200L in pids) {
                    listOf(WindowRef(handle = 1_200L, pid = 200L))
                } else {
                    emptyList()
                }
            }
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(6_600L)
        runCurrent()

        assertEquals(1, platform.spawnCount)
        assertEquals(listOf(WindowRef(handle = 1_200L, pid = 200L)), platform.raisedWindows)
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        coordinator.cancelAll()
    }

    @Test
    fun unreadableSpawnedProcessCanStillOwnTheFoundWindow() = runTest {
        val platform = FakeLaunchPlatform(this).apply {
            spawnedProcessAlive = false
            windowAppearsAtMs = 0L
            snapshotProvider = { _ ->
                if (spawnCount == 0) emptyList()
                else listOf(
                    ProcessSnapshotEntry(
                        pid = 100L,
                        creationTimeMs = 0L,
                        processName = "",
                        executablePath = null
                    )
                )
            }
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()

        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        assertEquals(100L, platform.raisedWindows.single().pid)
        coordinator.cancelAll()
    }

    @Test
    fun splashWindowIsReplacedByTheMainWindowWhenItAppears() = runTest {
        val splash = WindowRef(
            handle = 421L,
            pid = 42L,
            ownerless = false,
            hasTitle = false,
            reasonableSize = false,
            zOrder = 0
        )
        val main = WindowRef(
            handle = 422L,
            pid = 42L,
            ownerless = true,
            hasTitle = true,
            reasonableSize = true,
            zOrder = 1
        )
        val platform = FakeLaunchPlatform(this).apply {
            instances += RunningInstance(
                pid = 42L,
                startedAtMs = nowMs(),
                processName = app.processName,
                creationTimeMs = nowMs(),
                executablePath = app.exePath
            )
            windowSchedule = { now, pids ->
                if (42L !in pids) emptyList()
                else if (now < 300L) listOf(splash)
                else listOf(splash, main)
            }
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(600L)
        runCurrent()

        assertEquals(listOf(main), platform.raisedWindows)
        coordinator.cancelAll()
    }

    @Test
    fun windowOrderingPrefersMainWindowQualityThenZOrder() {
        val lowerQuality = WindowRef(
            handle = 1L,
            pid = 10L,
            ownerless = false,
            hasTitle = true,
            reasonableSize = true,
            zOrder = 0
        )
        val topmostMain = WindowRef(
            handle = 2L,
            pid = 10L,
            ownerless = true,
            hasTitle = true,
            reasonableSize = true,
            zOrder = 5
        )
        val nextMain = topmostMain.copy(handle = 3L, zOrder = 8)

        assertEquals(
            listOf(topmostMain, nextMain, lowerQuality),
            orderLauncherWindows(listOf(lowerQuality, nextMain, topmostMain))
        )
    }

    @Test
    fun storeAppFailsImmediatelyWithoutSpawning() = runTest {
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            unsupportedLaunchReason = "Microsoft Store apps cannot be launched here."
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()

        val failed = assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])
        assertFalse(failed.canRetry)
        assertEquals(0, platform.spawnCount)
        assertTrue(logs.any { "result=Failed(code=StoreAppUnsupported)" in it })
        coordinator.cancelAll()
    }

    @Test
    fun cancelDuringSnapshotClearsPerAppStateAndLogsOnce() = runTest {
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            snapshotGate = CompletableDeferred()
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        coordinator.cancel(app)
        runCurrent()

        assertTrue(coordinator.states.value.isEmpty())
        assertEquals(1, logs.count { " summary=true " in " $it " })
        assertTrue(logs.single { " summary=true " in " $it " }
            .contains("result=Failed(code=Cancelled)"))
        coordinator.cancelAll()
    }

    @Test
    fun watchdogEndsHungRaiseAndOldAttemptCannotChangeANewerAttempt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val platform = FakeLaunchPlatform(this).apply {
            spawnResults.add(SpawnResult.Started(100L))
            spawnResults.add(SpawnResult.Started(200L))
            spawnedProcessAlive = false
            windowAppearsAtMs = 0L
            raiseGate = gate
            raiseGateIsNonCancellable = true
        }
        val coordinator = coordinator(platform, backgroundScope)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()
        advanceTimeBy(
            LauncherLaunchCoordinator.WINDOW_WAIT_MS +
                LauncherLaunchCoordinator.WATCHDOG_MARGIN_MS
        )
        runCurrent()

        assertIs<LaunchState.Failed>(coordinator.states.value[app.processName])
        platform.raiseGate = null
        platform.windowAppearsAtMs = platform.nowMs()
        assertTrue(coordinator.request(app))
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])

        gate.complete(Unit)
        runCurrent()
        assertEquals(LaunchState.Idle, coordinator.states.value[app.processName])
        assertEquals(2, platform.spawnCount)
        coordinator.cancelAll()
    }

    @Test
    fun cancelDuringRaiseInvalidatesTheWorkerAndRemovesState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val platform = FakeLaunchPlatform(this).apply {
            windowAppearsAtMs = 0L
            raiseGate = gate
            raiseGateIsNonCancellable = true
        }
        val coordinator = coordinator(platform, backgroundScope, logs)

        coordinator.request(app)
        runCurrent()
        advanceTimeBy(LauncherLaunchCoordinator.WINDOW_SELECTION_SETTLE_MS)
        runCurrent()
        coordinator.cancel(app)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(coordinator.states.value.isEmpty())
        assertEquals(1, logs.count { " summary=true " in " $it " })
        assertTrue(logs.single { " summary=true " in " $it " }
            .contains("result=Failed(code=Cancelled)"))
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
        val spawnResults = java.util.ArrayDeque<SpawnResult>()
        val spawnedProcessEntries = linkedMapOf<Long, ProcessSnapshotEntry>()
        var spawnResult: SpawnResult = SpawnResult.Started(100L)
        var spawnCount = 0
        var spawnedPid: Long? = null
        var spawnedProcessAlive = true
        var windowAppearsAtMs: Long? = null
        var snapshotProvider: ((Long) -> List<ProcessSnapshotEntry>)? = null
        var windowSchedule: ((Long, Set<Long>) -> List<WindowRef>)? = null
        var snapshotGate: CompletableDeferred<Unit>? = null
        var snapshotGateIsNonCancellable = false
        var spawnGate: CompletableDeferred<Unit>? = null
        var spawnGateIsNonCancellable = false
        var clockOffsetMs = 0L
        var unsupportedLaunchReason: String? = null
        var raiseGate: CompletableDeferred<Unit>? = null
        var raiseGateIsNonCancellable = false
        var raiseSuccess = true
        var findInstancesFailure: Exception? = null
        var windowPollsForSpawnedPid = 0

        override fun nowMs(): Long = testScope.testScheduler.currentTime + clockOffsetMs

        override fun monotonicNowMs(): Long = testScope.testScheduler.currentTime

        override fun findInstances(app: FocusLauncherApp): List<RunningInstance> {
            findInstancesFailure?.let { throw it }
            return instances.toList()
        }

        override fun processSnapshot(app: FocusLauncherApp): List<ProcessSnapshotEntry> {
            snapshotProvider?.let { return it(nowMs()) }
            val existing = findInstances(app).map { instance ->
                ProcessSnapshotEntry(
                    pid = instance.pid,
                    parentPid = instance.parentPid,
                    creationTimeMs = instance.creationTimeMs,
                    processName = instance.processName,
                    executablePath = instance.executablePath
                )
            }
            val spawned = if (spawnedProcessAlive) spawnedProcessEntries.values else emptyList()
            return existing + spawned
        }

        override suspend fun processSnapshotAsync(app: FocusLauncherApp): List<ProcessSnapshotEntry> =
            run {
                val gate = snapshotGate
                if (gate != null) {
                    if (snapshotGateIsNonCancellable) {
                        withContext(NonCancellable) { gate.await() }
                    } else {
                        gate.await()
                    }
                }
                processSnapshot(app)
            }

        override fun unsupportedReason(app: FocusLauncherApp): String? =
            unsupportedLaunchReason

        override fun findWindows(pids: Set<Long>): List<WindowRef> {
            windowSchedule?.let { return it(nowMs(), pids).filter { window -> window.pid in pids } }
            val pid = spawnedPid
            if (pid != null && pid in pids) {
                windowPollsForSpawnedPid++
                if (windowAppearsAtMs != null && nowMs() >= windowAppearsAtMs!!) {
                    return listOf(WindowRef(handle = pid + 1_000L, pid = pid))
                }
            }
            return windows.filter { it.pid in pids }
        }

        override suspend fun findWindowsAsync(
            pids: Set<Long>,
            attemptId: String
        ): List<WindowRef> = findWindows(pids)

        override fun spawn(app: FocusLauncherApp): SpawnResult {
            spawnCount++
            val result = spawnResults.pollFirst() ?: spawnResult
            if (result is SpawnResult.Started) {
                spawnedPid = result.pid
                spawnedProcessEntries[result.pid] = ProcessSnapshotEntry(
                    pid = result.pid,
                    creationTimeMs = nowMs(),
                    processName = app.processName,
                    executablePath = app.exePath
                )
            }
            return result
        }

        override suspend fun spawnAsync(app: FocusLauncherApp, attemptId: String): SpawnResult {
            val gate = spawnGate
            if (gate != null) {
                if (spawnGateIsNonCancellable) {
                    return withContext(NonCancellable) {
                        gate.await()
                        spawn(app)
                    }
                }
                gate.await()
            }
            return spawn(app)
        }

        override suspend fun raise(window: WindowRef): RaiseResult {
            val gate = raiseGate
            if (gate != null) {
                if (raiseGateIsNonCancellable) {
                    withContext(NonCancellable) { gate.await() }
                } else {
                    gate.await()
                }
            }
            raisedWindows += window
            return RaiseResult(success = raiseSuccess, tier = "fake")
        }

        override fun focusFlowElevated(): Boolean? = true

        override fun lastForegroundLockCall(): String? = "lock=true"

        override fun processIsAlive(pid: Long): Boolean? = pid == spawnedPid
    }
}
