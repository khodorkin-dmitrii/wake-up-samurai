package com.yavin.wakeupsamurai.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Reconciles only after credential-encrypted Room data is available. */
@AndroidEntryPoint
class AlarmRecoveryReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: OneShotAlarmCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconcileReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> ReconcileReason.CLOCK_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ReconcileReason.ACCESS_GRANTED
            else -> return
        }
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000L) { coordinator.reconcile(reason) }
            } catch (exception: Exception) {
                // A later foreground/system pass resumes from Room; never crash the broadcast process.
                Log.w("AlarmRecovery", "Schedule reconciliation was interrupted", exception)
            } finally {
                pending.finish()
            }
        }
    }
}
