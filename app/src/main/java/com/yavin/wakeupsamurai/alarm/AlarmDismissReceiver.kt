package com.yavin.wakeupsamurai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Notification action is keyed to one stable delivery, never merely an alarm ID. */
@AndroidEntryPoint
class AlarmDismissReceiver : BroadcastReceiver() {
    @Inject lateinit var ringingController: RingingAlarmController

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return
        ringingController.dismiss(
            RingingOccurrence(alarmId, occurrenceId, revision),
        )
    }
}
