package com.yavin.wakeupsamurai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Notification action is keyed to one stable delivery, never merely an alarm ID. */
class AlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return
        ContextCompat.startForegroundService(
            context,
            RingingAlarmService.dismissIntent(context, alarmId, occurrenceId, revision),
        )
    }
}
