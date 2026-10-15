package com.yavin.wakeupsamurai.alarm

import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AlarmSaveResult {
    data class Scheduled(val alarm: Alarm) : AlarmSaveResult
    data class BlockedExactAlarmAccess(val alarm: Alarm) : AlarmSaveResult
    data class BlockedNotificationPermission(val alarm: Alarm) : AlarmSaveResult
    data class SavedWithoutSchedule(val alarm: Alarm) : AlarmSaveResult
    data class Failed(val message: String) : AlarmSaveResult
}

/** Coordinates the persisted source of truth with derived one-shot AlarmManager entries. */
@Singleton
class OneShotAlarmCoordinator @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: OneShotAlarmScheduler,
) {
    suspend fun save(alarm: Alarm): AlarmSaveResult {
        val previous = repository.get(alarm.id)?.alarm
        previous?.nextOccurrence()?.let(scheduler::cancel)

        if (!alarm.configuredEnabled) {
            val saved = alarm.clearSchedule(AlarmSchedulingState.NOT_SCHEDULED)
            repository.save(saved)
            return AlarmSaveResult.SavedWithoutSchedule(saved)
        }
        if (alarm.repeatDays.isNotEmpty()) {
            val saved = alarm.clearSchedule(AlarmSchedulingState.UNSUPPORTED_RECURRING)
            repository.save(saved)
            return AlarmSaveResult.SavedWithoutSchedule(saved)
        }

        return when (scheduler.access()) {
            SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED -> {
                val saved = alarm.clearSchedule(AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS)
                repository.save(saved)
                AlarmSaveResult.BlockedExactAlarmAccess(saved)
            }
            SchedulingAccess.NOTIFICATION_PERMISSION_REQUIRED -> {
                val saved = alarm.clearSchedule(AlarmSchedulingState.BLOCKED_NOTIFICATION_PERMISSION)
                repository.save(saved)
                AlarmSaveResult.BlockedNotificationPermission(saved)
            }
            SchedulingAccess.GRANTED -> schedule(alarm)
        }
    }

    suspend fun retry(alarmId: String): AlarmSaveResult {
        val alarm = repository.get(alarmId)?.alarm ?: return AlarmSaveResult.Failed("Alarm not found.")
        return save(alarm)
    }

    suspend fun delete(alarmId: String) {
        repository.get(alarmId)?.alarm?.nextOccurrence()?.let(scheduler::cancel)
        repository.delete(alarmId)
    }

    private suspend fun schedule(alarm: Alarm): AlarmSaveResult {
        val occurrence = runCatching { newOccurrence(alarm) }.getOrElse {
            return AlarmSaveResult.Failed("Could not calculate the next alarm occurrence.")
        }
        return when (val result = scheduler.schedule(occurrence)) {
            PlatformScheduleResult.Scheduled -> {
                val saved = alarm.copy(
                    scheduleRevision = occurrence.revision,
                    scheduledOccurrenceId = occurrence.occurrenceId,
                    scheduledAt = alarm.resolvedOneShotAt,
                    schedulingState = AlarmSchedulingState.SCHEDULED,
                )
                try {
                    repository.save(saved)
                    AlarmSaveResult.Scheduled(saved)
                } catch (exception: Exception) {
                    scheduler.cancel(occurrence)
                    AlarmSaveResult.Failed(exception.message ?: "Could not save the scheduled alarm.")
                }
            }
            PlatformScheduleResult.ExactAlarmAccessRequired -> {
                val saved = alarm.clearSchedule(AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS)
                repository.save(saved)
                AlarmSaveResult.BlockedExactAlarmAccess(saved)
            }
            is PlatformScheduleResult.Failed -> {
                val saved = alarm.clearSchedule(AlarmSchedulingState.FAILED)
                repository.save(saved)
                AlarmSaveResult.Failed(result.message)
            }
        }
    }

    private fun Alarm.clearSchedule(state: AlarmSchedulingState): Alarm = copy(
        scheduleRevision = scheduleRevision + 1,
        scheduledOccurrenceId = null,
        scheduledAt = null,
        schedulingState = state,
    )
}
