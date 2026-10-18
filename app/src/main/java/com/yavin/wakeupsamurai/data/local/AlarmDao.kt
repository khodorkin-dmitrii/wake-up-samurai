package com.yavin.wakeupsamurai.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    @Transaction
    @Query("SELECT * FROM alarms ORDER BY hour, minute, createdAtEpochMillis")
    fun observeAll(): Flow<List<AlarmWithAudioEntity>>

    @Transaction
    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun get(id: String): AlarmWithAudioEntity?

    @Query("SELECT * FROM alarms")
    fun observeEntities(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms WHERE audioAssetId = :assetId ORDER BY createdAtEpochMillis")
    suspend fun referencingAssets(assetId: String): List<AlarmEntity>

    @Upsert
    suspend fun upsert(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        UPDATE alarms SET ringingOccurrenceId = :occurrenceId, ringingRevision = :revision,
            ringingBootId = :bootId, ringingDeadlineElapsedMillis = :deadlineElapsedMillis,
            lastRingingFailure = NULL, scheduledOccurrenceId = NULL, scheduledAtEpochMillis = NULL,
            regularScheduledBootId = NULL, lastRegularDeliveredAtEpochMillis = scheduledAtEpochMillis,
            schedulingState = 'RINGING'
        WHERE id = :alarmId AND configuredEnabled = 1 AND ringingOccurrenceId IS NULL
          AND scheduledOccurrenceId = :occurrenceId AND scheduleRevision = :revision
    """)
    suspend fun claimRegular(alarmId: String, occurrenceId: String, revision: Long, bootId: Long,
        deadlineElapsedMillis: Long): Int

    @Query("""
        UPDATE alarms SET scheduledOccurrenceId = NULL, scheduledAtEpochMillis = NULL,
            regularScheduledBootId = NULL, lastRegularDeliveredAtEpochMillis = scheduledAtEpochMillis
        WHERE id = :alarmId AND configuredEnabled = 1 AND ringingOccurrenceId IS NOT NULL
          AND scheduledOccurrenceId = :occurrenceId AND scheduleRevision = :revision
    """)
    suspend fun skipRegular(alarmId: String, occurrenceId: String, revision: Long): Int

    @Query("""
        UPDATE alarms SET ringingOccurrenceId = :occurrenceId, ringingRevision = :revision,
            ringingBootId = :bootId, ringingDeadlineElapsedMillis = :deadlineElapsedMillis,
            lastRingingFailure = NULL, snoozeOccurrenceId = NULL, snoozeRevision = NULL,
            snoozedAtEpochMillis = NULL, snoozeScheduledBootId = NULL,
            snoozeElapsedDeadlineMillis = NULL,
            schedulingState = 'RINGING'
        WHERE id = :alarmId AND configuredEnabled = 1 AND ringingOccurrenceId IS NULL
          AND snoozeOccurrenceId = :occurrenceId AND snoozeRevision = :revision
    """)
    suspend fun claimSnooze(alarmId: String, occurrenceId: String, revision: Long, bootId: Long,
        deadlineElapsedMillis: Long): Int

    @Query("""
        UPDATE alarms SET snoozeOccurrenceId = NULL, snoozeRevision = NULL, snoozedAtEpochMillis = NULL,
            snoozeScheduledBootId = NULL, snoozeElapsedDeadlineMillis = NULL
        WHERE id = :alarmId AND configuredEnabled = 1 AND ringingOccurrenceId IS NOT NULL
          AND snoozeOccurrenceId = :occurrenceId AND snoozeRevision = :revision
    """)
    suspend fun skipSnooze(alarmId: String, occurrenceId: String, revision: Long): Int

    @Query("""
        UPDATE alarms SET scheduleRevision = :revision, scheduledOccurrenceId = :occurrenceId,
            scheduledAtEpochMillis = :at, regularScheduledBootId = :bootId,
            schedulingState = CASE WHEN ringingOccurrenceId IS NOT NULL THEN 'RINGING'
                WHEN snoozeOccurrenceId IS NOT NULL THEN 'SNOOZED' ELSE 'SCHEDULED' END
        WHERE id = :alarmId AND configuredEnabled = 1 AND repeatDaysMask != 0
          AND scheduledOccurrenceId IS NULL AND scheduleRevision = :previousRevision
    """)
    suspend fun installNextRegular(alarmId: String, previousRevision: Long, occurrenceId: String, revision: Long, at: Long, bootId: Long): Int

    @Query("""
        UPDATE alarms SET schedulingState = :state
        WHERE id = :alarmId AND configuredEnabled = 1 AND repeatDaysMask != 0
          AND scheduledOccurrenceId IS NULL AND scheduleRevision = :previousRevision
    """)
    suspend fun markRegularSchedulingFailed(alarmId: String, previousRevision: Long, state: String): Int

    @Query("""
        UPDATE alarms
        SET ringingOccurrenceId = NULL, ringingRevision = NULL, ringingBootId = NULL,
            ringingDeadlineElapsedMillis = NULL, lastRingingFailure = :failureMessage,
            configuredEnabled = CASE WHEN repeatDaysMask = 0 AND snoozeOccurrenceId IS NULL THEN 0 ELSE configuredEnabled END,
            schedulingState = CASE
                WHEN snoozeOccurrenceId IS NOT NULL THEN 'SNOOZED'
                WHEN scheduledOccurrenceId IS NOT NULL THEN 'SCHEDULED'
                WHEN repeatDaysMask != 0 AND schedulingState IN ('FAILED', 'BLOCKED_EXACT_ALARM_ACCESS', 'BLOCKED_NOTIFICATION_PERMISSION')
                    THEN schedulingState
                WHEN repeatDaysMask != 0 THEN 'FAILED'
                ELSE :finalState END
        WHERE id = :alarmId
          AND ringingOccurrenceId = :occurrenceId
          AND ringingRevision = :revision
    """)
    suspend fun finishRingingOccurrence(
        alarmId: String,
        occurrenceId: String,
        revision: Long,
        finalState: String,
        failureMessage: String?,
    ): Int

    @Query("""
        UPDATE alarms
        SET snoozeOccurrenceId = :snoozeOccurrenceId,
            snoozeRevision = :snoozeRevision,
            snoozedAtEpochMillis = :snoozedAtEpochMillis,
            snoozeElapsedDeadlineMillis = :elapsedDeadlineMillis,
            snoozeScheduledBootId = :bootId,
            schedulingState = 'SNOOZED',
            ringingOccurrenceId = NULL, ringingRevision = NULL, ringingBootId = NULL,
            ringingDeadlineElapsedMillis = NULL
        WHERE id = :alarmId
          AND ringingOccurrenceId = :ringingOccurrenceId
          AND ringingRevision = :ringingRevision
          AND configuredEnabled = 1
    """)
    suspend fun replaceRingingWithSnooze(
        alarmId: String,
        ringingOccurrenceId: String,
        ringingRevision: Long,
        snoozeOccurrenceId: String,
        snoozeRevision: Long,
        snoozedAtEpochMillis: Long,
        elapsedDeadlineMillis: Long,
        bootId: Long,
    ): Int

    @Query("""
        UPDATE alarms SET snoozeOccurrenceId = NULL, snoozeRevision = NULL, snoozedAtEpochMillis = NULL,
            snoozeScheduledBootId = NULL, snoozeElapsedDeadlineMillis = NULL,
            configuredEnabled = CASE WHEN repeatDaysMask = 0 THEN 0 ELSE configuredEnabled END,
            schedulingState = CASE WHEN ringingOccurrenceId IS NOT NULL THEN 'RINGING'
                WHEN scheduledOccurrenceId IS NOT NULL THEN 'SCHEDULED'
                WHEN repeatDaysMask != 0 THEN 'FAILED' ELSE 'NOT_SCHEDULED' END
        WHERE id = :alarmId AND snoozeOccurrenceId = :occurrenceId AND snoozeRevision = :revision
    """)
    suspend fun clearPendingSnooze(alarmId: String, occurrenceId: String, revision: Long): Int
}

@Dao
interface AudioAssetDao {
    @Query("SELECT * FROM audio_assets ORDER BY createdAtEpochMillis DESC, id DESC")
    fun observeAll(): Flow<List<AudioAssetEntity>>

    @Query("SELECT * FROM audio_assets WHERE id = :id")
    suspend fun get(id: String): AudioAssetEntity?

    @Query("SELECT * FROM audio_assets WHERE relativePath = :relativePath")
    suspend fun getByPath(relativePath: String): AudioAssetEntity?

    @Query("DELETE FROM audio_assets WHERE id = :id")
    suspend fun delete(id: String)

    @Upsert
    suspend fun upsert(asset: AudioAssetEntity)
}
