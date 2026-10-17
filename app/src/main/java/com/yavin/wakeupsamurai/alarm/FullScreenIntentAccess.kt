package com.yavin.wakeupsamurai.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface FullScreenIntentAccess {
    fun canUseFullScreenIntent(): Boolean
    fun isAlarmChannelHighImportance(): Boolean
    fun settingsIntent(): Intent?
}

@Singleton
class AndroidFullScreenIntentAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : FullScreenIntentAccess {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notificationManager.canUseFullScreenIntent()

    override fun isAlarmChannelHighImportance(): Boolean = RingingNotificationChannel.isHighImportance(context)

    override fun settingsIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(android.net.Uri.fromParts("package", context.packageName, null))
        } else {
            null
        }
}
