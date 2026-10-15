package com.yavin.wakeupsamurai.alarm

import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AlarmWithAudio
import java.time.Instant
import java.time.LocalTime
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
) : OneShotAlarmScheduler {
    val scheduled = mutableListOf<ScheduledOccurrence>()
    val cancelled = mutableListOf<ScheduledOccurrence>()
    override fun access() = access
    override fun schedule(occurrence: ScheduledOccurrence): PlatformScheduleResult {
        scheduled += occurrence
        return PlatformScheduleResult.Scheduled
    }
    override fun cancel(occurrence: ScheduledOccurrence) { cancelled += occurrence }
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
}
