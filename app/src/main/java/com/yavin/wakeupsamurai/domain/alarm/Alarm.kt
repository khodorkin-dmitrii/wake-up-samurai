package com.yavin.wakeupsamurai.domain.alarm

import java.time.Instant
import java.time.LocalTime

data class Alarm(
    val id: String,
    val label: String,
    val messageText: String,
    val time: LocalTime,
    val repeatDays: Set<java.time.DayOfWeek>,
    val configuredEnabled: Boolean,
    val resolvedOneShotAt: Instant?,
    val audioAssetId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val audioSelectionKind: AudioSelectionKind? = null,
    val scheduleRevision: Long = 0L,
    val scheduledOccurrenceId: String? = null,
    val scheduledAt: Instant? = null,
    val schedulingState: AlarmSchedulingState = AlarmSchedulingState.NOT_SCHEDULED,
)

/** The persisted state of the derived Android alarm, not the user's enabled intent. */
enum class AlarmSchedulingState {
    NOT_SCHEDULED,
    SCHEDULED,
    RINGING,
    SNOOZED,
    BLOCKED_EXACT_ALARM_ACCESS,
    BLOCKED_NOTIFICATION_PERMISSION,
    UNSUPPORTED_RECURRING,
    COMPLETED,
    SKIPPED,
    FAILED,
}

enum class AudioSelectionKind {
    GENERATED_FOR_TEXT,
    SAVED_ASSET,
}

data class AudioAsset(
    val id: String,
    val relativePath: String,
    val sourceText: String?,
    val voice: String?,
    val languageTag: String?,
    val createdAt: Instant?,
    val durationMillis: Long?,
)

data class AlarmWithAudio(
    val alarm: Alarm,
    val audioAsset: AudioAsset?,
)
