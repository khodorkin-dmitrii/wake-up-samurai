package com.yavin.wakeupsamurai.ui.alarmeditor

import java.time.DayOfWeek
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState

data class AlarmEditorUiState(
    val isLoading: Boolean = false,
    val alarmId: String? = null,
    val label: String = "",
    val messageDraft: String = "",
    val draftRevision: Long = 0L,
    val generationStatus: GenerationStatus = GenerationStatus.Idle,
    val generatedAudio: GeneratedAudioUi? = null,
    val isPreviewPlaying: Boolean = false,
    val previewErrorMessage: String? = null,
    val showSuggestionReplacementConfirmation: Boolean = false,
    val hour: Int = 7,
    val minute: Int = 0,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val nextOccurrenceSummary: String = "",
    val configuredEnabled: Boolean = false,
    val resolvedOneShotEpochMillis: Long? = null,
    val isScheduleDirty: Boolean = false,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val showDiscardConfirmation: Boolean = false,
    val showDeleteConfirmation: Boolean = false,
    val validationMessage: String? = null,
    val hasPersistedAlarm: Boolean = false,
    val audioSourceText: String? = null,
    val recordingState: RecordingState = RecordingState.Idle,
    val schedulingState: AlarmSchedulingState = AlarmSchedulingState.NOT_SCHEDULED,
    val hasPendingRegularOccurrence: Boolean = false,
    val schedulingAccessRequest: SchedulingAccessRequest? = null,
) {
    val canGenerate: Boolean
        get() = messageDraft.isNotBlank() && generationStatus !is GenerationStatus.Generating

    val isGeneratedAudioOutdated: Boolean
        get() = generatedAudio?.isTextSynchronized == true && audioSourceText != messageDraft.trim()

    val canRestoreAudioText: Boolean
        get() = isGeneratedAudioOutdated && audioSourceText != null
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data object AwaitingPermission : RecordingState
    data object PermissionDenied : RecordingState
    data object PermissionPermanentlyDenied : RecordingState
    data class Recording(val elapsedMillis: Long = 0L) : RecordingState
    data class Reviewing(val durationMillis: Long) : RecordingState
    data class Error(val message: String) : RecordingState
}

sealed interface GenerationStatus {
    data object Idle : GenerationStatus
    data class Generating(val sourceRevision: Long) : GenerationStatus
    data class Ready(val sourceRevision: Long) : GenerationStatus
    data class Error(val message: String, val sourceRevision: Long) : GenerationStatus
}

data class GeneratedAudioUi(
    val assetId: String,
    val sourceRevision: Long,
    val durationMillis: Long,
    val generationLatencyMillis: Long,
    val isTextSynchronized: Boolean = true,
)

enum class SchedulingAccessRequest {
    EXACT_ALARM,
    NOTIFICATIONS,
    APP_NOTIFICATIONS,
    ALARM_CHANNEL,
}

data class SavedAudioPickerUiState(
    val assets: List<SavedAudioItemUi> = emptyList(),
    val isLoading: Boolean = true,
    val previewingAssetId: String? = null,
    val previewErrorMessage: String? = null,
)

data class SavedAudioItemUi(
    val id: String,
    val title: String,
    val durationMillis: Long?,
    val isAvailable: Boolean,
    val unavailableReason: String? = null,
)
