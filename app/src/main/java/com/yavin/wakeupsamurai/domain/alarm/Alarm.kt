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
    val contentMode: AlarmContentMode = AlarmContentMode.TEXT,
    val automaticVoiceTitle: String? = null,
    val scheduleRevision: Long = 0L,
    val scheduledOccurrenceId: String? = null,
    val scheduledAt: Instant? = null,
    val schedulingState: AlarmSchedulingState = AlarmSchedulingState.NOT_SCHEDULED,
    val snoozeOccurrenceId: String? = null,
    val snoozeRevision: Long? = null,
    val snoozedAt: Instant? = null,
    val ringingOccurrenceId: String? = null,
    val ringingRevision: Long? = null,
    val regularScheduledBootId: Long? = null,
    val snoozeScheduledBootId: Long? = null,
    val snoozeElapsedDeadlineMillis: Long? = null,
    val ringingBootId: Long? = null,
    val lastRegularDeliveredAt: Instant? = null,
    val ringingDeadlineElapsedMillis: Long? = null,
    val lastRingingFailure: String? = null,
)

/** The persisted state of the derived Android alarm, not the user's enabled intent. */
enum class AlarmSchedulingState {
    NOT_SCHEDULED,
    SCHEDULED,
    RINGING,
    SNOOZED,
    BLOCKED_EXACT_ALARM_ACCESS,
    BLOCKED_NOTIFICATION_PERMISSION,
    COMPLETED,
    SKIPPED,
    FAILED,
    MISSED,
}

enum class AudioSelectionKind {
    GENERATED_FOR_TEXT,
    SAVED_ASSET,
}

/** The editor mode retained for an alarm draft that has no selected audio yet. */
enum class AlarmContentMode {
    TEXT,
    VOICE,
}

/** Durable provenance of an asset, independent from which alarm currently selects it. */
enum class AudioAssetProvenance {
    GENERATED_TEXT,
    RECORDED_VOICE,
    UNKNOWN,
}

data class AudioAsset(
    val id: String,
    val relativePath: String,
    val sourceText: String?,
    val voice: String?,
    val languageTag: String?,
    val createdAt: Instant?,
    val durationMillis: Long?,
    val provenance: AudioAssetProvenance = AudioAssetProvenance.UNKNOWN,
)

fun Alarm.resolvedTitle(): String = label.ifBlank {
    automaticVoiceTitle ?: messageText.ifBlank { "Untitled alarm" }
}

data class AlarmWithAudio(
    val alarm: Alarm,
    val audioAsset: AudioAsset?,
)
