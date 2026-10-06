package com.focusflow.ui.screens

import com.focusflow.data.models.DailyAllowance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AllowanceUiStateTest {

    @Test
    fun failedLoadPreservesLastKnownDataAndCancellationStillPropagates() = runBlocking {
        val previous = listOf(DailyAllowance("chat.exe", "Chat", 30))

        val failed = loadAllowanceData(previous) {
            error("database unavailable")
        }

        assertIs<AllowanceLoadState.Failed<List<DailyAllowance>>>(failed)
        assertEquals(previous, failed.previous)
        assertFailsWith<CancellationException> {
            loadAllowanceData(previous) { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun failedMutationIsReportedAndCancellationIsNotConvertedToFailure() = runBlocking {
        val failed = runAllowanceMutation { error("write failed") }

        assertIs<AllowanceMutationResult.Failure>(failed)
        assertFailsWith<CancellationException> {
            runAllowanceMutation { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun usageSummaryCountsDistinctAllowancesBlockedToday() {
        val summary = summarizeAllowanceUsage(
            allowances = listOf(
                DailyAllowance(" Chat.EXE ", "Chat", 30),
                DailyAllowance("chat.exe", "Duplicate", 15),
                DailyAllowance("browser.exe", "Browser", 60)
            ),
            blockedProcesses = setOf("CHAT.EXE")
        )

        assertEquals(AllowanceUsageSummary(appCount = 2, blockedTodayCount = 1), summary)
    }

    @Test
    fun allowanceSummaryShowsLoadingAndUnavailableInsteadOfZeroCounts() {
        val zeroSummary = AllowanceUsageSummary(appCount = 0, blockedTodayCount = 0)
        val format = "{apps} apps · {blocked} blocked today"

        assertEquals(
            "Loading allowances",
            allowanceSummaryLabel(
                AllowanceLoadState.Loading(),
                format,
                loadingText = "Loading allowances",
                unavailableText = "Unavailable · Retry"
            )
        )
        assertEquals(
            "Unavailable · Retry",
            allowanceSummaryLabel(
                AllowanceLoadState.Failed(null, IllegalStateException("database unavailable")),
                format,
                loadingText = "Loading allowances",
                unavailableText = "Unavailable · Retry"
            )
        )
        assertEquals(
            "0 apps · 0 blocked today",
            allowanceSummaryLabel(
                AllowanceLoadState.Loaded(zeroSummary),
                format,
                loadingText = "Loading allowances",
                unavailableText = "Unavailable · Retry"
            )
        )
    }
}
