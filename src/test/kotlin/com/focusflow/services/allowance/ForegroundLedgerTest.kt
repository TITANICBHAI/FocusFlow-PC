package com.focusflow.services.allowance

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundLedgerTest {
    @Test
    fun creditsIntervalsToTheForegroundAtEachSwitch() {
        val ledger = ForegroundLedger()
        val credits = mutableListOf<ForegroundCredit>()

        credits += ledger.onForeground("a.exe", 0L, DAY).credits
        credits += ledger.onForeground("b.exe", 3_000_000_000L, DAY).credits
        credits += ledger.onForeground("a.exe", 10_000_000_000L, DAY).credits
        credits += ledger.flush(15_000_000_000L, DAY).credits

        assertEquals(
            listOf(
                ForegroundCredit("a.exe", DAY, 3_000_000_000L),
                ForegroundCredit("b.exe", DAY, 7_000_000_000L),
                ForegroundCredit("a.exe", DAY, 5_000_000_000L)
            ),
            credits
        )
    }

    @Test
    fun preservesSubSecondSwitchIntervals() {
        val ledger = ForegroundLedger()
        ledger.onForeground("a.exe", 0L, DAY)

        val switched = ledger.onForeground("b.exe", 250_000_000L, DAY)
        val flushed = ledger.flush(900_000_000L, DAY)

        assertEquals(250_000_000L, switched.credits.single().elapsedNs)
        assertEquals(650_000_000L, flushed.credits.single().elapsedNs)
        assertEquals("b.exe", flushed.credits.single().key)
    }

    @Test
    fun nullForegroundCreditsNobody() {
        val ledger = ForegroundLedger()
        ledger.onForeground("a.exe", 0L, DAY)

        val result = ledger.onForeground(null, 2_000_000_000L, DAY)

        assertEquals(2_000_000_000L, result.credits.single().elapsedNs)
        assertEquals("a.exe", result.credits.single().key)
        assertTrue(ledger.flush(5_000_000_000L, DAY).credits.isEmpty())
    }

    @Test
    fun heartbeatCorrectsMissedForegroundEventAndFlushesOpenInterval() {
        val ledger = ForegroundLedger()
        ledger.onForeground("a.exe", 0L, DAY)

        val result = ledger.heartbeat(8_000_000_000L, "b.exe", DAY)

        assertTrue(result.missedEvent)
        assertEquals(listOf(ForegroundCredit("a.exe", DAY, 8_000_000_000L)), result.credits)
        assertEquals(
            listOf(ForegroundCredit("b.exe", DAY, 4_000_000_000L)),
            ledger.heartbeat(12_000_000_000L, "b.exe", DAY).credits
        )
    }

    @Test
    fun discardsNegativeAndOverlongGapsWithoutCarryingThemForward() {
        val ledger = ForegroundLedger(maxGapNs = 25_000_000_000L)
        ledger.onForeground("a.exe", 0L, DAY)

        val longGap = ledger.heartbeat(30_000_000_000L, "a.exe", DAY)
        val afterGap = ledger.heartbeat(35_000_000_000L, "a.exe", DAY)
        val backwards = ledger.heartbeat(34_000_000_000L, "a.exe", DAY)

        assertTrue(longGap.discardedGap)
        assertEquals(5_000_000_000L, afterGap.credits.single().elapsedNs)
        assertTrue(backwards.discardedGap)
        assertEquals(
            2_000_000_000L,
            ledger.flush(36_000_000_000L, DAY).credits.single().elapsedNs,
            "The new baseline after a backwards clock observation should be countable"
        )
    }

    @Test
    fun rolloverCreditsTheOpenIntervalToTheOldDayThenStartsTheNewDay() {
        val nextDay = DAY.plusDays(1)
        val ledger = ForegroundLedger()
        ledger.onForeground("a.exe", 0L, DAY)

        val rollover = ledger.heartbeat(10_000_000_000L, "a.exe", nextDay)
        val nextDayCredit = ledger.flush(12_000_000_000L, nextDay)

        assertEquals(listOf(ForegroundCredit("a.exe", DAY, 10_000_000_000L)), rollover.credits)
        assertEquals(nextDay, rollover.dateChangedTo)
        assertEquals(listOf(ForegroundCredit("a.exe", nextDay, 2_000_000_000L)), nextDayCredit.credits)
        assertFalse(nextDayCredit.dateChangedTo != null)
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 1, 2)
    }
}
