package com.yavin.wakeupsamurai.data.audio

import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.data.local.AlarmDao
import com.yavin.wakeupsamurai.data.local.AlarmEntity
import com.yavin.wakeupsamurai.data.local.AudioAssetDao
import com.yavin.wakeupsamurai.data.local.AudioAssetEntity
import com.yavin.wakeupsamurai.domain.alarm.AudioAsset
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetAlarmUse
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import com.yavin.wakeupsamurai.domain.alarm.AudioDeletionResult
import com.yavin.wakeupsamurai.domain.alarm.AudioLibraryRepository
import com.yavin.wakeupsamurai.domain.alarm.ManagedAudioAsset
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@Singleton
class RoomAudioLibraryRepository @Inject constructor(
    private val assetDao: AudioAssetDao,
    private val alarmDao: AlarmDao,
    private val storage: AudioAssetFileStore,
    private val preview: AudioPreview,
    private val usageRegistry: AudioAssetUsageRegistry,
) : AudioLibraryRepository {
    override fun observeManagedAssets(): Flow<List<ManagedAudioAsset>> = combine(
        assetDao.observeAll(),
        alarmDao.observeEntities(),
    ) { assets, alarms ->
        assets.map { entity ->
            val file = storage.resolve(entity.relativePath)
            ManagedAudioAsset(
                asset = entity.toDomain(),
                alarmUses = alarms.filter { it.audioAssetId == entity.id }.map(AlarmEntity::toUse),
                fileSizeBytes = file?.takeIf { it.isFile }?.length(),
                isAvailable = storage.isReadable(entity.relativePath),
            )
        }
    }

    override suspend fun delete(assetId: String): AudioDeletionResult = withContext(Dispatchers.IO) {
        usageRegistry.withWriteLock {
            val asset = assetDao.get(assetId) ?: return@withWriteLock AudioDeletionResult.Failed("This saved audio no longer exists.")
            val uses = alarmDao.referencingAssets(assetId).map(AlarmEntity::toUse)
            if (uses.isNotEmpty()) return@withWriteLock AudioDeletionResult.Referenced(uses)
            if (usageRegistry.isInUse(assetId)) return@withWriteLock AudioDeletionResult.ActiveUse

            val file = storage.resolve(asset.relativePath)
                ?: return@withWriteLock AudioDeletionResult.Failed("The saved audio path is invalid.")
            // Media3 binds the player to the main application thread. File deletion remains on IO.
            withContext(Dispatchers.Main.immediate) { preview.stop() }
            val missing = !file.exists()
            if (!missing && !file.delete()) {
                return@withWriteLock AudioDeletionResult.Failed("Could not delete the audio file. Try again.")
            }
            return@withWriteLock try {
                assetDao.delete(assetId)
                AudioDeletionResult.Deleted(assetId, fileWasMissing = missing)
            } catch (exception: Exception) {
                AudioDeletionResult.Failed("The file was removed, but its library entry could not be updated. Try again.")
            }
        }
    }
}

private fun AudioAssetEntity.toDomain() = AudioAsset(
    id = id,
    relativePath = relativePath,
    sourceText = sourceText,
    voice = voice,
    languageTag = languageTag,
    createdAt = createdAtEpochMillis?.let(Instant::ofEpochMilli),
    durationMillis = durationMillis,
)

private fun AlarmEntity.toUse(): AudioAssetAlarmUse = AudioAssetAlarmUse(
    alarmId = id,
    label = label.ifBlank { "Alarm at %02d:%02d".format(hour, minute) },
)
