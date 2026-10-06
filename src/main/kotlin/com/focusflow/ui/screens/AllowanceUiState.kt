package com.focusflow.ui.screens

import com.focusflow.data.models.DailyAllowance
import kotlinx.coroutines.CancellationException

internal sealed interface AllowanceLoadState<out T> {
    data class Loading<T>(val previous: T? = null) : AllowanceLoadState<T>
    data class Loaded<T>(val value: T) : AllowanceLoadState<T>
    data class Failed<T>(val previous: T?, val cause: Exception) : AllowanceLoadState<T>
}

internal fun <T> AllowanceLoadState<T>.lastKnownValue(): T? = when (this) {
    is AllowanceLoadState.Loading -> previous
    is AllowanceLoadState.Loaded -> value
    is AllowanceLoadState.Failed -> previous
}

internal suspend fun <T> loadAllowanceData(
    previous: T?,
    load: suspend () -> T
): AllowanceLoadState<T> = try {
    AllowanceLoadState.Loaded(load())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    AllowanceLoadState.Failed(previous, failure)
}

internal sealed interface AllowanceMutationResult {
    data object Success : AllowanceMutationResult
    data class Failure(val cause: Exception) : AllowanceMutationResult
}

internal suspend fun runAllowanceMutation(
    mutation: suspend () -> Unit
): AllowanceMutationResult = try {
    mutation()
    AllowanceMutationResult.Success
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    AllowanceMutationResult.Failure(failure)
}

internal data class AllowanceUsageSummary(
    val appCount: Int,
    val blockedTodayCount: Int
)

internal fun formatAllowanceSummary(
    format: String,
    summary: AllowanceUsageSummary
): String = format
    .replace("{apps}", summary.appCount.toString())
    .replace("{blocked}", summary.blockedTodayCount.toString())

internal fun allowanceSummaryLabel(
    state: AllowanceLoadState<AllowanceUsageSummary>,
    format: String,
    loadingText: String,
    unavailableText: String
): String = when (state) {
    is AllowanceLoadState.Loading ->
        state.previous?.let { formatAllowanceSummary(format, it) } ?: loadingText
    is AllowanceLoadState.Loaded -> formatAllowanceSummary(format, state.value)
    is AllowanceLoadState.Failed -> unavailableText
}

internal fun summarizeAllowanceUsage(
    allowances: List<DailyAllowance>,
    blockedProcesses: Set<String>
): AllowanceUsageSummary {
    fun normalize(value: String) = value.trim().lowercase()
    val blocked = blockedProcesses.map(::normalize).toSet()
    val uniqueAllowances = allowances.distinctBy { normalize(it.processName) }
    return AllowanceUsageSummary(
        appCount = uniqueAllowances.size,
        blockedTodayCount = uniqueAllowances.count { normalize(it.processName) in blocked }
    )
}
