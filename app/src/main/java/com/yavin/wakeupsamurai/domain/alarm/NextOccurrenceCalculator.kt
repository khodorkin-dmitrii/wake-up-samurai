package com.yavin.wakeupsamurai.domain.alarm

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

class NextOccurrenceCalculator @Inject constructor() {
    fun oneShotStrictlyAfter(now: Instant, zoneId: ZoneId, time: LocalTime): Instant {
        val today = now.atZone(zoneId).toLocalDate()
        return (0L..1L)
            .asSequence()
            .map { resolve(today.plusDays(it), time, zoneId) }
            .first { it.toInstant().isAfter(now) }
            .toInstant()
    }

    fun recurringStrictlyAfter(
        now: Instant,
        zoneId: ZoneId,
        time: LocalTime,
        repeatDays: Set<DayOfWeek>,
    ): Instant? {
        if (repeatDays.isEmpty()) return null
        val today = now.atZone(zoneId).toLocalDate()
        return (0L..7L)
            .asSequence()
            .map { today.plusDays(it) }
            .filter { it.dayOfWeek in repeatDays }
            .map { resolve(it, time, zoneId).toInstant() }
            .firstOrNull { it.isAfter(now) }
    }

    /** Gaps move forward by the gap duration; overlaps use Java's earlier offset. */
    internal fun resolve(date: LocalDate, time: LocalTime, zoneId: ZoneId): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val offsets = zoneId.rules.getValidOffsets(local)
        return when (offsets.size) {
            0 -> {
                val transition = requireNotNull(zoneId.rules.getTransition(local))
                ZonedDateTime.ofLocal(
                    local.plus(Duration.between(transition.dateTimeBefore, transition.dateTimeAfter)),
                    zoneId,
                    transition.offsetAfter,
                )
            }
            1 -> ZonedDateTime.ofLocal(local, zoneId, offsets.single())
            else -> ZonedDateTime.ofLocal(local, zoneId, offsets.first()).withEarlierOffsetAtOverlap()
        }
    }
}

interface TimeProvider {
    fun now(): Instant
    fun zoneId(): ZoneId
}

class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}
