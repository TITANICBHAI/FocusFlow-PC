package com.focusflow.services

import com.focusflow.data.models.StandaloneBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The standalone-block countdown is derived from a ticking "now" in the UI, so the time maths
 * must be a pure function of that "now". These tests pin the clock instead of using real time.
 */
class StandaloneBlockTimeTest {
    private val apps = listOf("game.exe")

    @Test
    fun remainingCountsDownAsNowAdvances() {
        val block = StandaloneBlock(apps, untilMs = 10_000L)
        assertEquals(10_000L, block.remainingMsAt(0L))
        assertEquals(9_000L, block.remainingMsAt(1_000L))
        assertEquals(1_000L, block.remainingMsAt(9_000L))
    }

    @Test
    fun remainingNeverGoesNegativeAfterTheBlockEnds() {
        val block = StandaloneBlock(apps, untilMs = 10_000L)
        assertEquals(0L, block.remainingMsAt(10_000L))
        assertEquals(0L, block.remainingMsAt(99_999L))
    }

    @Test
    fun immediateBlockIsActiveUntilItEnds() {
        val block = StandaloneBlock(apps, untilMs = 10_000L, startMs = null)
        assertTrue(block.isActiveAt(0L))
        assertTrue(block.isActiveAt(9_999L))
        assertFalse(block.isActiveAt(10_000L))
        assertFalse(block.isScheduledAt(10_000L))
    }

    @Test
    fun scheduledBlockWaitsThenBecomesActiveWithoutAnyOtherEvent() {
        val block = StandaloneBlock(apps, untilMs = 20_000L, startMs = 5_000L)

        // Before the start: configured (scheduled) but not enforcing; counts down to the start.
        assertTrue(block.isScheduledAt(1_000L))
        assertFalse(block.isActiveAt(1_000L))
        assertEquals(4_000L, block.startsInMsAt(1_000L))

        // At/after the start: enforcing, no longer "starts in".
        assertTrue(block.isActiveAt(5_000L))
        assertEquals(0L, block.startsInMsAt(5_000L))
        assertEquals(15_000L, block.remainingMsAt(5_000L))
    }

    @Test
    fun startsInIsZeroForAnImmediateBlock() {
        assertEquals(0L, StandaloneBlock(apps, untilMs = 10_000L).startsInMsAt(1_000L))
    }

    @Test
    fun blockWithNoAppsIsNeverActiveOrScheduled() {
        val empty = StandaloneBlock(emptyList(), untilMs = 10_000L)
        assertFalse(empty.isActiveAt(0L))
        assertFalse(empty.isScheduledAt(0L))
    }

    @Test
    fun serviceDefaultsStillFollowTheRealClockWhenNoBlockIsLoaded() {
        StandaloneBlockService.resetForTest()
        assertEquals(0L, StandaloneBlockService.remainingMs())
        assertEquals(0L, StandaloneBlockService.startsInMs())
        assertFalse(StandaloneBlockService.isActive)
        assertFalse(StandaloneBlockService.isScheduled)
    }
}
