package com.focusflow.services

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessCommandCacheTest {
    @Test
    fun cachesCommandForSamePidAndStartInstant() {
        val cache = ProcessCommandCache()
        val startedAt = Instant.parse("2026-10-14T10:00:00Z")
        var reads = 0

        val first = cache.command(123L, startedAt) { reads++; "C:\\Apps\\one.exe" }
        val second = cache.command(123L, startedAt) { reads++; "C:\\Apps\\changed.exe" }

        assertEquals("C:\\Apps\\one.exe", first)
        assertEquals(first, second)
        assertEquals(1, reads)
    }

    @Test
    fun pidReuseWithDifferentStartInstantReadsFreshCommand() {
        val cache = ProcessCommandCache()
        val firstStart = Instant.parse("2026-10-14T10:00:00Z")
        val secondStart = Instant.parse("2026-10-14T10:01:00Z")
        var reads = 0

        val first = cache.command(123L, firstStart) { reads++; "C:\\Apps\\one.exe" }
        val second = cache.command(123L, secondStart) { reads++; "C:\\Apps\\two.exe" }

        assertEquals("C:\\Apps\\one.exe", first)
        assertEquals("C:\\Apps\\two.exe", second)
        assertEquals(2, reads)
    }

    @Test
    fun missingStartInstantDoesNotCacheCommand() {
        val cache = ProcessCommandCache()
        var reads = 0

        cache.command(123L, null) { reads++; "C:\\Apps\\one.exe" }
        cache.command(123L, null) { reads++; "C:\\Apps\\two.exe" }

        assertEquals(2, reads)
    }
}
