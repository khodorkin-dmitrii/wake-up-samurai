package com.yavin.wakeupsamurai.ui.alarmeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.tts.GeneratedSpeech
import com.yavin.wakeupsamurai.tts.SpeechGeneration
import com.yavin.wakeupsamurai.tts.SpeechGenerationRequest
import com.yavin.wakeupsamurai.tts.SpikeVoices
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    private val speechGeneration: SpeechGeneration,
    private val audioPreview: AudioPreview,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlarmEditorUiState())
    val uiState: StateFlow<AlarmEditorUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var activeRequestId = 0L
    private var generatedSpeech: GeneratedSpeech? = null

    init {
        viewModelScope.launch {
            audioPreview.state.collect { previewState ->
                _uiState.update {
                    it.copy(
                        isPreviewPlaying = previewState.isPlaying,
                        previewErrorMessage = previewState.errorMessage,
                    )
                }
            }
        }
    }

    fun onMessageDraftChanged(message: String) {
        val current = _uiState.value
        if (message == current.messageDraft) return
        if (current.generationStatus is GenerationStatus.Generating) cancelGeneration()
        audioPreview.stop()
        _uiState.update {
            it.copy(
                messageDraft = message,
                draftRevision = it.draftRevision + 1,
                generationStatus = GenerationStatus.Idle,
                previewErrorMessage = null,
            )
        }
    }

    fun onGenerateAudio() {
        val state = _uiState.value
        if (!state.canGenerate || generationJob?.isActive == true) return

        val requestId = ++activeRequestId
        val request = SpeechGenerationRequest(
            text = state.messageDraft,
            voice = SpikeVoices.forText(state.messageDraft),
            revision = state.draftRevision,
        )
        _uiState.update {
            it.copy(
                generationStatus = GenerationStatus.Generating(request.revision),
                previewErrorMessage = null,
            )
        }

        generationJob = viewModelScope.launch {
            try {
                val result = withTimeout(GENERATION_TIMEOUT_MILLIS) {
                    speechGeneration.generate(request)
                }
                val remainsCurrent =
                    requestId == activeRequestId && _uiState.value.draftRevision == request.revision
                if (!remainsCurrent) {
                    // It is a valid completed asset even though it no longer belongs in this preview.
                    return@launch
                }

                audioPreview.stop()
                generatedSpeech = result
                _uiState.update {
                    it.copy(
                        generationStatus = GenerationStatus.Ready(result.sourceRevision),
                        generatedAudio = GeneratedAudioUi(
                            sourceRevision = result.sourceRevision,
                            durationMillis = result.durationMillis,
                            generationLatencyMillis = result.generationLatencyMillis,
                        ),
                    )
                }
            } catch (_: TimeoutCancellationException) {
                setErrorIfCurrent(requestId, request.revision, "The request timed out. Try again.")
            } catch (_: CancellationException) {
                // Explicit cancellation is transitioned to idle by cancelGeneration().
            } catch (exception: Exception) {
                setErrorIfCurrent(
                    requestId,
                    request.revision,
                    exception.message ?: "Unknown generation error.",
                )
            } finally {
                if (requestId == activeRequestId) generationJob = null
            }
        }
    }

    fun onCancelGeneration() {
        cancelGeneration()
    }

    fun onRetryGeneration() {
        if (_uiState.value.generationStatus is GenerationStatus.Error) onGenerateAudio()
    }

    fun onTogglePreview() {
        val speech = generatedSpeech ?: return
        if (_uiState.value.isPreviewPlaying) audioPreview.pause() else audioPreview.play(speech.file)
    }

    fun onTryPhrase() {
        if (_uiState.value.messageDraft.isBlank()) applySuggestion()
        else _uiState.update { it.copy(showSuggestionReplacementConfirmation = true) }
    }

    fun onConfirmSuggestionReplacement() {
        _uiState.update { it.copy(showSuggestionReplacementConfirmation = false) }
        applySuggestion()
    }

    fun onDismissSuggestionReplacement() {
        _uiState.update { it.copy(showSuggestionReplacementConfirmation = false) }
    }

    fun onLeaveEditor() {
        cancelGeneration()
        audioPreview.release()
    }

    override fun onCleared() {
        generationJob?.cancel()
        audioPreview.release()
    }

    private fun applySuggestion() {
        onMessageDraftChanged(SUGGESTED_PHRASE)
    }

    private fun cancelGeneration() {
        if (generationJob?.isActive != true) return
        activeRequestId++
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(generationStatus = GenerationStatus.Idle) }
    }

    private fun setErrorIfCurrent(requestId: Long, revision: Long, message: String) {
        if (requestId != activeRequestId || _uiState.value.draftRevision != revision) return
        _uiState.update { it.copy(generationStatus = GenerationStatus.Error(message, revision)) }
    }

    companion object {
        const val SUGGESTED_PHRASE =
            "Wake up (wake up)\nGrab a brush and put a little make-up"
        const val GENERATION_TIMEOUT_MILLIS = 45_000L
    }
}
