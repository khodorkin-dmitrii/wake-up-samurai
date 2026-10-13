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
)

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
