package com.yavin.wakeupsamurai.domain.alarm

import kotlinx.coroutines.flow.Flow

interface AlarmRepository {
    fun observeAll(): Flow<List<AlarmWithAudio>>
    suspend fun get(id: String): AlarmWithAudio?
    suspend fun save(alarm: Alarm)
    suspend fun delete(id: String)
}

interface AudioAssetRepository {
    fun observeAll(): Flow<List<AudioAsset>>
    suspend fun get(id: String): AudioAsset?
    suspend fun register(asset: AudioAsset): AudioAsset
    suspend fun importExistingCompletedFiles()
}

data class AudioAssetAlarmUse(
    val alarmId: String,
    val label: String,
)

data class ManagedAudioAsset(
    val asset: AudioAsset,
    val alarmUses: List<AudioAssetAlarmUse>,
    val fileSizeBytes: Long?,
    val isAvailable: Boolean,
)

sealed interface AudioDeletionResult {
    data class Deleted(val assetId: String, val fileWasMissing: Boolean) : AudioDeletionResult
    data class Referenced(val uses: List<AudioAssetAlarmUse>) : AudioDeletionResult
    data object ActiveUse : AudioDeletionResult
    data class Failed(val message: String) : AudioDeletionResult
}

interface AudioLibraryRepository {
    fun observeManagedAssets(): Flow<List<ManagedAudioAsset>>
    suspend fun delete(assetId: String): AudioDeletionResult
}

/** Coordinates asset deletion with editor drafts and writes of alarm references. */
interface AudioAssetUsageRegistry {
    fun setEditorDraftAsset(assetId: String?)
    fun isInUse(assetId: String): Boolean
    suspend fun <T> withWriteLock(block: suspend () -> T): T
}
