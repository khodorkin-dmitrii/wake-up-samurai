package com.yavin.wakeupsamurai.ui.alarmeditor

data class AlarmEditorUiState(
    val messageDraft: String = "",
    val draftRevision: Long = 0L,
    val generationStatus: GenerationStatus = GenerationStatus.Idle,
    val generatedAudio: GeneratedAudioUi? = null,
    val isPreviewPlaying: Boolean = false,
    val previewErrorMessage: String? = null,
    val showSuggestionReplacementConfirmation: Boolean = false,
) {
    val canGenerate: Boolean
        get() = messageDraft.isNotBlank() && generationStatus !is GenerationStatus.Generating

    val isGeneratedAudioOutdated: Boolean
        get() = generatedAudio?.sourceRevision != null && generatedAudio.sourceRevision != draftRevision
}

sealed interface GenerationStatus {
    data object Idle : GenerationStatus
    data class Generating(val sourceRevision: Long) : GenerationStatus
    data class Ready(val sourceRevision: Long) : GenerationStatus
    data class Error(val message: String, val sourceRevision: Long) : GenerationStatus
}

data class GeneratedAudioUi(
    val sourceRevision: Long,
    val durationMillis: Long,
    val generationLatencyMillis: Long,
)
