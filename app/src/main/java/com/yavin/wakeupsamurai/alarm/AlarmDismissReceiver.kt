package com.yavin.wakeupsamurai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Notification action is keyed to one stable delivery, never merely an alarm ID. */
@AndroidEntryPoint
class AlarmDismissReceiver : BroadcastReceiver() {
    @Inject lateinit var ringingController: RingingAlarmController

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) { ringingController.dismiss(RingingOccurrence(alarmId, occurrenceId, revision)) }
            } catch (error: Exception) {
                Log.w("AlarmDismiss", "Dismiss command could not be delivered", error)
            } finally {
                pending.finish()
            }
        }
    }
}
