package com.focusflow.testutil

import com.focusflow.services.TelemetrySink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

internal class FakeTelemetrySink(expectedPosts: Int) : TelemetrySink {
    private val completedPosts = CountDownLatch(expectedPosts)
    private val capturedPayloads = CopyOnWriteArrayList<String>()

    override fun post(payloadJson: String) {
        capturedPayloads += payloadJson
        completedPosts.countDown()
    }

    fun awaitPosts(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS): Boolean =
        completedPosts.await(timeout, unit)

    fun payloads(): List<String> = capturedPayloads.toList()
}
