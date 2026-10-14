package com.focusflow.services

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.util.concurrent.TimeUnit

class RelaunchHandoffTest {

    @TempDir
    lateinit var tempDir: Path

    private var nowMs = 1_000L
    private var nowNanos = 1_000_000L
    private val store by lazy {
        RelaunchHandoffStore(
            directory = tempDir.toFile(),
            monotonicNanos = { nowNanos },
            nowMs = { nowMs }
        )
    }

    @Test
    fun onlyFreshMarkersCanBeAccepted() {
        val marker = assertNotNull(store.begin(oldPid = 100, userName = "tester", nonce = "fresh"))
        assertEquals(marker, store.currentMarker())

        nowMs += RelaunchHandoffStore.MAX_MARKER_AGE_MS + 1
        assertNull(store.currentMarker())
        assertNull(store.acceptIncoming("fresh", currentPid = 200, userName = "tester", elevated = true))
    }

    @Test
    fun helloRequiresMatchingNonceUserAndElevatedChild() {
        val marker = assertNotNull(store.begin(oldPid = 100, userName = "tester", nonce = "nonce"))

        assertNull(store.acceptIncoming("nonce", currentPid = 200, userName = "tester", elevated = false))
        assertNull(store.acceptIncoming("nonce", currentPid = 200, userName = "other", elevated = true))
        assertNull(store.acceptIncoming("wrong", currentPid = 200, userName = "tester", elevated = true))
        assertNull(store.acceptIncoming("nonce", currentPid = 100, userName = "tester", elevated = true))

        assertNotNull(store.acceptIncoming("nonce", currentPid = 200, userName = "tester", elevated = true))
        assertTrue(store.awaitHello(marker, timeoutMs = 0))
    }

    @Test
    fun timeoutLeavesOldInstanceMarkerForTheRunningProcess() {
        val marker = assertNotNull(store.begin(oldPid = 100, userName = "tester", nonce = "timeout"))

        assertFalse(
            store.awaitHello(
                marker = marker,
                timeoutMs = 25,
                pollIntervalMs = 10,
                sleep = { elapsedMs -> nowNanos += TimeUnit.MILLISECONDS.toNanos(elapsedMs) }
            )
        )
        assertEquals(marker, store.currentMarker())
    }

    @Test
    fun clearingAfterDeclineOrFailureRemovesMarkerAndHello() {
        val marker = assertNotNull(store.begin(oldPid = 100, userName = "tester", nonce = "cleanup"))
        assertNotNull(store.acceptIncoming("cleanup", currentPid = 200, userName = "tester", elevated = true))

        store.clear(marker)

        assertNull(store.currentMarker())
        assertFalse(store.awaitHello(marker, timeoutMs = 0))
    }
}
