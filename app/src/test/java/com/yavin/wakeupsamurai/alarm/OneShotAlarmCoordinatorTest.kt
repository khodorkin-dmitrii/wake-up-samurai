package com.yavin.wakeupsamurai.alarm

import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AlarmWithAudio
import com.yavin.wakeupsamurai.domain.alarm.DeliveryClaim
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotAlarmCoordinatorTest {
    private val clock = FakeClock(Instant.parse("2026-10-12T06:00:00Z")) // Monday
    private val repository = FakeAlarmRepository()
    private val scheduler = FakeScheduler()
    private val bootClock = FakeBootClock()
    private val coordinator = OneShotAlarmCoordinator(repository, scheduler, clock, bootClock = bootClock)

    @Test fun recurringSaveSchedulesOnlyTheNextSelectedWeekday() = runTest {
        val result = coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))

        assertTrue(result is AlarmSaveResult.Scheduled)
        assertEquals(Instant.parse("2026-10-12T07:00:00Z"), repository.alarm()!!.scheduledAt)
        assertEquals(1, scheduler.scheduled.size)
        assertTrue(repository.alarm()!!.configuredEnabled)
    }

    @Test fun deliveryAdvancesRegularOnceBeforeDismissAndRejectsDuplicate() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")

        assertEquals(DeliveryDisposition.RING, coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision))
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision))
        val advanced = repository.alarm()!!
        assertEquals(Instant.parse("2026-10-16T07:00:00Z"), advanced.scheduledAt)
        assertEquals(2, scheduler.scheduled.size)
        assertEquals(first.occurrenceId, advanced.ringingOccurrenceId)

        assertTrue(repository.finishOccurrence("alarm", first.occurrenceId, first.revision, AlarmSchedulingState.COMPLETED))
        assertFalse(repository.finishOccurrence("alarm", first.occurrenceId, first.revision, AlarmSchedulingState.COMPLETED))
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertEquals(AlarmSchedulingState.SCHEDULED, repository.alarm()!!.schedulingState)
        assertEquals(advanced.scheduledOccurrenceId, repository.alarm()!!.scheduledOccurrenceId)
    }

    @Test fun snoozeAndNextRegularCoexistAndCancelOnlySnooze() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        val nextRegular = repository.alarm()!!.nextOccurrence()!!

        assertTrue(coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision)) is SnoozeResult.Snoozed)
        assertEquals(nextRegular, repository.alarm()!!.nextOccurrence())
        assertEquals(Instant.parse("2026-10-12T07:05:00Z"), repository.alarm()!!.snoozedAt)
        assertTrue(OneShotAlarmCoordinator(repository, scheduler, clock, bootClock = bootClock).cancelSnooze("alarm"))
        assertEquals(nextRegular, repository.alarm()!!.nextOccurrence())
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertNull(repository.alarm()!!.snoozeOccurrenceId)
        assertEquals(listOf("alarm"), scheduler.cancelled.map { it.alarmId })
    }

    @Test fun snoozedRecurringRingsAgainWithoutReplacingWeeklySchedule() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        val nextRegular = repository.alarm()!!.nextOccurrence()!!
        val snoozed = coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision)) as SnoozeResult.Snoozed

        clock.now = Instant.parse("2026-10-12T07:05:00Z")
        assertEquals(DeliveryDisposition.RING, coordinator.acceptDelivery("alarm", snoozed.occurrence.occurrenceId, snoozed.occurrence.revision))
        assertEquals(nextRegular, repository.alarm()!!.nextOccurrence())
        assertTrue(repository.finishOccurrence("alarm", snoozed.occurrence.occurrenceId, snoozed.occurrence.revision, AlarmSchedulingState.COMPLETED))
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertEquals(nextRegular, repository.alarm()!!.nextOccurrence())
    }

    @Test fun disablingAndDeletingCancelBothPendingOccurrences() = runTest {
        val regular = alarm(days = setOf(DayOfWeek.MONDAY)).copy(
            scheduleRevision = 3, scheduledOccurrenceId = "regular", scheduledAt = clock.now.plusSeconds(3600),
            snoozeOccurrenceId = "snooze", snoozeRevision = 4, snoozedAt = clock.now.plusSeconds(300),
            schedulingState = AlarmSchedulingState.SNOOZED,
        )
        repository.save(regular)
        coordinator.save(regular.copy(configuredEnabled = false))
        assertEquals(setOf("regular", "snooze"), scheduler.cancelled.map { it.occurrenceId }.toSet())
        assertFalse(repository.alarm()!!.configuredEnabled)

        repository.save(regular)
        scheduler.cancelled.clear()
        coordinator.delete("alarm")
        assertEquals(setOf("regular", "snooze"), scheduler.cancelled.map { it.occurrenceId }.toSet())
        assertNull(repository.alarm())
    }

    @Test fun savedScheduleChangeReplacesBothPendingOccurrencesAndMakesOldDeliveryStale() = runTest {
        val old = alarm(days = setOf(DayOfWeek.MONDAY)).copy(
            scheduleRevision = 3, scheduledOccurrenceId = "old-regular", scheduledAt = clock.now.plusSeconds(3600),
            snoozeOccurrenceId = "old-snooze", snoozeRevision = 4, snoozedAt = clock.now.plusSeconds(300),
            schedulingState = AlarmSchedulingState.SNOOZED,
        )
        repository.save(old)
        coordinator.save(old.copy(time = LocalTime.of(8, 0), repeatDays = setOf(DayOfWeek.TUESDAY)))

        assertEquals(setOf("old-regular", "old-snooze"), scheduler.cancelled.map { it.occurrenceId }.toSet())
        assertEquals(Instant.parse("2026-10-13T08:00:00Z"), repository.alarm()!!.scheduledAt)
        assertNotEquals("old-regular", repository.alarm()!!.scheduledOccurrenceId)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery("alarm", "old-regular", 3))
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery("alarm", "old-snooze", 4))
    }

    @Test fun explicitSaveSwitchesBetweenOneShotAndWeekdayRepeat() = runTest {
        coordinator.save(alarm())
        val oneShot = repository.alarm()!!.nextOccurrence()!!

        coordinator.save(repository.alarm()!!.copy(repeatDays = setOf(DayOfWeek.TUESDAY), resolvedOneShotAt = null))
        val recurring = repository.alarm()!!.nextOccurrence()!!
        assertEquals(Instant.parse("2026-10-13T07:00:00Z"), repository.alarm()!!.scheduledAt)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery("alarm", oneShot.occurrenceId, oneShot.revision))

        coordinator.save(repository.alarm()!!.copy(repeatDays = emptySet(), resolvedOneShotAt = clock.now.plusSeconds(7200)))
        assertEquals(Instant.parse("2026-10-12T08:00:00Z"), repository.alarm()!!.scheduledAt)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery("alarm", recurring.occurrenceId, recurring.revision))
        assertEquals(setOf(oneShot.occurrenceId, recurring.occurrenceId), scheduler.cancelled.map { it.occurrenceId }.toSet())
    }

    @Test fun failureToAdvanceKeepsRecurringIntentAndCurrentRing() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        scheduler.nextResult = PlatformScheduleResult.Failed("AlarmManager failed")

        assertEquals(DeliveryDisposition.RING, coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision))
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertNull(repository.alarm()!!.scheduledOccurrenceId)
        assertEquals(AlarmSchedulingState.FAILED, repository.alarm()!!.schedulingState)
        assertEquals(first.occurrenceId, repository.alarm()!!.ringingOccurrenceId)
        repository.finishOccurrence("alarm", first.occurrenceId, first.revision, AlarmSchedulingState.COMPLETED)
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertEquals(AlarmSchedulingState.FAILED, repository.alarm()!!.schedulingState)
    }

    @Test fun blockedRecurringSaveKeepsIntentAndRetrySchedulesIt() = runTest {
        scheduler.accessState = SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED
        assertTrue(coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY))) is AlarmSaveResult.BlockedExactAlarmAccess)
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertNull(repository.alarm()!!.scheduledOccurrenceId)
        assertTrue(scheduler.scheduled.isEmpty())

        scheduler.accessState = SchedulingAccess.GRANTED
        assertTrue(coordinator.retry("alarm") is AlarmSaveResult.Scheduled)
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test fun failedSnoozeLeavesCurrentRecurringOccurrenceRinging() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        scheduler.nextResult = PlatformScheduleResult.Failed("Could not snooze")

        assertTrue(coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision)) is SnoozeResult.Failed)
        assertEquals(first.occurrenceId, repository.alarm()!!.ringingOccurrenceId)
        assertNull(repository.alarm()!!.snoozeOccurrenceId)
        assertNotEquals(first.occurrenceId, repository.alarm()!!.scheduledOccurrenceId)
    }

    @Test fun simultaneousRegularDeliveryIsSkippedButItsFollowingRepeatIsScheduled() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        val tuesday = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-13T07:00:00Z")
        // Inject an overlapping active session; normally its monotonic five-minute deadline prevents this.

        assertEquals(DeliveryDisposition.SKIPPED, coordinator.acceptDelivery(tuesday.alarmId, tuesday.occurrenceId, tuesday.revision))
        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), repository.alarm()!!.scheduledAt)
        assertEquals(first.occurrenceId, repository.alarm()!!.ringingOccurrenceId)
        assertTrue(repository.alarm()!!.configuredEnabled)
    }

    @Test fun oneShotStillCompletesAndCancellingItsSnoozeDisablesIt() = runTest {
        coordinator.save(alarm())
        val first = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        assertTrue(coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision)) is SnoozeResult.Snoozed)
        assertTrue(coordinator.cancelSnooze("alarm"))
        assertFalse(repository.alarm()!!.configuredEnabled)
    }

    @Test fun repeatedForegroundPassIsIdempotentAndPackageUpdateRearmsSameIdentity() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val before = repository.alarm()!!.nextOccurrence()!!
        coordinator.reconcile()
        coordinator.reconcile()
        assertEquals(3, scheduler.scheduled.size)
        assertTrue(scheduler.scheduled.all { it.occurrenceId == before.occurrenceId })

        coordinator.reconcile(ReconcileReason.PACKAGE_REPLACED)
        assertEquals(4, scheduler.scheduled.size)
        assertEquals(before, repository.alarm()!!.nextOccurrence())
    }

    @Test fun rebootRearmsFutureOneShotWithoutMovingItsInstant() = runTest {
        coordinator.save(alarm())
        val before = repository.alarm()!!.nextOccurrence()!!
        bootClock.id = 2L
        clock.now = clock.now.plusSeconds(600)

        coordinator.reconcile(ReconcileReason.BOOT)

        assertEquals(before, repository.alarm()!!.nextOccurrence())
        assertEquals(2L, repository.alarm()!!.regularScheduledBootId)
        assertEquals(2, scheduler.scheduled.size)
    }

    @Test fun timezoneChangeReplacesRecurringInstantButNotOneShotInstant() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val old = repository.alarm()!!.nextOccurrence()!!
        clock.zone = ZoneId.of("America/New_York")
        coordinator.reconcile(ReconcileReason.CLOCK_CHANGED)
        val changed = repository.alarm()!!.nextOccurrence()!!
        assertEquals(Instant.parse("2026-10-12T11:00:00Z"), repository.alarm()!!.scheduledAt)
        assertNotEquals(old.occurrenceId, changed.occurrenceId)
        assertTrue(scheduler.cancelled.any { it.occurrenceId == old.occurrenceId })

        coordinator.save(alarm())
        val oneShot = repository.alarm()!!.nextOccurrence()!!
        clock.zone = ZoneId.of("Europe/Budapest")
        coordinator.reconcile(ReconcileReason.CLOCK_CHANGED)
        assertEquals(oneShot, repository.alarm()!!.nextOccurrence())
    }

    @Test fun overdueOneShotIsMissedAndNeverRevived() = runTest {
        coordinator.save(alarm())
        val old = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T08:00:00Z")
        coordinator.reconcile(ReconcileReason.BOOT)

        assertFalse(repository.alarm()!!.configuredEnabled)
        assertEquals(AlarmSchedulingState.MISSED, repository.alarm()!!.schedulingState)
        assertNull(repository.alarm()!!.scheduledOccurrenceId)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(old.alarmId, old.occurrenceId, old.revision))
        coordinator.reconcile()
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test fun accessRetryDoesNotMoveAnOverdueOneShotToTomorrow() = runTest {
        scheduler.accessState = SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED
        coordinator.save(alarm())
        clock.now = Instant.parse("2026-10-12T08:00:00Z")
        scheduler.accessState = SchedulingAccess.GRANTED

        assertTrue(coordinator.retry("alarm") is AlarmSaveResult.Failed)
        assertEquals(AlarmSchedulingState.MISSED, repository.alarm()!!.schedulingState)
        assertFalse(repository.alarm()!!.configuredEnabled)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun overdueRecurringAdvancesToOneFutureWeekday() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val old = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T08:00:00Z")
        coordinator.reconcile(ReconcileReason.BOOT)

        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), repository.alarm()!!.scheduledAt)
        assertNotEquals(old.occurrenceId, repository.alarm()!!.scheduledOccurrenceId)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(old.alarmId, old.occurrenceId, old.revision))
        assertTrue(repository.alarm()!!.configuredEnabled)
    }

    @Test fun snoozeUsesElapsedDeadlineWithinBootAndWallRecoveryAfterReboot() = runTest {
        coordinator.save(alarm())
        val first = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        val snoozed = (coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision)) as SnoozeResult.Snoozed).occurrence
        assertEquals(400_000L, snoozed.elapsedDeadlineMillis)

        // Move wall time back two hours while monotonic time advances one minute.
        clock.now = clock.now.minusSeconds(7_200)
        bootClock.elapsed += 60_000L
        coordinator.reconcile(ReconcileReason.CLOCK_CHANGED)
        assertEquals(clock.now.plusSeconds(240), repository.alarm()!!.snoozedAt)
        assertEquals(snoozed.occurrenceId, repository.alarm()!!.snoozeOccurrenceId)
        assertNull(repository.alarm()!!.scheduledOccurrenceId)

        val wallRecoveryAt = repository.alarm()!!.snoozedAt
        bootClock.id = 2L
        bootClock.elapsed = 10_000L
        clock.now = requireNotNull(wallRecoveryAt).minusSeconds(120)
        coordinator.reconcile(ReconcileReason.BOOT)
        assertEquals(130_000L, repository.alarm()!!.snoozeElapsedDeadlineMillis)
        assertEquals(2L, repository.alarm()!!.snoozeScheduledBootId)
        assertEquals(snoozed.occurrenceId, repository.alarm()!!.snoozeOccurrenceId)
    }

    @Test fun overdueSnoozeExpiresAndAccessReturnOnlyRestoresEligibleOccurrence() = runTest {
        coordinator.save(alarm())
        val first = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        val snooze = (coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision)) as SnoozeResult.Snoozed).occurrence
        bootClock.id = 2L
        clock.now = requireNotNull(repository.alarm()!!.snoozedAt).plusSeconds(1)
        coordinator.reconcile(ReconcileReason.BOOT)
        assertNull(repository.alarm()!!.snoozeOccurrenceId)
        assertEquals(AlarmSchedulingState.MISSED, repository.alarm()!!.schedulingState)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(snooze.alarmId, snooze.occurrenceId, snooze.revision))

        coordinator.save(alarm(days = setOf(DayOfWeek.FRIDAY)))
        scheduler.accessState = SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED
        coordinator.reconcile()
        assertEquals(AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS, repository.alarm()!!.schedulingState)
        scheduler.accessState = SchedulingAccess.GRANTED
        coordinator.reconcile(ReconcileReason.ACCESS_GRANTED)
        assertEquals(AlarmSchedulingState.SCHEDULED, repository.alarm()!!.schedulingState)
    }

    @Test fun sameBootElapsedExpiryIgnoresWallClockRollback() = runTest {
        coordinator.save(alarm())
        val first = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        coordinator.snooze(RingingOccurrence("alarm", first.occurrenceId, first.revision))
        clock.now = clock.now.minusSeconds(3_600)
        bootClock.elapsed += 301_000L

        coordinator.reconcile()

        assertNull(repository.alarm()!!.snoozeOccurrenceId)
        assertFalse(repository.alarm()!!.configuredEnabled)
        assertEquals(AlarmSchedulingState.MISSED, repository.alarm()!!.schedulingState)
    }

    @Test fun dormantLegacyRecurringRuleIsNotSilentlyActivated() = runTest {
        repository.save(alarm(days = setOf(DayOfWeek.MONDAY)).copy(
            schedulingState = AlarmSchedulingState.NOT_SCHEDULED,
        ))
        coordinator.reconcile(ReconcileReason.BOOT)
        assertNull(repository.alarm()!!.scheduledOccurrenceId)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test fun previousBootRingingSessionIsNotRevived() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        bootClock.id = 2L
        coordinator.reconcile(ReconcileReason.BOOT)

        assertNull(repository.alarm()!!.ringingOccurrenceId)
        assertTrue(repository.alarm()!!.configuredEnabled)
        assertNotEquals(first.occurrenceId, repository.alarm()!!.scheduledOccurrenceId)
    }

    @Test fun acceptedRingRecoversOnlyWithinOriginalMonotonicDeadline() = runTest {
        coordinator.save(alarm())
        val pending = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(pending.alarmId, pending.occurrenceId, pending.revision)
        val ringing = RingingOccurrence(pending.alarmId, pending.occurrenceId, pending.revision)
        assertEquals(400_000L, repository.alarm()!!.ringingDeadlineElapsedMillis)

        bootClock.elapsed = 160_000L
        clock.now = clock.now.plusSeconds(86_400) // Wall time must not mark an accepted ring as missed.
        val restored = OneShotAlarmCoordinator(repository, scheduler, clock, bootClock = bootClock)
        assertEquals(240_000L, restored.ringingStatus(ringing)?.remainingMillis)
        assertEquals(1, restored.reconcile().size)
        assertEquals(DeliveryDisposition.STALE, restored.acceptDelivery(pending.alarmId, pending.occurrenceId, pending.revision))

        bootClock.elapsed = 400_000L
        assertNull(restored.ringingStatus(ringing))
        assertNull(repository.alarm()!!.ringingOccurrenceId)
        assertFalse(repository.alarm()!!.configuredEnabled)
        assertTrue(restored.reconcile().isEmpty())
    }

    @Test fun interruptedExplicitSaveIsRecoveredWithoutActivatingLegacyRule() = runTest {
        val pendingSave = alarm().copy(schedulingState = AlarmSchedulingState.FAILED)
        repository.save(pendingSave)
        coordinator.reconcile()
        assertEquals(AlarmSchedulingState.SCHEDULED, repository.alarm()!!.schedulingState)
        assertEquals(pendingSave.resolvedOneShotAt, repository.alarm()!!.scheduledAt)
    }

    @Test fun failedServiceStartEndsOnlyMatchingOccurrenceAndExposesProblem() = runTest {
        coordinator.save(alarm())
        val pending = repository.alarm()!!.nextOccurrence()!!
        coordinator.acceptDelivery(pending.alarmId, pending.occurrenceId, pending.revision)
        assertTrue(coordinator.finishOccurrence(pending.alarmId, pending.occurrenceId, pending.revision,
            AlarmSchedulingState.FAILED, "Playback could not start"))
        assertNull(coordinator.ringingStatus(RingingOccurrence(pending.alarmId, pending.occurrenceId, pending.revision)))
        assertEquals("Playback could not start", repository.alarm()!!.lastRingingFailure)
        assertFalse(coordinator.finishOccurrence(pending.alarmId, pending.occurrenceId, pending.revision,
            AlarmSchedulingState.COMPLETED))
    }

    @Test fun secondAlarmIsSkippedWhileFirstRingsAndItsRepeatStillAdvances() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)).copy(id = "other"))
        val first = repository.alarm("alarm")!!.nextOccurrence()!!
        val second = repository.alarm("other")!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")

        assertEquals(DeliveryDisposition.RING, coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision))
        assertEquals(DeliveryDisposition.SKIPPED, coordinator.acceptDelivery(second.alarmId, second.occurrenceId, second.revision))
        assertNull(repository.alarm("other")!!.ringingOccurrenceId)
        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), repository.alarm("other")!!.scheduledAt)
        assertEquals(first.occurrenceId, repository.alarm("alarm")!!.ringingOccurrenceId)
    }

    @Test fun oldDismissActionCannotStopNewerOccurrence() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)))
        val monday = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        coordinator.acceptDelivery(monday.alarmId, monday.occurrenceId, monday.revision)
        assertTrue(coordinator.finishOccurrence(monday.alarmId, monday.occurrenceId, monday.revision,
            AlarmSchedulingState.COMPLETED))
        val tuesday = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-13T07:00:00Z")
        bootClock.elapsed += 86_400_000L
        assertEquals(DeliveryDisposition.RING,
            coordinator.acceptDelivery(tuesday.alarmId, tuesday.occurrenceId, tuesday.revision))

        assertFalse(coordinator.finishOccurrence(monday.alarmId, monday.occurrenceId, monday.revision,
            AlarmSchedulingState.COMPLETED))
        assertEquals(tuesday.occurrenceId, repository.alarm()!!.ringingOccurrenceId)
    }

    @Test fun rollingClockBackAfterRegularDeliveryDoesNotRepeatThatDay() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val first = repository.alarm()!!.nextOccurrence()!!
        clock.now = Instant.parse("2026-10-12T07:00:00Z")
        coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision)
        coordinator.finishOccurrence(first.alarmId, first.occurrenceId, first.revision, AlarmSchedulingState.COMPLETED)
        clock.now = Instant.parse("2026-10-12T06:00:00Z")

        coordinator.reconcile(ReconcileReason.CLOCK_CHANGED)

        assertEquals(Instant.parse("2026-10-19T07:00:00Z"), repository.alarm()!!.scheduledAt)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(first.alarmId, first.occurrenceId, first.revision))
    }

    @Test fun recoveryFailureRetainsIntentAndLaterPassReusesOccurrence() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        bootClock.id = 2L
        scheduler.nextResult = PlatformScheduleResult.Failed("transient")
        coordinator.reconcile(ReconcileReason.BOOT)
        val failed = repository.alarm()!!
        assertEquals(AlarmSchedulingState.FAILED, failed.schedulingState)
        assertTrue(failed.configuredEnabled)
        val pending = failed.nextOccurrence()

        coordinator.reconcile()
        assertEquals(pending, repository.alarm()!!.nextOccurrence())
        assertEquals(AlarmSchedulingState.SCHEDULED, repository.alarm()!!.schedulingState)
    }

    @Test fun disableAndDeliveryAfterReconciliationCannotRecreateOldOccurrence() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val old = repository.alarm()!!.nextOccurrence()!!
        coordinator.save(repository.alarm()!!.copy(configuredEnabled = false))
        coordinator.reconcile(ReconcileReason.BOOT)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(old.alarmId, old.occurrenceId, old.revision))
        assertFalse(repository.alarm()!!.configuredEnabled)
        assertEquals(1, scheduler.scheduled.size)

        coordinator.delete("alarm")
        coordinator.reconcile(ReconcileReason.PACKAGE_REPLACED)
        assertNull(repository.alarm())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun disableQueuedDuringRecoveryWinsAndOldDeliveryStaysStale() = runTest {
        coordinator.save(alarm(days = setOf(DayOfWeek.MONDAY)))
        val old = repository.alarm()!!.nextOccurrence()!!
        val gate = CompletableDeferred<Unit>()
        repository.pauseNextGet = gate
        val recovery = async { coordinator.reconcile(ReconcileReason.BOOT) }
        runCurrent()
        val disable = async { coordinator.save(repository.alarm()!!.copy(configuredEnabled = false)) }
        runCurrent()
        gate.complete(Unit)
        recovery.await()
        disable.await()

        assertFalse(repository.alarm()!!.configuredEnabled)
        assertNull(repository.alarm()!!.scheduledOccurrenceId)
        assertEquals(DeliveryDisposition.STALE, coordinator.acceptDelivery(old.alarmId, old.occurrenceId, old.revision))
    }

    private fun alarm(days: Set<DayOfWeek> = emptySet()) = Alarm(
        id = "alarm", label = "", messageText = "Pancakes", time = LocalTime.of(7, 0),
        repeatDays = days, configuredEnabled = true,
        resolvedOneShotAt = if (days.isEmpty()) clock.now.plusSeconds(3600) else null,
        audioAssetId = "asset", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
    )
}

