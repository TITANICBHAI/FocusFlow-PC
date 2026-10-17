package com.focusflow.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProcessSnapshotTest {
    @Test
    fun sharesOneSnapshotUntilTheTtlExpires() {
        var nowMs = 1_000L
        var loads = 0
        val refreshDurations = mutableListOf<Long>()
        val cache = ProcessSnapshotCache(
            ttlMs = 250L,
            nowMs = { nowMs },
            load = {
                loads++
                listOf(ProcessSnapshotEntry(pid = loads.toLong()))
            },
            onRefreshDurationMs = refreshDurations::add
        )

        val first = cache.snapshot()
        nowMs = 1_249L
        val stillFresh = cache.snapshot()
        nowMs = 1_250L
        val refreshed = cache.snapshot()
        val forced = cache.snapshot(forceRefresh = true)

        assertSame(first, stillFresh)
        assertEquals(1L, first.single().pid)
        assertEquals(2L, refreshed.single().pid)
        assertNotSame(refreshed, forced)
        assertEquals(3L, forced.single().pid)
        assertEquals(3, loads)
        assertEquals(3, refreshDurations.size)
    }

    @Test
    fun recordsOneRuntimeSnapshotSampleWithoutProcessNames() {
        val startedAtNanos = System.nanoTime()
        val snapshot = ProcessSnapshot.snapshotProcesses()
        val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
        val currentPid = ProcessHandle.current().pid()

        assertTrue(snapshot.any { it.pid == currentPid }, "the snapshot should include this test worker")
        println("P1.5 process snapshot sample: processCount=${snapshot.size}, durationMs=$durationMs")
    }
}
