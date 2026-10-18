package com.yavin.wakeupsamurai.domain.alarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextOccurrenceCalculatorTest {
    private val calculator = NextOccurrenceCalculator()
    private val utc = ZoneId.of("UTC")

    @Test fun oneShotUsesTodayWhenTimeIsStillAhead() {
        val result = calculator.oneShotStrictlyAfter(Instant.parse("2026-10-13T06:00:00Z"), utc, LocalTime.of(7, 0))
        assertEquals(Instant.parse("2026-10-13T07:00:00Z"), result)
    }

    @Test fun oneShotRollsToTomorrowAtExactTime() {
        val result = calculator.oneShotStrictlyAfter(Instant.parse("2026-10-13T07:00:00Z"), utc, LocalTime.of(7, 0))
        assertEquals(Instant.parse("2026-10-14T07:00:00Z"), result)
    }

    @Test fun recurringRollsAcrossWeekBoundary() {
        val result = calculator.recurringStrictlyAfter(
            Instant.parse("2026-10-12T08:00:00Z"), utc, LocalTime.of(7, 0), setOf(DayOfWeek.MONDAY),
        )
        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), result)
    }

    @Test fun selectedTodayAtExactTimeUsesNextSelectedDay() {
        val result = calculator.recurringStrictlyAfter(
            Instant.parse("2026-10-13T07:00:00Z"), utc, LocalTime.of(7, 0),
            setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
        )
        assertEquals(Instant.parse("2026-10-14T07:00:00Z"), result)
    }

    @Test fun dstGapShiftsForwardByGap() {
        val zone = ZoneId.of("Europe/Budapest")
        val result = calculator.oneShotStrictlyAfter(
            Instant.parse("2026-03-28T12:00:00Z"), zone, LocalTime.of(2, 30),
        )
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), result)
        assertEquals(LocalTime.of(3, 30), result.atZone(zone).toLocalTime())
    }

    @Test fun dstOverlapUsesEarlierOffsetAndSingleOccurrenceForDate() {
        val zone = ZoneId.of("Europe/Budapest")
        val result = calculator.recurringStrictlyAfter(
            Instant.parse("2026-10-24T12:00:00Z"), zone, LocalTime.of(2, 30), setOf(DayOfWeek.SUNDAY),
        )!!
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), result)
        assertTrue(result.atZone(zone).offset.totalSeconds > 0)
        assertEquals(
            Instant.parse("2026-11-01T01:30:00Z"),
            calculator.recurringStrictlyAfter(result, zone, LocalTime.of(2, 30), setOf(DayOfWeek.SUNDAY)),
        )
    }

    @Test fun recurringDstGapAdvancesToNextSelectedWeekAfterShiftedDelivery() {
        val zone = ZoneId.of("Europe/Budapest")
        val first = calculator.recurringStrictlyAfter(
            Instant.parse("2026-03-28T12:00:00Z"), zone, LocalTime.of(2, 30), setOf(DayOfWeek.SUNDAY),
        )!!
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), first)
        assertEquals(
            Instant.parse("2026-04-05T00:30:00Z"),
            calculator.recurringStrictlyAfter(first, zone, LocalTime.of(2, 30), setOf(DayOfWeek.SUNDAY)),
        )
    }

    @Test fun timeZoneChangesResultingInstant() {
        val now = Instant.parse("2026-10-13T00:00:00Z")
        val budapest = calculator.oneShotStrictlyAfter(now, ZoneId.of("Europe/Budapest"), LocalTime.of(7, 0))
        val tokyo = calculator.oneShotStrictlyAfter(now, ZoneId.of("Asia/Tokyo"), LocalTime.of(7, 0))
        assertTrue(budapest != tokyo)
    }
}
