package com.yavin.wakeupsamurai.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_assets",
    indices = [Index(value = ["relativePath"], unique = true)],
)
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val relativePath: String,
    val sourceText: String?,
    val voice: String?,
    val languageTag: String?,
    val createdAtEpochMillis: Long?,
    val durationMillis: Long?,
    val provenance: String,
)

@Entity(
    tableName = "alarms",
    foreignKeys = [
        ForeignKey(
            entity = AudioAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["audioAssetId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("audioAssetId")],
)
data class AlarmEntity(
    @PrimaryKey val id: String,
    val label: String,
    val messageText: String,
    val hour: Int,
    val minute: Int,
    val repeatDaysMask: Int,
    val configuredEnabled: Boolean,
    val resolvedOneShotEpochMillis: Long?,
    val audioAssetId: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val audioSelectionKind: String?,
    val contentMode: String,
    val automaticVoiceTitle: String?,
    val scheduleRevision: Long,
    val scheduledOccurrenceId: String?,
    val scheduledAtEpochMillis: Long?,
    val schedulingState: String,
    val snoozeOccurrenceId: String?,
    val snoozeRevision: Long?,
    val snoozedAtEpochMillis: Long?,
    val ringingOccurrenceId: String?,
    val ringingRevision: Long?,
    val regularScheduledBootId: Long?,
    val snoozeScheduledBootId: Long?,
    val snoozeElapsedDeadlineMillis: Long?,
    val ringingBootId: Long?,
    val lastRegularDeliveredAtEpochMillis: Long?,
    val ringingDeadlineElapsedMillis: Long?,
    val lastRingingFailure: String?,
)

data class AlarmWithAudioEntity(
    @androidx.room.Embedded val alarm: AlarmEntity,
    @androidx.room.Relation(parentColumn = "audioAssetId", entityColumn = "id")
    val audioAsset: AudioAssetEntity?,
)

@Entity(tableName = "alarm_naming_state")
data class AlarmNamingStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nextVoiceSequence: Long = 1L,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
