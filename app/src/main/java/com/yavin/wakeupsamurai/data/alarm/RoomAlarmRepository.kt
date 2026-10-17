package com.yavin.wakeupsamurai.data.alarm

import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.data.local.AlarmDao
import com.yavin.wakeupsamurai.data.local.AlarmEntity
import com.yavin.wakeupsamurai.data.local.AlarmWithAudioEntity
import com.yavin.wakeupsamurai.data.local.AudioAssetDao
import com.yavin.wakeupsamurai.data.local.AudioAssetEntity
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AlarmWithAudio
import com.yavin.wakeupsamurai.domain.alarm.AudioSelectionKind
import com.yavin.wakeupsamurai.domain.alarm.AudioAsset
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class RoomAlarmRepository @Inject constructor(
    private val alarmDao: AlarmDao,
    private val usageRegistry: AudioAssetUsageRegistry,
) : AlarmRepository {
    override fun observeAll(): Flow<List<AlarmWithAudio>> = alarmDao.observeAll().map { rows ->
        rows.map(AlarmWithAudioEntity::toDomain)
    }

    override suspend fun get(id: String): AlarmWithAudio? = alarmDao.get(id)?.toDomain()
    override suspend fun save(alarm: Alarm) = usageRegistry.withWriteLock {
        alarmDao.upsert(alarm.toEntity())
    }
    override suspend fun delete(id: String) = alarmDao.delete(id)

    override suspend fun claimScheduledOccurrence(alarmId: String, occurrenceId: String, revision: Long): Boolean =
        alarmDao.claimScheduledOccurrence(alarmId, occurrenceId, revision) == 1

    override suspend fun finishOccurrence(
        alarmId: String,
        occurrenceId: String,
        revision: Long,
        finalState: AlarmSchedulingState,
    ): Boolean = alarmDao.finishRingingOccurrence(
        alarmId = alarmId,
        occurrenceId = occurrenceId,
        revision = revision,
        finalState = finalState.name,
    ) == 1

    override suspend fun replaceRingingWithSnooze(
        alarmId: String,
        ringingOccurrenceId: String,
        ringingRevision: Long,
        snoozeOccurrenceId: String,
        snoozeRevision: Long,
        snoozedAtEpochMillis: Long,
    ): Boolean = usageRegistry.withWriteLock {
        alarmDao.replaceRingingWithSnooze(
            alarmId, ringingOccurrenceId, ringingRevision, snoozeOccurrenceId, snoozeRevision, snoozedAtEpochMillis,
        ) == 1
    }
}

@Singleton
class RoomAudioAssetRepository @Inject constructor(
    private val audioAssetDao: AudioAssetDao,
    private val storage: AudioAssetFileStore,
) : AudioAssetRepository {
    override fun observeAll(): Flow<List<AudioAsset>> = audioAssetDao.observeAll().map { rows ->
        rows.map(AudioAssetEntity::toDomain)
    }

    override suspend fun get(id: String): AudioAsset? = audioAssetDao.get(id)?.toDomain()
    override suspend fun register(asset: AudioAsset): AudioAsset {
        val existing = audioAssetDao.getByPath(asset.relativePath)
        val registered = if (existing == null) asset else asset.copy(id = existing.id)
        audioAssetDao.upsert(registered.toEntity())
        return registered
    }

    override suspend fun importExistingCompletedFiles() {
        storage.completedFiles().forEach { file ->
            val relativePath = storage.relativePath(file)
            if (audioAssetDao.getByPath(relativePath) != null) return@forEach
            val id = UUID.nameUUIDFromBytes(relativePath.toByteArray(StandardCharsets.UTF_8)).toString()
            audioAssetDao.upsert(
                AudioAssetEntity(
                    id = id,
                    relativePath = relativePath,
                    sourceText = null,
                    voice = null,
                    languageTag = null,
                    createdAtEpochMillis = null,
                    durationMillis = storage.validatedDuration(file),
                ),
            )
        }
    }
}

private fun Alarm.toEntity() = AlarmEntity(
    id = id,
    label = label,
    messageText = messageText,
    hour = time.hour,
    minute = time.minute,
    repeatDaysMask = repeatDays.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
    configuredEnabled = configuredEnabled,
    resolvedOneShotEpochMillis = resolvedOneShotAt?.toEpochMilli(),
    audioAssetId = audioAssetId,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
    audioSelectionKind = audioSelectionKind?.name,
    scheduleRevision = scheduleRevision,
    scheduledOccurrenceId = scheduledOccurrenceId,
    scheduledAtEpochMillis = scheduledAt?.toEpochMilli(),
    schedulingState = schedulingState.name,
)

private fun AlarmWithAudioEntity.toDomain() = AlarmWithAudio(
    alarm = Alarm(
        id = alarm.id,
        label = alarm.label,
        messageText = alarm.messageText,
        time = LocalTime.of(alarm.hour, alarm.minute),
        repeatDays = DayOfWeek.entries.filterTo(mutableSetOf()) {
            alarm.repeatDaysMask and (1 shl (it.value - 1)) != 0
        },
        configuredEnabled = alarm.configuredEnabled,
        resolvedOneShotAt = alarm.resolvedOneShotEpochMillis?.let(Instant::ofEpochMilli),
        audioAssetId = alarm.audioAssetId,
        createdAt = Instant.ofEpochMilli(alarm.createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(alarm.updatedAtEpochMillis),
        audioSelectionKind = alarm.audioSelectionKind?.let(AudioSelectionKind::valueOf),
        scheduleRevision = alarm.scheduleRevision,
        scheduledOccurrenceId = alarm.scheduledOccurrenceId,
        scheduledAt = alarm.scheduledAtEpochMillis?.let(Instant::ofEpochMilli),
        schedulingState = runCatching { AlarmSchedulingState.valueOf(alarm.schedulingState) }
            .getOrDefault(AlarmSchedulingState.NOT_SCHEDULED),
    ),
    audioAsset = audioAsset?.toDomain(),
)

private fun AudioAssetEntity.toDomain() = AudioAsset(
    id = id,
    relativePath = relativePath,
    sourceText = sourceText,
    voice = voice,
    languageTag = languageTag,
    createdAt = createdAtEpochMillis?.let(Instant::ofEpochMilli),
    durationMillis = durationMillis,
)

private fun AudioAsset.toEntity() = AudioAssetEntity(
    id = id,
    relativePath = relativePath,
    sourceText = sourceText,
    voice = voice,
    languageTag = languageTag,
    createdAtEpochMillis = createdAt?.toEpochMilli(),
    durationMillis = durationMillis,
)
