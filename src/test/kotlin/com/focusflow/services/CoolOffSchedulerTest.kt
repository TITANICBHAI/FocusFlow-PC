package com.focusflow.services

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoolOffSchedulerTest {
    @Test
    fun runsTheActionOnceAfterTheDelay() {
        val scheduler = CoolOffScheduler()
        val ran = AtomicInteger(0)
        val done = CountDownLatch(1)
        assertTrue(scheduler.schedule("a", 50) { ran.incrementAndGet(); done.countDown() })
        assertTrue(scheduler.isPending("a"))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, ran.get())
        assertFalse(scheduler.isPending("a"))
    }

    @Test
    fun aCancelledCoolOffNeverRuns() {
        val scheduler = CoolOffScheduler()
        val ran = AtomicInteger(0)
        scheduler.schedule("a", 150) { ran.incrementAndGet() }
        assertTrue(scheduler.cancel("a"))
        assertFalse(scheduler.cancel("a"), "nothing left to cancel")
        Thread.sleep(400)
        assertEquals(0, ran.get())
        assertFalse(scheduler.isPending("a"))
    }

    @Test
    fun aSecondRequestForTheSameKeyIsRefusedWhileOneIsPending() {
        val scheduler = CoolOffScheduler()
        assertTrue(scheduler.schedule("a", 60_000) { })
        assertFalse(scheduler.schedule("a", 60_000) { })
        assertTrue(scheduler.schedule("b", 60_000) { }, "other keys are independent")
        scheduler.cancel("a"); scheduler.cancel("b")
    }

    @Test
    fun remainingTimeCountsDownAndNeverGoesNegative() {
        var now = 0L
        val scheduler = CoolOffScheduler(nanoTime = { now })
        scheduler.schedule("a", 600_000) { }
        assertEquals(600_000L, scheduler.remainingMs("a"))
        now = 100_000L * 1_000_000L
        assertEquals(500_000L, scheduler.remainingMs("a"))
        now = 9_999_999L * 1_000_000L
        assertEquals(0L, scheduler.remainingMs("a"))
        assertEquals(0L, scheduler.remainingMs("missing"))
        scheduler.cancel("a")
    }

    @Test
    fun aFailingActionDoesNotBreakTheScheduler() {
        val scheduler = CoolOffScheduler()
        val done = CountDownLatch(1)
        scheduler.schedule("bad", 20) { error("boom") }
        scheduler.schedule("good", 80) { done.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS))
    }
}
