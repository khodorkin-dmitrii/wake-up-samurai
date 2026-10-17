package com.yavin.wakeupsamurai.alarm

import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AlarmWithAudio
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneShotAlarmCoordinatorTest {
    @Test
    fun schedulesOnlyAfterPlatformSchedulingSucceeds() = runTest {
        val repository = FakeAlarmRepository()
        val scheduler = FakeScheduler()
        val coordinator = OneShotAlarmCoordinator(repository, scheduler)

        val result = coordinator.save(enabledOneShot())

        assertTrue(result is AlarmSaveResult.Scheduled)
        val saved = repository.saved.single()
        assertEquals(AlarmSchedulingState.SCHEDULED, saved.schedulingState)
        assertTrue(saved.scheduledOccurrenceId != null)
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun editingScheduledAlarmCancelsPreviousOccurrenceBeforeReplacingIt() = runTest {
        val old = enabledOneShot().copy(
            scheduleRevision = 4,
            scheduledOccurrenceId = "old-occurrence",
            scheduledAt = Instant.parse("2026-10-15T07:00:00Z"),
            schedulingState = AlarmSchedulingState.SCHEDULED,
        )
        val repository = FakeAlarmRepository(old)
        val scheduler = FakeScheduler()
        val coordinator = OneShotAlarmCoordinator(repository, scheduler)

        coordinator.save(old.copy(resolvedOneShotAt = Instant.parse("2026-10-15T08:00:00Z")))

        assertEquals(listOf("old-occurrence"), scheduler.cancelled.map { it.occurrenceId })
        assertEquals(5L, repository.saved.single().scheduleRevision)
    }

    @Test
    fun blockedExactAccessKeepsEnabledIntentButNeverReportsSystemSchedule() = runTest {
        val repository = FakeAlarmRepository()
        val scheduler = FakeScheduler(access = SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED)
        val coordinator = OneShotAlarmCoordinator(repository, scheduler)

        val result = coordinator.save(enabledOneShot())

        assertTrue(result is AlarmSaveResult.BlockedExactAlarmAccess)
        assertEquals(AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS, repository.saved.single().schedulingState)
        assertTrue(repository.saved.single().configuredEnabled)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    @Test
    fun staleOrDuplicateOccurrenceCannotBeClaimedAfterCompletion() = runTest {
        val repository = FakeAlarmRepository(enabledOneShot().copy(
            scheduleRevision = 2,
            scheduledOccurrenceId = "current",
            schedulingState = AlarmSchedulingState.SCHEDULED,
        ))

        assertFalse(repository.claimScheduledOccurrence("alarm", "old", 1))
        assertTrue(repository.claimScheduledOccurrence("alarm", "current", 2))
        assertFalse(repository.claimScheduledOccurrence("alarm", "current", 2))
        assertTrue(repository.finishOccurrence("alarm", "current", 2, AlarmSchedulingState.COMPLETED))
        assertFalse(repository.finishOccurrence("alarm", "current", 2, AlarmSchedulingState.COMPLETED))
    }

    @Test
    fun snoozePersistsNewOccurrenceOnlyAfterExactSchedulingSucceeds() = runTest {
        val ringing = enabledOneShot().copy(
            scheduleRevision = 7,
            scheduledOccurrenceId = "ringing",
            schedulingState = AlarmSchedulingState.RINGING,
        )
        val repository = FakeAlarmRepository(ringing)
        val scheduler = FakeScheduler()
        val coordinator = OneShotAlarmCoordinator(repository, scheduler, FixedTimeProvider)

        val result = coordinator.snooze(RingingOccurrence("alarm", "ringing", 7))

        assertTrue(result is SnoozeResult.Snoozed)
        val saved = repository.saved.last()
        assertEquals(AlarmSchedulingState.SNOOZED, saved.schedulingState)
        assertEquals(8L, saved.scheduleRevision)
        assertEquals(Instant.parse("2026-10-15T07:05:00Z"), saved.scheduledAt)
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun duplicateOrStaleSnoozeCannotReplaceNewPendingOccurrence() = runTest {
        val ringing = enabledOneShot().copy(
            scheduleRevision = 2,
            scheduledOccurrenceId = "ringing",
            schedulingState = AlarmSchedulingState.RINGING,
        )
        val repository = FakeAlarmRepository(ringing)
        val scheduler = FakeScheduler()
        val coordinator = OneShotAlarmCoordinator(repository, scheduler, FixedTimeProvider)

        assertTrue(coordinator.snooze(RingingOccurrence("alarm", "ringing", 2)) is SnoozeResult.Snoozed)
        assertEquals(SnoozeResult.Stale, coordinator.snooze(RingingOccurrence("alarm", "ringing", 2)))
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun snoozeFailureLeavesCurrentOccurrenceRinging() = runTest {
        val ringing = enabledOneShot().copy(
            scheduleRevision = 2,
            scheduledOccurrenceId = "ringing",
            schedulingState = AlarmSchedulingState.RINGING,
        )
        val repository = FakeAlarmRepository(ringing)
        val coordinator = OneShotAlarmCoordinator(
            repository,
            FakeScheduler(platformResult = PlatformScheduleResult.Failed("Platform rejected snooze.")),
            FixedTimeProvider,
        )

        assertTrue(coordinator.snooze(RingingOccurrence("alarm", "ringing", 2)) is SnoozeResult.Failed)
        assertEquals(AlarmSchedulingState.RINGING, repository.get("alarm")!!.alarm.schedulingState)
    }

    @Test
    fun pendingSnoozeRestoresAndCanBeCancelled() = runTest {
        val snoozed = enabledOneShot().copy(
            scheduleRevision = 4,
            scheduledOccurrenceId = "snooze",
            scheduledAt = Instant.parse("2026-10-15T07:05:00Z"),
            schedulingState = AlarmSchedulingState.SNOOZED,
        )
        val repository = FakeAlarmRepository(snoozed)
        val scheduler = FakeScheduler()
        val coordinator = OneShotAlarmCoordinator(repository, scheduler, FixedTimeProvider)

        assertEquals("snooze", repository.get("alarm")!!.alarm.nextOccurrence()!!.occurrenceId)
        assertTrue(coordinator.cancelSnooze("alarm"))
        assertEquals(listOf("snooze"), scheduler.cancelled.map { it.occurrenceId })
        assertFalse(repository.get("alarm")!!.alarm.configuredEnabled)
    }

    private fun enabledOneShot() = Alarm(
        id = "alarm",
        label = "",
        messageText = "Pancakes",
        time = LocalTime.of(7, 0),
        repeatDays = emptySet(),
        configuredEnabled = true,
        resolvedOneShotAt = Instant.parse("2026-10-15T07:00:00Z"),
        audioAssetId = "asset",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}

private class FakeScheduler(
    private val access: SchedulingAccess = SchedulingAccess.GRANTED,
    private val platformResult: PlatformScheduleResult = PlatformScheduleResult.Scheduled,
) : OneShotAlarmScheduler {
    val scheduled = mutableListOf<ScheduledOccurrence>()
    val cancelled = mutableListOf<ScheduledOccurrence>()
    override fun access() = access
    override fun schedule(occurrence: ScheduledOccurrence): PlatformScheduleResult {
        scheduled += occurrence
        return platformResult
    }
    override fun cancel(occurrence: ScheduledOccurrence) { cancelled += occurrence }
}

private object FixedTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.parse("2026-10-15T07:00:00Z")
    override fun zoneId(): ZoneId = ZoneId.of("UTC")
}

private class FakeAlarmRepository(initial: Alarm? = null) : AlarmRepository {
    private val values = MutableStateFlow(initial?.let { listOf(AlarmWithAudio(it, null)) }.orEmpty())
    val saved = mutableListOf<Alarm>()

    override fun observeAll(): Flow<List<AlarmWithAudio>> = values
    override suspend fun get(id: String): AlarmWithAudio? = values.value.firstOrNull { it.alarm.id == id }
    override suspend fun save(alarm: Alarm) {
        saved += alarm
        values.value = values.value.filterNot { it.alarm.id == alarm.id } + AlarmWithAudio(alarm, null)
    }
    override suspend fun delete(id: String) { values.value = values.value.filterNot { it.alarm.id == id } }
    override suspend fun claimScheduledOccurrence(alarmId: String, occurrenceId: String, revision: Long): Boolean {
        val row = get(alarmId) ?: return false
        if (row.alarm.scheduledOccurrenceId != occurrenceId || row.alarm.scheduleRevision != revision ||
            row.alarm.schedulingState != AlarmSchedulingState.SCHEDULED || !row.alarm.configuredEnabled
        ) return false
        save(row.alarm.copy(schedulingState = AlarmSchedulingState.RINGING))
        return true
    }
    override suspend fun finishOccurrence(
        alarmId: String,
        occurrenceId: String,
        revision: Long,
        finalState: AlarmSchedulingState,
    ): Boolean {
        val row = get(alarmId) ?: return false
        if (row.alarm.scheduledOccurrenceId != occurrenceId || row.alarm.scheduleRevision != revision ||
            row.alarm.schedulingState != AlarmSchedulingState.RINGING
        ) return false
        save(row.alarm.copy(
            configuredEnabled = false,
            scheduledOccurrenceId = null,
            scheduledAt = null,
            schedulingState = finalState,
        ))
        return true
    }
    override suspend fun replaceRingingWithSnooze(
        alarmId: String, ringingOccurrenceId: String, ringingRevision: Long,
        snoozeOccurrenceId: String, snoozeRevision: Long, snoozedAtEpochMillis: Long,
    ): Boolean {
        val row = get(alarmId) ?: return false
        if (row.alarm.schedulingState != AlarmSchedulingState.RINGING ||
            row.alarm.scheduledOccurrenceId != ringingOccurrenceId || row.alarm.scheduleRevision != ringingRevision
        ) return false
        save(row.alarm.copy(
            scheduleRevision = snoozeRevision,
            scheduledOccurrenceId = snoozeOccurrenceId,
            scheduledAt = Instant.ofEpochMilli(snoozedAtEpochMillis),
            schedulingState = AlarmSchedulingState.SNOOZED,
        ))
        return true
    }
}
