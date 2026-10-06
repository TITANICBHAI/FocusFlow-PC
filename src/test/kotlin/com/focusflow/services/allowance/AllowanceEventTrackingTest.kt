package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import com.focusflow.enforcement.ForegroundEvent
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AllowanceEventTrackingTest {
    @Test
    fun foregroundSwitchEventsCreditExactIntervalsAndFlushOnSwitchAway() = runTest {
        val clock = MutableClock()
        val store = MemoryUsageStore(
            listOf(
                DailyAllowance("a.exe", "App A", 100),
                DailyAllowance("b.exe", "App B", 100)
            )
        )
        val events = FakeForegroundEvents()
        var foreground: ForegroundInfo? = null
        val engine = createEngine(
            backgroundScope,
            clock,
            store,
            events,
            ForegroundSource { foreground }
        )

        engine.start()
        runCurrent()
        events.emit("a.exe", 11L, clock.monoNs())
        runCurrent()

        clock.advance(3_000L)
        foreground = ForegroundInfo("b.exe", 12L)
        events.emit("b.exe", 12L, clock.monoNs())
        runCurrent()

        clock.advance(7_000L)
        foreground = ForegroundInfo("a.exe", 11L)
        events.emit("a.exe", 11L, clock.monoNs())
        runCurrent()
        assertEquals(3L, store.saved[TODAY]?.get("a.exe"))
        assertEquals(7L, store.saved[TODAY]?.get("b.exe"))

        clock.advance(5_000L)
        engine.stop()

        assertEquals(8L, store.saved[TODAY]?.get("a.exe"))
        assertEquals(7L, store.saved[TODAY]?.get("b.exe"))
    }

    @Test
    fun heartbeatFlushesTheOpenIntervalWithoutDoubleCounting() = runTest {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryUsageStore(listOf(DailyAllowance("a.exe", "App A", 100)))
        val events = FakeForegroundEvents()
        val engine = createEngine(
            backgroundScope,
            clock,
            store,
            events,
            ForegroundSource { ForegroundInfo("a.exe", 11L) }
        )

        engine.start()
        runCurrent()
        events.emit("a.exe", 11L, clock.monoNs())
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        engine.stop()

        assertEquals(10L, store.saved[TODAY]?.get("a.exe"))
    }

    @Test
    fun periodicFlushPersistsAnOpenForegroundInterval() = runTest {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryUsageStore(listOf(DailyAllowance("a.exe", "App A", 100)))
        val events = FakeForegroundEvents()
        val engine = createEngine(
            backgroundScope,
            clock,
            store,
            events,
            ForegroundSource { ForegroundInfo("a.exe", 11L) }
        )

        engine.start()
        runCurrent()
        events.emit("a.exe", 11L, clock.monoNs())
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(60L, store.saved[TODAY]?.get("a.exe"))
        engine.stop()
    }

    @Test
    fun endingEmergencyBreakFlushesUsageImmediately() = runTest {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryUsageStore(listOf(DailyAllowance("a.exe", "App A", 100)))
        val events = FakeForegroundEvents()
        val breakActive = MutableStateFlow(true)
        val engine = AllowanceEngine(
            AllowancePorts(
                clock = clock,
                foregroundSource = ForegroundSource { ForegroundInfo("a.exe", 11L) },
                runningProcessSource = RunningProcessSource { emptyList() },
                processKiller = ProcessKiller { },
                breakState = object : BreakState {
                    override val isActive = breakActive
                },
                usageStore = store,
                blockedSetSink = BlockedSetSink { },
                isWindows = true,
                foregroundEvents = events
            ),
            backgroundScope
        )

        engine.start()
        runCurrent()
        events.emit("a.exe", 11L, clock.monoNs())
        runCurrent()
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(store.saved[TODAY].isNullOrEmpty())

        breakActive.value = false
        runCurrent()

        assertEquals(30L, store.saved[TODAY]?.get("a.exe"))
        engine.stop()
    }

    @Test
    fun endingEmergencyBreakFlushesTheOpenForegroundInterval() = runTest {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryUsageStore(listOf(DailyAllowance("a.exe", "App A", 100)))
        val events = FakeForegroundEvents()
        val breakActive = MutableStateFlow(true)
        val engine = AllowanceEngine(
            AllowancePorts(
                clock = clock,
                foregroundSource = ForegroundSource { ForegroundInfo("a.exe", 11L) },
                runningProcessSource = RunningProcessSource { emptyList() },
                processKiller = ProcessKiller { },
                breakState = object : BreakState {
                    override val isActive = breakActive
                },
                usageStore = store,
                blockedSetSink = BlockedSetSink { },
                isWindows = true,
                foregroundEvents = events
            ),
            backgroundScope
        )

        engine.start()
        runCurrent()
        events.emit("a.exe", 11L, clock.monoNs())
        runCurrent()
        advanceTimeBy(35_000L)
        runCurrent()

        breakActive.value = false
        runCurrent()

        assertEquals(35L, store.saved[TODAY]?.get("a.exe"))
        engine.stop()
    }

    @Test
    fun diagnosticsIncludeForegroundAndReconciledHeartbeatMisses() = runTest {
        val clock = SchedulerClock(testScheduler)
        val store = MemoryUsageStore(listOf(DailyAllowance("a.exe", "App A", 100)))
        val events = FakeForegroundEvents()
        val diagnostics = mutableListOf<AllowanceTrackingDiagnostics>()
        var foreground: ForegroundInfo? = ForegroundInfo("a.exe", 11L)
        val engine = createEngine(
            backgroundScope,
            clock,
            store,
            events,
            ForegroundSource { foreground },
            diagnostics
        )

        engine.start()
        runCurrent()
        events.emit("b.exe", 12L, clock.monoNs())
        runCurrent()
        advanceTimeBy(120_000L)
        runCurrent()
        foreground = ForegroundInfo("a.exe", 11L)
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(diagnostics.isNotEmpty())
        assertEquals("a.exe", diagnostics.last().currentForeground)
        assertTrue(diagnostics.last().missedEventCount > 0)
        engine.stop()
    }

    @Test
    fun diagnosticsAreRateLimitedDuringRapidForegroundSwitches() = runTest {
        val clock = MutableClock()
        val store = MemoryUsageStore(listOf(DailyAllowance("a.exe", "App A", 100)))
        val events = FakeForegroundEvents()
        val diagnostics = mutableListOf<AllowanceTrackingDiagnostics>()
        val engine = createEngine(
            backgroundScope,
            clock,
            store,
            events,
            ForegroundSource { ForegroundInfo("a.exe", 11L) },
            diagnostics
        )

        engine.start()
        runCurrent()
        repeat(12) {
            events.emit("a.exe", 11L, clock.monoNs())
            runCurrent()
            clock.advance(1_000L)
        }
        assertEquals(1, diagnostics.size)

        clock.advance(60_000L)
        repeat(6) {
            events.emit("a.exe", 11L, clock.monoNs())
            runCurrent()
            clock.advance(1_000L)
        }

        assertEquals(2, diagnostics.size)
        engine.stop()
    }

    private fun createEngine(
        scope: CoroutineScope,
        clock: Clock,
        store: UsageStore,
        events: FakeForegroundEvents,
        foreground: ForegroundSource,
        diagnostics: MutableList<AllowanceTrackingDiagnostics> = mutableListOf()
    ) = AllowanceEngine(
        AllowancePorts(
            clock = clock,
            foregroundSource = foreground,
            runningProcessSource = RunningProcessSource { emptyList() },
            processKiller = ProcessKiller { },
            breakState = object : BreakState {
                override val isActive = MutableStateFlow(false)
            },
            usageStore = store,
            blockedSetSink = BlockedSetSink { },
            isWindows = true,
            foregroundEvents = events,
            diagnosticsSink = AllowanceTrackingDiagnosticsSink { diagnostics += it }
        ),
        scope
    )

    private class FakeForegroundEvents : ForegroundEventSource {
        private var nextId = 0L
        private val listeners = mutableMapOf<Long, (ForegroundEvent) -> Unit>()

        override fun addListener(listener: (ForegroundEvent) -> Unit): Long {
            val id = ++nextId
            listeners[id] = listener
            return id
        }

        override fun removeListener(listenerId: Long) {
            listeners.remove(listenerId)
        }

        fun emit(exe: String?, pid: Long, monoNs: Long) {
            val event = ForegroundEvent(exe, pid, monoNs)
            listeners.values.toList().forEach { it(event) }
        }
    }

    private class MutableClock : Clock {
        private var wall = 1_000L
        private var mono = 1_000_000L
        override fun wallMs() = wall
        override fun monoNs() = mono
        override fun today() = TODAY
        fun advance(ms: Long) {
            wall += ms
            mono += ms * 1_000_000L
        }
    }

    private class SchedulerClock(
        private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    ) : Clock {
        override fun wallMs() = 1_000L + scheduler.currentTime
        override fun monoNs() = 1_000_000L + scheduler.currentTime * 1_000_000L
        override fun today() = TODAY
    }

    private class MemoryUsageStore(
        private val allowanceList: List<DailyAllowance>
    ) : UsageStore {
        val saved = mutableMapOf<LocalDate, MutableMap<String, Long>>()
        override fun allowances() = allowanceList
        override fun usage(date: LocalDate) = saved[date].orEmpty().toMap()
        override fun upsertUsage(date: LocalDate, processName: String, seconds: Long) {
            saved.getOrPut(date, ::mutableMapOf)[processName] = seconds
        }
        override fun deleteUsageBefore(date: LocalDate) = Unit
    }

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 1, 2)
    }
}