private class FakeClock(var now: Instant, var zone: ZoneId = ZoneId.of("UTC")) : TimeProvider {
    override fun now(): Instant = now
    override fun zoneId(): ZoneId = zone
}

private class FakeBootClock(var id: Long = 1L, var elapsed: Long = 100_000L) : AlarmBootClock {
    override fun bootId(): Long = id
    override fun elapsedRealtimeMillis(): Long = elapsed
}

private class FakeScheduler : OneShotAlarmScheduler {
    val scheduled = mutableListOf<ScheduledOccurrence>()
    val cancelled = mutableListOf<ScheduledOccurrence>()
    var nextResult: PlatformScheduleResult = PlatformScheduleResult.Scheduled
    var accessState: SchedulingAccess = SchedulingAccess.GRANTED
    override fun access() = accessState
    override fun schedule(occurrence: ScheduledOccurrence): PlatformScheduleResult {
        val result = nextResult
        nextResult = PlatformScheduleResult.Scheduled
        if (result == PlatformScheduleResult.Scheduled) scheduled += occurrence
        return result
    }
    override fun cancel(occurrence: ScheduledOccurrence) { cancelled += occurrence }
}

private class FakeAlarmRepository : AlarmRepository {
    private val values = MutableStateFlow<List<AlarmWithAudio>>(emptyList())
    var pauseNextGet: CompletableDeferred<Unit>? = null
    fun alarm(id: String = "alarm"): Alarm? = values.value.firstOrNull { it.alarm.id == id }?.alarm
    private fun change(id: String, block: (Alarm) -> Alarm): Boolean {
        val old = alarm(id) ?: return false
        values.value = values.value.map { if (it.alarm.id == id) AlarmWithAudio(block(old), null) else it }
        return true
    }
    override fun observeAll(): Flow<List<AlarmWithAudio>> = values
    override suspend fun get(id: String): AlarmWithAudio? {
        pauseNextGet?.also { gate -> pauseNextGet = null; gate.await() }
        return values.value.firstOrNull { it.alarm.id == id }
    }
    override suspend fun save(alarm: Alarm) {
        values.value = values.value.filterNot { it.alarm.id == alarm.id } + AlarmWithAudio(alarm, null)
    }
    override suspend fun delete(id: String) { values.value = values.value.filterNot { it.alarm.id == id } }
    override suspend fun claimScheduledOccurrence(alarmId: String, occurrenceId: String, revision: Long, bootId: Long,
        deadlineElapsedMillis: Long): DeliveryClaim {
        val old = alarm(alarmId) ?: return DeliveryClaim.STALE
        if (!old.configuredEnabled) return DeliveryClaim.STALE
        if (old.scheduledOccurrenceId == occurrenceId && old.scheduleRevision == revision) {
            change(alarmId) { it.copy(
                scheduledOccurrenceId = null, scheduledAt = null, regularScheduledBootId = null,
                lastRegularDeliveredAt = old.scheduledAt,
                ringingOccurrenceId = if (old.ringingOccurrenceId == null) occurrenceId else old.ringingOccurrenceId,
                ringingRevision = if (old.ringingOccurrenceId == null) revision else old.ringingRevision,
                ringingBootId = if (old.ringingOccurrenceId == null) bootId else old.ringingBootId,
                ringingDeadlineElapsedMillis = if (old.ringingOccurrenceId == null) deadlineElapsedMillis else old.ringingDeadlineElapsedMillis,
                schedulingState = if (old.ringingOccurrenceId == null) AlarmSchedulingState.RINGING else old.schedulingState,
            ) }
            return if (old.ringingOccurrenceId == null) DeliveryClaim.REGULAR else DeliveryClaim.SKIPPED_REGULAR
        }
        if (old.snoozeOccurrenceId == occurrenceId && old.snoozeRevision == revision) {
            change(alarmId) { it.copy(
                snoozeOccurrenceId = null, snoozeRevision = null, snoozedAt = null,
                snoozeScheduledBootId = null, snoozeElapsedDeadlineMillis = null,
                ringingOccurrenceId = if (old.ringingOccurrenceId == null) occurrenceId else old.ringingOccurrenceId,
                ringingRevision = if (old.ringingOccurrenceId == null) revision else old.ringingRevision,
                ringingBootId = if (old.ringingOccurrenceId == null) bootId else old.ringingBootId,
                ringingDeadlineElapsedMillis = if (old.ringingOccurrenceId == null) deadlineElapsedMillis else old.ringingDeadlineElapsedMillis,
                schedulingState = if (old.ringingOccurrenceId == null) AlarmSchedulingState.RINGING else old.schedulingState,
            ) }
            return if (old.ringingOccurrenceId == null) DeliveryClaim.SNOOZE else DeliveryClaim.SKIPPED_SNOOZE
        }
        return DeliveryClaim.STALE
    }
    override suspend fun installNextRegular(
        alarmId: String, previousRevision: Long, occurrenceId: String, revision: Long, at: Long, bootId: Long,
    ): Boolean {
        val old = alarm(alarmId) ?: return false
        if (!old.configuredEnabled || old.repeatDays.isEmpty() || old.scheduleRevision != previousRevision ||
            old.scheduledOccurrenceId != null
        ) return false
        return change(alarmId) { it.copy(
            scheduleRevision = revision, scheduledOccurrenceId = occurrenceId, scheduledAt = Instant.ofEpochMilli(at),
            regularScheduledBootId = bootId,
            schedulingState = if (it.ringingOccurrenceId != null) AlarmSchedulingState.RINGING else if (it.snoozeOccurrenceId != null)
                AlarmSchedulingState.SNOOZED else AlarmSchedulingState.SCHEDULED,
        ) }
    }
    override suspend fun markRegularSchedulingFailed(
        alarmId: String, previousRevision: Long, state: AlarmSchedulingState,
    ): Boolean {
        val old = alarm(alarmId) ?: return false
        if (old.scheduleRevision != previousRevision || old.scheduledOccurrenceId != null) return false
        return change(alarmId) { it.copy(schedulingState = state) }
    }
    override suspend fun finishOccurrence(
        alarmId: String, occurrenceId: String, revision: Long, finalState: AlarmSchedulingState,
        failureMessage: String?,
    ): Boolean {
        val old = alarm(alarmId) ?: return false
        if (old.ringingOccurrenceId != occurrenceId || old.ringingRevision != revision) return false
        return change(alarmId) { it.copy(
            ringingOccurrenceId = null, ringingRevision = null, ringingBootId = null,
            ringingDeadlineElapsedMillis = null, lastRingingFailure = failureMessage,
            configuredEnabled = if (it.repeatDays.isEmpty() && it.snoozeOccurrenceId == null) false else it.configuredEnabled,
            schedulingState = when {
                it.snoozeOccurrenceId != null -> AlarmSchedulingState.SNOOZED
                it.scheduledOccurrenceId != null -> AlarmSchedulingState.SCHEDULED
                it.repeatDays.isNotEmpty() -> if (it.schedulingState in setOf(
                        AlarmSchedulingState.FAILED, AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS,
                        AlarmSchedulingState.BLOCKED_NOTIFICATION_PERMISSION,
                    )) it.schedulingState else AlarmSchedulingState.FAILED
                else -> finalState
            },
        ) }
    }
    override suspend fun replaceRingingWithSnooze(
        alarmId: String, ringingOccurrenceId: String, ringingRevision: Long,
        snoozeOccurrenceId: String, snoozeRevision: Long, snoozedAtEpochMillis: Long,
        elapsedDeadlineMillis: Long, bootId: Long,
    ): Boolean {
        val old = alarm(alarmId) ?: return false
        if (old.ringingOccurrenceId != ringingOccurrenceId || old.ringingRevision != ringingRevision || !old.configuredEnabled) return false
        return change(alarmId) { it.copy(
            ringingOccurrenceId = null, ringingRevision = null, ringingBootId = null,
            ringingDeadlineElapsedMillis = null,
            snoozeOccurrenceId = snoozeOccurrenceId, snoozeRevision = snoozeRevision,
            snoozedAt = Instant.ofEpochMilli(snoozedAtEpochMillis), schedulingState = AlarmSchedulingState.SNOOZED,
            snoozeElapsedDeadlineMillis = elapsedDeadlineMillis, snoozeScheduledBootId = bootId,
        ) }
    }
    override suspend fun clearPendingSnooze(alarmId: String, occurrenceId: String, revision: Long): Boolean {
        val old = alarm(alarmId) ?: return false
        if (old.snoozeOccurrenceId != occurrenceId || old.snoozeRevision != revision) return false
        return change(alarmId) { it.copy(
            snoozeOccurrenceId = null, snoozeRevision = null, snoozedAt = null,
            snoozeElapsedDeadlineMillis = null, snoozeScheduledBootId = null,
            configuredEnabled = if (it.repeatDays.isEmpty()) false else it.configuredEnabled,
            schedulingState = when {
                it.ringingOccurrenceId != null -> AlarmSchedulingState.RINGING
                it.scheduledOccurrenceId != null -> AlarmSchedulingState.SCHEDULED
                it.repeatDays.isNotEmpty() -> AlarmSchedulingState.FAILED
                else -> AlarmSchedulingState.NOT_SCHEDULED
            },
        ) }
    }
}
