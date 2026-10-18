package com.yavin.wakeupsamurai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AlarmEntity::class, AudioAssetEntity::class],
    version = 6,
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeOccurrenceId TEXT")
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeRevision INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozedAtEpochMillis INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN ringingOccurrenceId TEXT")
        db.execSQL("ALTER TABLE alarms ADD COLUMN ringingRevision INTEGER")
        db.execSQL("""
            UPDATE alarms SET snoozeOccurrenceId = scheduledOccurrenceId,
                snoozeRevision = scheduleRevision,
                snoozedAtEpochMillis = scheduledAtEpochMillis,
                scheduledOccurrenceId = NULL,
                scheduledAtEpochMillis = NULL
            WHERE schedulingState = 'SNOOZED'
        """.trimIndent())
        db.execSQL("""
            UPDATE alarms SET ringingOccurrenceId = scheduledOccurrenceId,
                ringingRevision = scheduleRevision,
                scheduledOccurrenceId = NULL,
                scheduledAtEpochMillis = NULL
            WHERE schedulingState = 'RINGING'
        """.trimIndent())
        db.execSQL("""
            UPDATE alarms SET schedulingState = 'NOT_SCHEDULED'
            WHERE schedulingState = 'UNSUPPORTED_RECURRING'
        """.trimIndent())
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN regularScheduledBootId INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeScheduledBootId INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN snoozeElapsedDeadlineMillis INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN ringingBootId INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN lastRegularDeliveredAtEpochMillis INTEGER")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE alarms ADD COLUMN ringingDeadlineElapsedMillis INTEGER")
        db.execSQL("ALTER TABLE alarms ADD COLUMN lastRingingFailure TEXT")
    }
}
