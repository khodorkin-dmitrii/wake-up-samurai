package com.yavin.wakeupsamurai.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.yavin.wakeupsamurai.MainActivity
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduledOccurrence(
    val alarmId: String,
    val occurrenceId: String,
    val revision: Long,
    val triggerAtEpochMillis: Long,
)

enum class SchedulingAccess {
    GRANTED,
    EXACT_ALARM_ACCESS_REQUIRED,
    NOTIFICATION_PERMISSION_REQUIRED,
}

sealed interface PlatformScheduleResult {
    data object Scheduled : PlatformScheduleResult
    data object ExactAlarmAccessRequired : PlatformScheduleResult
    data class Failed(val message: String) : PlatformScheduleResult
}

interface OneShotAlarmScheduler {
    fun access(): SchedulingAccess
    fun schedule(occurrence: ScheduledOccurrence): PlatformScheduleResult
    fun cancel(occurrence: ScheduledOccurrence)
}

@Singleton
class AndroidOneShotAlarmScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OneShotAlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun access(): SchedulingAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms() ->
            SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
            SchedulingAccess.NOTIFICATION_PERMISSION_REQUIRED
        else -> SchedulingAccess.GRANTED
    }

    override fun schedule(occurrence: ScheduledOccurrence): PlatformScheduleResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return PlatformScheduleResult.ExactAlarmAccessRequired
        }
        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    occurrence.triggerAtEpochMillis,
                    PendingIntent.getActivity(
                        context,
                        occurrence.requestCode(),
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ),
                occurrence.pendingIntent(),
            )
            PlatformScheduleResult.Scheduled
        } catch (_: SecurityException) {
            PlatformScheduleResult.ExactAlarmAccessRequired
        } catch (exception: Exception) {
            PlatformScheduleResult.Failed(exception.message ?: "Android could not schedule this alarm.")
        }
    }

    override fun cancel(occurrence: ScheduledOccurrence) {
        val pendingIntent = occurrence.pendingIntent()
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun ScheduledOccurrence.pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(),
        Intent(context, AlarmTriggerReceiver::class.java)
            .setAction(ACTION_TRIGGER)
            .setData(Uri.parse("wakeupsamurai://alarm/${alarmId}/${occurrenceId}"))
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .putExtra(EXTRA_OCCURRENCE_ID, occurrenceId)
            .putExtra(EXTRA_REVISION, revision),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ScheduledOccurrence.requestCode(): Int =
        "$alarmId:$occurrenceId".hashCode()

    companion object {
        const val ACTION_TRIGGER = "com.yavin.wakeupsamurai.action.TRIGGER_ALARM"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_OCCURRENCE_ID = "occurrence_id"
        const val EXTRA_REVISION = "revision"
    }
}

internal fun Alarm.nextOccurrence(): ScheduledOccurrence? = scheduledOccurrenceId?.let { occurrenceId ->
    scheduledAt?.let { at ->
        ScheduledOccurrence(id, occurrenceId, scheduleRevision, at.toEpochMilli())
    }
}

internal fun newOccurrence(alarm: Alarm): ScheduledOccurrence = ScheduledOccurrence(
    alarmId = alarm.id,
    occurrenceId = UUID.randomUUID().toString(),
    revision = alarm.scheduleRevision + 1,
    triggerAtEpochMillis = requireNotNull(alarm.resolvedOneShotAt).toEpochMilli(),
)
