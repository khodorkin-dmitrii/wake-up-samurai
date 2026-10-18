package com.yavin.wakeupsamurai.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import android.util.Log

/** Does only identity validation and foreground-service startup; playback belongs to the service. */
@AndroidEntryPoint
class AlarmTriggerReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: OneShotAlarmCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidOneShotAlarmScheduler.ACTION_TRIGGER) return
        val alarmId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val occurrenceId = intent.getStringExtra(AndroidOneShotAlarmScheduler.EXTRA_OCCURRENCE_ID) ?: return
        val revision = intent.getLongExtra(AndroidOneShotAlarmScheduler.EXTRA_REVISION, -1L)
        if (revision < 0L) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var accepted = false
            try {
                withTimeout(8_000L) {
                    if (coordinator.acceptDelivery(alarmId, occurrenceId, revision) == DeliveryDisposition.RING) {
                        accepted = true
                        try {
                            ContextCompat.startForegroundService(
                                context,
                                RingingAlarmService.startIntent(context, alarmId, occurrenceId, revision),
                            )
                        } catch (exception: Exception) {
                            coordinator.finishOccurrence(alarmId, occurrenceId, revision,
                                com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState.FAILED,
                                "Android could not start alarm playback. Check alarm and notification access.")
                            accepted = false
                        }
                    }
                }
            } catch (exception: Exception) {
                Log.w("AlarmTrigger", "Alarm delivery was interrupted", exception)
                if (accepted) runCatching {
                    coordinator.finishOccurrence(alarmId, occurrenceId, revision,
                        com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState.FAILED,
                        "Alarm delivery was interrupted. Check alarm access and try again.")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
