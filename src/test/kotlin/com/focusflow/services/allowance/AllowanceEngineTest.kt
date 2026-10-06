package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllowanceEngineTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun startsWithFakePortsWithoutUsingTheDatabaseOrWindowsApis() = runTest {
        val store = FakeUsageStore()
        val blockedSets = mutableListOf<Set<String>>()
        val engine = createEngine(
            backgroundScope,
            store,
            FixedClock,
            blockedSet = BlockedSetSink { blockedSets += it }
        )

        engine.start()
        runCurrent()

        assertEquals(1, store.allowanceReads)
        assertEquals(1, store.usageReads)
        assertEquals(emptyList(), engine.getUsageSummary())
        assertEquals(setOf(emptySet<String>()), blockedSets.toSet())

        engine.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun creditsAndPersistsUsageForARunningProcessFromTheInjectedSource() = runTest {
        val today = LocalDate.of(2026, 1, 2)
        val clock = MutableClock(today, 1_000L, 1_000_000L)
        val store = FakeUsageStore(listOf(DailyAllowance("sleep", "Smoke Sleep", 9999)))
        val engine = createEngine(
            backgroundScope,
            store,
            clock,
            running = RunningProcessSource { listOf(RunningProcess("sleep", 42L)) }
        )

        try {
            engine.start()
            runCurrent()
            repeat(5) {
                clock.advanceBoth(10_000L)
                engine.tickForTest()
            }

            val persistedSeconds = store.savedUsage[today]?.get("sleep")
            assertTrue(persistedSeconds in 40L..60L, "Expected credited usage, got $persistedSeconds")
        } finally {
            engine.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun firstAllowanceAfterFiveHoursOfIdleTimeDoesNotInheritThatTime() = runTest {
        val clock = MutableClock(TODAY, 1_000L, 1_000_000L)
        val store = FakeUsageStore()
        val engine = createEngine(
            backgroundScope,
            store,
            clock,
            running = RunningProcessSource { listOf(RunningProcess("sample.exe", 7L)) }
        )

        try {
            engine.start()
            runCurrent()
            clock.advanceBoth(5 * 60 * 60 * 1_000L)
            engine.tickForTest()

            store.allowanceList = listOf(DailyAllowance("sample.exe", "Sample", 9999))
            engine.reload()
            clock.advanceBoth(10_000L)
            engine.tickForTest()

            assertTrue(
                engine.getUsageMinutes("sample.exe") <= 1L,
                "A new allowance inherited ${engine.getUsageMinutes("sample.exe")} minutes"
            )
        } finally {
            engine.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun eightHourSleepGapCreditsNoUsage() = runTest {
        val clock = MutableClock(TODAY, 1_000L, 1_000_000L)
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 9999)))
        val engine = createEngine(
            backgroundScope,
            store,
            clock,
            running = RunningProcessSource { listOf(RunningProcess("sample.exe", 7L)) }
        )

        try {
            engine.start()
            runCurrent()
            clock.advanceBoth(8 * 60 * 60 * 1_000L)
            engine.tickForTest()

            assertEquals(0L, engine.getUsageMinutes("sample.exe"))
        } finally {
            engine.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nullForegroundTicksDoNotCountAndLaterForegroundStartsAtThatTick() = runTest {
        val clock = MutableClock(TODAY, 1_000L, 1_000_000L)
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 9999)))
        var foreground: ForegroundInfo? = null
        val engine = createEngine(
            backgroundScope,
            store,
            clock,
            running = RunningProcessSource { listOf(RunningProcess("sample.exe", 7L)) },
            foreground = ForegroundSource { foreground },
            isWindows = true
        )

        try {
            engine.start()
            runCurrent()
            repeat(3) {
                clock.advanceBoth(10_000L)
                engine.tickForTest()
            }
            foreground = ForegroundInfo("sample.exe", 7L)
            clock.advanceBoth(10_000L)
            engine.tickForTest()
            engine.stop()

            assertEquals(10L, store.savedUsage[TODAY]?.get("sample.exe"))
        } finally {
            engine.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sixtyTenPointThreeSecondTicksAccumulateMillisecondsWithoutPerTickTruncation() = runTest {
        val clock = MutableClock(TODAY, 1_000L, 1_000_000L)
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 9999)))
        val engine = createEngine(
            backgroundScope,
            store,
            clock,
            running = RunningProcessSource { listOf(RunningProcess("sample.exe", 7L)) }
        )

        try {
            engine.start()
            runCurrent()
            repeat(60) {
                clock.advanceBoth(10_300L)
                engine.tickForTest()
            }
            engine.stop()

            val persistedSeconds = store.savedUsage[TODAY]?.get("sample.exe")
            assertTrue(
                persistedSeconds in 615L..621L,
                "Expected 615–621 seconds without per-tick loss, got $persistedSeconds"
            )
        } finally {
            engine.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loopSurvivesProcessEnumerationException() = runTest {
        val calls = AtomicInteger()
        val unhandled = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, throwable -> unhandled += throwable }
        val scope = CoroutineScope(backgroundScope.coroutineContext + handler)
        val clock = SchedulerClock(testScheduler)
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 9999)))
        val engine = createEngine(
            scope,
            store,
            clock,
            running = RunningProcessSource {
                if (calls.incrementAndGet() == 1) error("temporary process scan failure")
                listOf(RunningProcess("sample.exe", 7L))
            }
        )

        engine.start()
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        engine.stop()

        assertTrue(calls.get() >= 2, "The tracker loop did not retry after a failed tick")
        assertTrue(unhandled.isEmpty(), "A recoverable tick error escaped the loop")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun failedUsageWriteIsRetriedOnTheNextFlush() = runTest {
        val clock = SchedulerClock(testScheduler)
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 9999))).apply {
            savedUsage[TODAY] = mutableMapOf("sample.exe" to 30L)
            failNextUpserts = 1
        }
        val unhandled = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, throwable -> unhandled += throwable }
        val engine = createEngine(
            CoroutineScope(backgroundScope.coroutineContext + handler),
            store,
            clock,
            running = RunningProcessSource { listOf(RunningProcess("sample.exe", 7L)) }
        )

        engine.start()
        runCurrent()
        advanceTimeBy(120_000L)
        runCurrent()
        engine.stop()

        assertTrue(store.upsertAttempts >= 2, "Failed usage write was not retried")
        assertTrue((store.savedUsage[TODAY]?.get("sample.exe") ?: 0L) > 30L)
        assertTrue(unhandled.isEmpty(), "A recoverable store error escaped the loop")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun unavailableUsageStoreDoesNotStartTracking() = runTest {
        val store = FakeUsageStore().apply { available = false }
        val engine = createEngine(backgroundScope, store, FixedClock)

        engine.start()
        runCurrent()

        assertEquals(0, store.allowanceReads)
        assertEquals(0, store.usageReads)
        engine.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun trackingDateDoesNotMoveBackwards() = runTest {
        val clock = MutableClock(LocalDate.of(2026, 1, 3), 1_000L, 1_000_000L)
        val store = FakeUsageStore()
        val engine = createEngine(backgroundScope, store, clock)

        try {
            engine.start()
            runCurrent()
            clock.currentDate = LocalDate.of(2026, 1, 2)
            clock.advanceBoth(10_000L)
            engine.tickForTest()

            assertTrue(store.deleteBeforeDates.isEmpty(), "A backwards date jump triggered cleanup")
        } finally {
            engine.stop()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun windowsDoesNotScanAllProcessesWhenNoAppNeedsKilling() = runTest {
        val scans = AtomicInteger()
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 9999)))
        val engine = createEngine(
            backgroundScope,
            store,
            FixedClock,
            running = RunningProcessSource {
                scans.incrementAndGet()
                listOf(RunningProcess("sample.exe", 7L))
            },
            foreground = ForegroundSource { null },
            isWindows = true
        )

        engine.start()
        runCurrent()
        engine.stop()

        assertEquals(0, scans.get())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loopRetriesKillerAfterARecoverableFailure() = runTest {
        val killerCalls = AtomicInteger()
        val unhandled = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, throwable -> unhandled += throwable }
        val store = FakeUsageStore(listOf(DailyAllowance("sample.exe", "Sample", 1))).apply {
            savedUsage[TODAY] = mutableMapOf("sample.exe" to 60L)
        }
        val engine = createEngine(
            CoroutineScope(backgroundScope.coroutineContext + handler),
            store,
            SchedulerClock(testScheduler),
            running = RunningProcessSource { listOf(RunningProcess("sample.exe", 7L)) },
            killer = ProcessKiller {
                if (killerCalls.incrementAndGet() == 1) error("temporary kill failure")
            }
        )

        engine.start()
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        engine.stop()

        assertTrue(killerCalls.get() >= 2, "The tracker did not retry killing on a later tick")
        assertTrue(unhandled.isEmpty(), "A recoverable killer error escaped the loop")
    }

    private fun createEngine(
        scope: CoroutineScope,
        store: FakeUsageStore,
        clock: Clock,
        running: RunningProcessSource = RunningProcessSource { emptyList() },
        foreground: ForegroundSource = ForegroundSource { null },
        killer: ProcessKiller = ProcessKiller { },
        isWindows: Boolean = false,
        blockedSet: BlockedSetSink = BlockedSetSink { }
    ) = AllowanceEngine(
        AllowancePorts(
            clock = clock,
            foregroundSource = foreground,
            runningProcessSource = running,
            processKiller = killer,
            breakState = object : BreakState {
                override val isActive = MutableStateFlow(false)
            },
            usageStore = store,
            blockedSetSink = blockedSet,
            isWindows = isWindows
        ),
        scope
    )

    private object FixedClock : Clock {
        override fun wallMs(): Long = 1_000L
        override fun monoNs(): Long = 1_000_000L
        override fun today(): LocalDate = TODAY
    }

    private class MutableClock(
        var currentDate: LocalDate,
        var currentWallMs: Long,
        var currentMonoNs: Long
    ) : Clock {
        override fun wallMs(): Long = currentWallMs
        override fun monoNs(): Long = currentMonoNs
        override fun today(): LocalDate = currentDate

        fun advanceBoth(deltaMs: Long) {
            currentWallMs += deltaMs
            currentMonoNs += deltaMs * 1_000_000L
        }
    }

    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
        private val date: LocalDate = TODAY
    ) : Clock {
        override fun wallMs(): Long = 1_000L + scheduler.currentTime
        override fun monoNs(): Long = 1_000_000L + scheduler.currentTime * 1_000_000L
        override fun today(): LocalDate = date
    }

    private class FakeUsageStore(
        var allowanceList: List<DailyAllowance> = emptyList()
    ) : UsageStore {
        var available = true
        var allowanceReads = 0
        var usageReads = 0
        var failNextUpserts = 0
        var upsertAttempts = 0
        val deleteBeforeDates = mutableListOf<LocalDate>()
        val savedUsage = mutableMapOf<LocalDate, MutableMap<String, Long>>()

        override fun isAvailable() = available

        override fun allowances() = allowanceList.also {
            allowanceReads++
        }

        override fun usage(date: LocalDate) = savedUsage[date].orEmpty().toMap().also {
            usageReads++
        }

        override fun upsertUsage(date: LocalDate, processName: String, seconds: Long) {
            upsertAttempts++
            if (failNextUpserts > 0) {
                failNextUpserts--
                error("temporary usage write failure")
            }
            savedUsage.getOrPut(date, ::mutableMapOf)[processName] = seconds
        }

        override fun deleteUsageBefore(date: LocalDate) {
            deleteBeforeDates += date
        }
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 1, 2)
    }
}
