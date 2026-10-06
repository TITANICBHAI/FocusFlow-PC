package com.focusflow.services.allowance

import java.time.LocalDate

data class ForegroundCredit(
    val key: String,
    val date: LocalDate,
    val elapsedNs: Long
)

data class ForegroundLedgerUpdate(
    val credits: List<ForegroundCredit> = emptyList(),
    val missedEvent: Boolean = false,
    val discardedGap: Boolean = false,
    val dateChangedTo: LocalDate? = null
)

/**
 * Monotonic-time ledger for foreground intervals. It has no OS, clock, or
 * persistence dependencies; callers supply samples and decide how to apply
 * credits and date changes.
 */
class ForegroundLedger(
    private val maxGapNs: Long = DEFAULT_MAX_GAP_NS
) {
    private var currentKey: String? = null
    private var openedAtNs: Long? = null
    private var trackingDate: LocalDate? = null

    @Synchronized
    fun onForeground(key: String?, nowNs: Long, date: LocalDate): ForegroundLedgerUpdate =
        transition(key, nowNs, date, missedEvent = false, flush = false)

    /**
     * Reconcile the event stream with a sampled foreground process and flush
     * the active interval. A changed sample means at least one hook event was
     * missed; the error is bounded by the heartbeat cadence.
     */
    @Synchronized
    fun heartbeat(nowNs: Long, sampledKey: String?, date: LocalDate): ForegroundLedgerUpdate =
        transition(
            sampledKey,
            nowNs,
            date,
            missedEvent = currentKey != sampledKey,
            flush = true
        )

    @Synchronized
    fun flush(nowNs: Long, date: LocalDate): ForegroundLedgerUpdate =
        transition(currentKey, nowNs, date, missedEvent = false, flush = true)

    @Synchronized
    fun currentForeground(): String? = currentKey

    @Synchronized
    fun reset() {
        currentKey = null
        openedAtNs = null
        trackingDate = null
    }

    private fun transition(
        nextKey: String?,
        nowNs: Long,
        observedDate: LocalDate,
        missedEvent: Boolean,
        flush: Boolean
    ): ForegroundLedgerUpdate {
        val previousDate = trackingDate
        val targetDate = if (previousDate == null || observedDate.isAfter(previousDate)) {
            observedDate
        } else {
            previousDate
        }
        val dateChanged = previousDate != null && targetDate.isAfter(previousDate)
        val credits = mutableListOf<ForegroundCredit>()
        var discardedGap = false

        if (currentKey != null && openedAtNs != null) {
            val elapsedNs = nowNs - openedAtNs!!
            if (elapsedNs < 0L || elapsedNs > maxGapNs) {
                discardedGap = true
            } else if (elapsedNs > 0L) {
                credits += ForegroundCredit(currentKey!!, previousDate ?: targetDate, elapsedNs)
            }
        }

        trackingDate = targetDate
        if (flush || currentKey != nextKey || dateChanged || openedAtNs == null) {
            openedAtNs = nowNs
        }
        currentKey = nextKey

        return ForegroundLedgerUpdate(
            credits = credits,
            missedEvent = missedEvent,
            discardedGap = discardedGap,
            dateChangedTo = if (dateChanged) targetDate else null
        )
    }

    private companion object {
        const val DEFAULT_MAX_GAP_NS = 25_000_000_000L
    }
}
