package com.yavin.wakeupsamurai.alarm

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** BOOT_COUNT is stable across process death and wall-clock changes. */
interface AlarmBootClock {
    fun bootId(): Long
    fun elapsedRealtimeMillis(): Long
}

class AndroidAlarmBootClock @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AlarmBootClock {
    override fun bootId(): Long = Settings.Global.getInt(
        context.contentResolver, Settings.Global.BOOT_COUNT, -1,
    ).toLong()

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
