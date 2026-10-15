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
        UPDATE alarms SET schedulingState = 'RINGING'
        WHERE id = :alarmId
          AND configuredEnabled = 1
          AND scheduledOccurrenceId = :occurrenceId
          AND scheduleRevision = :revision
          AND schedulingState = 'SCHEDULED'
    """)
    suspend fun claimScheduledOccurrence(alarmId: String, occurrenceId: String, revision: Long): Int

    @Query("""
        UPDATE alarms
        SET configuredEnabled = 0,
            schedulingState = :finalState,
            scheduledOccurrenceId = NULL,
            scheduledAtEpochMillis = NULL
        WHERE id = :alarmId
          AND scheduledOccurrenceId = :occurrenceId
          AND scheduleRevision = :revision
          AND schedulingState = 'RINGING'
    """)
    suspend fun finishRingingOccurrence(
        alarmId: String,
        occurrenceId: String,
        revision: Long,
        finalState: String,
    ): Int
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
