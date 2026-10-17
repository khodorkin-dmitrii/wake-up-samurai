package com.yavin.wakeupsamurai.alarm

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A stable identity for one currently ringing delivery. */
data class RingingOccurrence(
    val alarmId: String,
    val occurrenceId: String,
    val revision: Long,
)

/** Shared command boundary for the notification and ringing UI. */
interface RingingAlarmController {
    fun dismiss(occurrence: RingingOccurrence)
    suspend fun snooze(occurrence: RingingOccurrence): SnoozeResult
}

@Singleton
class AndroidRingingAlarmController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val alarmCoordinator: OneShotAlarmCoordinator,
) : RingingAlarmController {
    override fun dismiss(occurrence: RingingOccurrence) {
        ContextCompat.startForegroundService(
            context,
            RingingAlarmService.dismissIntent(
                context = context,
                alarmId = occurrence.alarmId,
                occurrenceId = occurrence.occurrenceId,
                revision = occurrence.revision,
            ),
        )
    }

    override suspend fun snooze(occurrence: RingingOccurrence): SnoozeResult {
        val result = alarmCoordinator.snooze(occurrence)
        val intent = when (result) {
            is SnoozeResult.Snoozed -> RingingAlarmService.stopAfterSnoozeIntent(
                context, occurrence.alarmId, occurrence.occurrenceId, occurrence.revision,
            )
            is SnoozeResult.AccessRequired -> RingingAlarmService.snoozeFailedIntent(
                context, occurrence.alarmId, occurrence.occurrenceId, occurrence.revision, result.message,
            )
            is SnoozeResult.Failed -> RingingAlarmService.snoozeFailedIntent(
                context, occurrence.alarmId, occurrence.occurrenceId, occurrence.revision, result.message,
            )
            SnoozeResult.Stale -> null
        }
        intent?.let { ContextCompat.startForegroundService(context, it) }
        return result
    }
}
