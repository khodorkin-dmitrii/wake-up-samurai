package com.yavin.wakeupsamurai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Does only identity validation and foreground-service startup; playback belongs to the service. */
@AndroidEntryPoint
class AlarmTriggerReceiver : BroadcastReceiver() {
    @Inject lateinit var alarmRepository: AlarmRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidOneShotAlarmScheduler.ACTION_TRIGGER) return
        val alarmId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (alarmRepository.claimScheduledOccurrence(alarmId, occurrenceId, revision)) {
                    ContextCompat.startForegroundService(
                        context,
                        RingingAlarmService.startIntent(context, alarmId, occurrenceId, revision),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
