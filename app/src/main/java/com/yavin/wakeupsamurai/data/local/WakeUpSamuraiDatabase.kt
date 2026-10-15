package com.yavin.wakeupsamurai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AlarmEntity::class, AudioAssetEntity::class],
    version = 3,
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

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN scheduleRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE alarms ADD COLUMN scheduledOccurrenceId TEXT")
        db.execSQL("ALTER TABLE alarms ADD COLUMN scheduledAtEpochMillis INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN schedulingState TEXT NOT NULL DEFAULT 'NOT_SCHEDULED'")
    }
}
