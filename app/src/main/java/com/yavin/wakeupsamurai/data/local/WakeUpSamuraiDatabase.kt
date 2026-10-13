package com.yavin.wakeupsamurai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AlarmEntity::class, AudioAssetEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WakeUpSamuraiDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun audioAssetDao(): AudioAssetDao
}
