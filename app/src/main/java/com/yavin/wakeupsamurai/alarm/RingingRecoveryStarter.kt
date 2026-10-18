package com.yavin.wakeupsamurai.alarm

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Foreground-entry bridge; the service still validates persisted identity and deadline. */
class RingingRecoveryStarter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val coordinator: OneShotAlarmCoordinator,
) {
    suspend fun start(recoverable: List<RecoverableRinging>) {
        val occurrence = recoverable.firstOrNull()?.occurrence ?: return
        try {
            ContextCompat.startForegroundService(
                context,
                RingingAlarmService.startIntent(context, occurrence.alarmId, occurrence.occurrenceId,
                    occurrence.revision),
            )
        } catch (_: Exception) {
            runCatching {
                coordinator.finishOccurrence(occurrence.alarmId, occurrence.occurrenceId, occurrence.revision,
                    AlarmSchedulingState.FAILED,
                    "Android could not restart alarm playback. Check alarm and notification access.")
            }.onFailure { Log.w("RingingRecovery", "Could not persist playback-start failure", it) }
        }
    }
}
