package com.yavin.wakeupsamurai.alarm

import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import com.yavin.wakeupsamurai.domain.alarm.SystemTimeProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AlarmSaveResult {
    data class Scheduled(val alarm: Alarm) : AlarmSaveResult
    data class BlockedExactAlarmAccess(val alarm: Alarm) : AlarmSaveResult
    data class BlockedNotificationPermission(val alarm: Alarm) : AlarmSaveResult
    data class BlockedNotificationsDisabled(val alarm: Alarm) : AlarmSaveResult
    data class BlockedAlarmChannel(val alarm: Alarm) : AlarmSaveResult
    data class SavedWithoutSchedule(val alarm: Alarm) : AlarmSaveResult
    data class Failed(val message: String) : AlarmSaveResult
}

/** Coordinates the persisted source of truth with derived one-shot AlarmManager entries. */
@Singleton
class OneShotAlarmCoordinator @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: OneShotAlarmScheduler,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
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
            SchedulingAccess.NOTIFICATIONS_DISABLED -> {
                val saved = alarm.clearSchedule(AlarmSchedulingState.BLOCKED_NOTIFICATION_PERMISSION)
                repository.save(saved)
                AlarmSaveResult.BlockedNotificationsDisabled(saved)
            }
            SchedulingAccess.ALARM_CHANNEL_DISABLED -> {
                val saved = alarm.clearSchedule(AlarmSchedulingState.FAILED)
                repository.save(saved)
                AlarmSaveResult.BlockedAlarmChannel(saved)
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

    /** setAlarmClock snooze policy: exact wall-clock action time plus five minutes. */
    suspend fun snooze(ringing: RingingOccurrence): SnoozeResult {
        val alarm = repository.get(ringing.alarmId)?.alarm ?: return SnoozeResult.Stale
        if (alarm.schedulingState != AlarmSchedulingState.RINGING ||
            alarm.scheduledOccurrenceId != ringing.occurrenceId || alarm.scheduleRevision != ringing.revision
        ) return SnoozeResult.Stale
        when (scheduler.access()) {
            SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED -> return SnoozeResult.AccessRequired("Allow alarms & reminders to snooze this alarm.")
            SchedulingAccess.NOTIFICATION_PERMISSION_REQUIRED -> return SnoozeResult.AccessRequired("Allow notifications to keep the snoozed alarm accessible.")
            SchedulingAccess.NOTIFICATIONS_DISABLED -> return SnoozeResult.AccessRequired("Enable app notifications to keep the snoozed alarm accessible.")
            SchedulingAccess.ALARM_CHANNEL_DISABLED -> return SnoozeResult.AccessRequired("Enable the Ringing alarms channel to snooze this alarm.")
            SchedulingAccess.GRANTED -> Unit
        }
        val next = ScheduledOccurrence(
            alarm.id,
            UUID.randomUUID().toString(),
            alarm.scheduleRevision + 1,
            timeProvider.now().plusMillis(SNOOZE_DURATION_MILLIS).toEpochMilli(),
        )
        when (val platform = scheduler.schedule(next)) {
            PlatformScheduleResult.Scheduled -> Unit
            PlatformScheduleResult.ExactAlarmAccessRequired -> return SnoozeResult.AccessRequired("Allow alarms & reminders to snooze this alarm.")
            is PlatformScheduleResult.Failed -> return SnoozeResult.Failed(platform.message)
        }
        val persisted = repository.replaceRingingWithSnooze(
            alarm.id, ringing.occurrenceId, ringing.revision,
            next.occurrenceId, next.revision, next.triggerAtEpochMillis,
        )
        if (!persisted) {
            scheduler.cancel(next)
            return SnoozeResult.Stale
        }
        return SnoozeResult.Snoozed(next)
    }

    suspend fun cancelSnooze(alarmId: String): Boolean {
        val alarm = repository.get(alarmId)?.alarm ?: return false
        if (alarm.schedulingState != AlarmSchedulingState.SNOOZED) return false
        repository.save(alarm.copy(configuredEnabled = false).clearSchedule(AlarmSchedulingState.NOT_SCHEDULED))
        alarm.nextOccurrence()?.let(scheduler::cancel)
        return true
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

    private companion object {
        const val SNOOZE_DURATION_MILLIS = 5 * 60 * 1_000L
    }
}

sealed interface SnoozeResult {
    data class Snoozed(val occurrence: ScheduledOccurrence) : SnoozeResult
    data class AccessRequired(val message: String) : SnoozeResult
    data class Failed(val message: String) : SnoozeResult
    data object Stale : SnoozeResult
}
