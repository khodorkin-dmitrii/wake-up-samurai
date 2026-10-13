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

    @Upsert
    suspend fun upsert(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface AudioAssetDao {
    @Query("SELECT * FROM audio_assets WHERE id = :id")
    suspend fun get(id: String): AudioAssetEntity?

    @Query("SELECT * FROM audio_assets WHERE relativePath = :relativePath")
    suspend fun getByPath(relativePath: String): AudioAssetEntity?

    @Upsert
    suspend fun upsert(asset: AudioAssetEntity)
}
