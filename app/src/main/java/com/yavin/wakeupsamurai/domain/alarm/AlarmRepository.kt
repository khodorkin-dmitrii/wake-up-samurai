package com.yavin.wakeupsamurai.domain.alarm

import kotlinx.coroutines.flow.Flow

interface AlarmRepository {
    fun observeAll(): Flow<List<AlarmWithAudio>>
    suspend fun get(id: String): AlarmWithAudio?
    suspend fun save(alarm: Alarm)
    suspend fun delete(id: String)
}

interface AudioAssetRepository {
    suspend fun get(id: String): AudioAsset?
    suspend fun register(asset: AudioAsset): AudioAsset
    suspend fun importExistingCompletedFiles()
}
