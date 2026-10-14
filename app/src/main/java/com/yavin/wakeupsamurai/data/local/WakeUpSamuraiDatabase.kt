package com.yavin.wakeupsamurai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AlarmEntity::class, AudioAssetEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class WakeUpSamuraiDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun audioAssetDao(): AudioAssetDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN audioSelectionKind TEXT")
    }
}
