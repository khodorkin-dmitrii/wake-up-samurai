package com.yavin.wakeupsamurai.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Creates the alarm channel only when absent; existing user settings are never overwritten. */
object RingingNotificationChannel {
    const val ID = "ringing_alarms_v2"

    fun ensureAndIsEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(ID)
        if (existing == null) {
            manager.createNotificationChannel(
                NotificationChannel(ID, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Controls for an active Wake Up, Samurai alarm"
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        return manager.getNotificationChannel(ID)?.importance?.let {
            it != NotificationManager.IMPORTANCE_NONE
        } ?: false
    }

    fun isHighImportance(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        ensureAndIsEnabled(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        return (manager.getNotificationChannel(ID)?.importance ?: NotificationManager.IMPORTANCE_NONE) >=
            NotificationManager.IMPORTANCE_HIGH
    }
}
