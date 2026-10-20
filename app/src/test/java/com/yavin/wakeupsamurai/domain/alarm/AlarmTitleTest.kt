package com.yavin.wakeupsamurai.domain.alarm

import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmTitleTest {
    @Test
    fun customLabelTakesPriorityOverMessageAndAutomaticVoiceTitle() {
        assertEquals("Morning run", alarm(label = "Morning run", message = "Run by the river", autoTitle = "Record voice 3").resolvedTitle())
    }

    @Test
    fun messageIsTextAlarmFallbackTitle() {
        assertEquals("Pancakes before work", alarm(message = "Pancakes before work").resolvedTitle())
    }

    @Test
    fun automaticVoiceTitleIsUsedWhenLabelIsCleared() {
        assertEquals("Record voice 8", alarm(message = "", autoTitle = "Record voice 8").resolvedTitle())
    }

    private fun alarm(label: String = "", message: String, autoTitle: String? = null) = Alarm(
        id = "alarm",
        label = label,
        messageText = message,
        time = LocalTime.of(7, 0),
        repeatDays = emptySet(),
        configuredEnabled = false,
        resolvedOneShotAt = null,
        audioAssetId = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        automaticVoiceTitle = autoTitle,
    )
}
