package com.yavin.wakeupsamurai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Notification snooze action; actual transition is guarded by the active occurrence identity. */
@AndroidEntryPoint
class AlarmSnoozeReceiver : BroadcastReceiver() {
    @Inject lateinit var ringingController: RingingAlarmController

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ringingController.snooze(RingingOccurrence(alarmId, occurrenceId, revision))
            } finally {
                result.finish()
            }
        }
    }
}
