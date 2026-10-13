package com.yavin.wakeupsamurai.ui.alarmeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.NextOccurrenceCalculator
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import com.yavin.wakeupsamurai.tts.GeneratedSpeech
import com.yavin.wakeupsamurai.tts.SpeechGeneration
import com.yavin.wakeupsamurai.tts.SpeechGenerationRequest
import com.yavin.wakeupsamurai.tts.SpikeVoices
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    private val speechGeneration: SpeechGeneration,
    private val audioPreview: AudioPreview,
    private val alarmRepository: AlarmRepository,
    private val storage: AudioAssetFileStore,
    private val calculator: NextOccurrenceCalculator,
    private val timeProvider: TimeProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlarmEditorUiState())
    val uiState: StateFlow<AlarmEditorUiState> = _uiState.asStateFlow()
    private val _closeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeEvents: SharedFlow<Unit> = _closeEvents.asSharedFlow()

    private var generationJob: Job? = null
    private var saveJob: Job? = null
    private var activeRequestId = 0L
    private var generatedSpeech: GeneratedSpeech? = null
    private var loadedAlarm: Alarm? = null
    private var activeSessionId: String? = null
    private var sessionActive = false

    init {
        viewModelScope.launch {
            audioPreview.state.collect { previewState ->
                _uiState.update {
                    it.copy(isPreviewPlaying = previewState.isPlaying, previewErrorMessage = previewState.errorMessage)
                }
            }
        }
    }

    fun startSession(alarmId: String?) {
        if (sessionActive && activeSessionId == alarmId) return
        sessionActive = true
        activeSessionId = alarmId
        generationJob?.cancel()
        audioPreview.stop()
        _uiState.value = AlarmEditorUiState(isLoading = alarmId != null)
        loadedAlarm = null
        generatedSpeech = null
        if (alarmId == null) {
            refreshNextOccurrence()
            return
        }
        viewModelScope.launch {
            val row = alarmRepository.get(alarmId)
            if (!sessionActive || activeSessionId != alarmId) return@launch
            if (row == null) {
                _uiState.value = AlarmEditorUiState(validationMessage = "Alarm not found.")
                return@launch
            }
            loadedAlarm = row.alarm
            val asset = row.audioAsset
            generatedSpeech = asset?.let { stored ->
                storage.resolve(stored.relativePath)?.takeIf { it.isFile }?.let { file ->
                    GeneratedSpeech(
                        assetId = stored.id,
                        file = file,
                        durationMillis = stored.durationMillis ?: 0L,
                        sourceRevision = 0L,
                        voice = stored.voice.orEmpty(),
                        generationLatencyMillis = 0L,
                    )
                }
            }
            _uiState.value = AlarmEditorUiState(
                alarmId = row.alarm.id,
                label = row.alarm.label,
                messageDraft = row.alarm.messageText,
                generatedAudio = asset?.let {
                    GeneratedAudioUi(it.id, 0L, it.durationMillis ?: 0L, 0L)
                },
                generationStatus = if (asset != null) GenerationStatus.Ready(0L) else GenerationStatus.Idle,
                audioSourceText = asset?.sourceText,
                hour = row.alarm.time.hour,
                minute = row.alarm.time.minute,
                repeatDays = row.alarm.repeatDays,
                configuredEnabled = row.alarm.configuredEnabled,
                resolvedOneShotEpochMillis = row.alarm.resolvedOneShotAt?.toEpochMilli(),
                hasPersistedAlarm = true,
            )
            refreshNextOccurrence()
        }
    }

    fun onLabelChanged(value: String) = edit { copy(label = value) }

    fun onMessageDraftChanged(message: String) {
        if (message == _uiState.value.messageDraft) return
        if (_uiState.value.generationStatus is GenerationStatus.Generating) cancelGeneration()
        audioPreview.stop()
        edit {
            copy(
                messageDraft = message,
                draftRevision = draftRevision + 1,
                generationStatus = GenerationStatus.Idle,
                previewErrorMessage = null,
            )
        }
    }

    fun onTimeChanged(hour: Int, minute: Int) {
        edit { copy(hour = hour, minute = minute, isScheduleDirty = true) }
        refreshNextOccurrence()
    }

    fun onRepeatDayToggled(day: DayOfWeek) {
        edit {
            copy(
                repeatDays = if (day in repeatDays) repeatDays - day else repeatDays + day,
                isScheduleDirty = true,
            )
        }
        refreshNextOccurrence()
    }

    fun onConfiguredEnabledChanged(enabled: Boolean) = edit { copy(configuredEnabled = enabled) }

    fun refreshNextOccurrence() {
        val state = _uiState.value
        val now = timeProvider.now()
        val next = if (state.repeatDays.isEmpty()) {
            state.resolvedOneShotEpochMillis
                ?.takeUnless { state.isScheduleDirty }
                ?.let(Instant::ofEpochMilli)
                ?: calculator.oneShotStrictlyAfter(now, timeProvider.zoneId(), LocalTime.of(state.hour, state.minute))
        } else {
            calculator.recurringStrictlyAfter(
                now, timeProvider.zoneId(), LocalTime.of(state.hour, state.minute), state.repeatDays,
            )
        }
        _uiState.update { it.copy(nextOccurrenceSummary = next?.let(::formatOccurrence).orEmpty()) }
    }

    fun onGenerateAudio() {
        val state = _uiState.value
        if (!state.canGenerate || generationJob?.isActive == true) return
        val requestId = ++activeRequestId
        val request = SpeechGenerationRequest(state.messageDraft, SpikeVoices.forText(state.messageDraft), state.draftRevision)
        _uiState.update { it.copy(generationStatus = GenerationStatus.Generating(request.revision), previewErrorMessage = null) }
        generationJob = viewModelScope.launch {
            try {
                val result = withTimeout(GENERATION_TIMEOUT_MILLIS) { speechGeneration.generate(request) }
                if (requestId != activeRequestId || _uiState.value.draftRevision != request.revision) return@launch
                audioPreview.stop()
                generatedSpeech = result
                _uiState.update {
                    it.copy(
                        generationStatus = GenerationStatus.Ready(result.sourceRevision),
                        generatedAudio = GeneratedAudioUi(
                            result.assetId, result.sourceRevision, result.durationMillis, result.generationLatencyMillis,
                        ),
                        audioSourceText = request.text.trim(),
                        isDirty = true,
                    )
                }
            } catch (_: TimeoutCancellationException) {
                setErrorIfCurrent(requestId, request.revision, "The request timed out. Try again.")
            } catch (_: CancellationException) {
                // Explicit cancellation transitions to idle in cancelGeneration().
            } catch (exception: Exception) {
                setErrorIfCurrent(requestId, request.revision, exception.message ?: "Unknown generation error.")
            } finally {
                if (requestId == activeRequestId) generationJob = null
            }
        }
    }

    fun onCancelGeneration() = cancelGeneration()
    fun onRetryGeneration() { if (_uiState.value.generationStatus is GenerationStatus.Error) onGenerateAudio() }
    fun onTogglePreview() {
        val speech = generatedSpeech ?: return
        if (_uiState.value.isPreviewPlaying) audioPreview.pause() else audioPreview.play(speech.file)
    }

    fun onRestoreAudioText() {
        val source = _uiState.value.audioSourceText ?: return
        onMessageDraftChanged(source)
    }

    fun onTryPhrase() {
        if (_uiState.value.messageDraft.isBlank()) applySuggestion()
        else _uiState.update { it.copy(showSuggestionReplacementConfirmation = true) }
    }
    fun onConfirmSuggestionReplacement() {
        _uiState.update { it.copy(showSuggestionReplacementConfirmation = false) }
        applySuggestion()
    }
    fun onDismissSuggestionReplacement() = _uiState.update { it.copy(showSuggestionReplacementConfirmation = false) }

    fun onSave() {
        if (saveJob?.isActive == true || _uiState.value.isSaving) return
        val state = _uiState.value
        val audio = generatedSpeech
        if (state.configuredEnabled) {
            val error = when {
                audio == null -> "Generate audio before enabling this alarm."
                !audio.file.isFile || !audio.file.canRead() -> "The selected audio file is unavailable."
                state.audioSourceText != state.messageDraft.trim() -> "Regenerate audio or restore its original text before enabling."
                else -> null
            }
            if (error != null) {
                _uiState.update { it.copy(validationMessage = error) }
                return
            }
        }
        _uiState.update { it.copy(isSaving = true, validationMessage = null) }
        saveJob = viewModelScope.launch {
            try {
                val now = timeProvider.now()
                val existing = loadedAlarm
                val id = existing?.id ?: state.alarmId ?: UUID.randomUUID().toString()
                val time = LocalTime.of(state.hour, state.minute)
                val alarm = Alarm(
                    id = id,
                    label = state.label.trim(),
                    messageText = state.messageDraft,
                    time = time,
                    repeatDays = state.repeatDays,
                    configuredEnabled = state.configuredEnabled,
                    resolvedOneShotAt = if (state.repeatDays.isEmpty()) {
                        calculator.oneShotStrictlyAfter(now, timeProvider.zoneId(), time)
                    } else null,
                    audioAssetId = audio?.assetId,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                alarmRepository.save(alarm)
                loadedAlarm = alarm
                _uiState.update {
                    it.copy(
                        alarmId = id,
                        isSaving = false,
                        isDirty = false,
                        hasPersistedAlarm = true,
                        resolvedOneShotEpochMillis = alarm.resolvedOneShotAt?.toEpochMilli(),
                        isScheduleDirty = false,
                    )
                }
                closeEditor()
            } catch (exception: Exception) {
                _uiState.update { it.copy(isSaving = false, validationMessage = exception.message ?: "Could not save alarm.") }
            }
        }
    }

    fun onBackRequested() {
        if (_uiState.value.isDirty) _uiState.update { it.copy(showDiscardConfirmation = true) }
        else closeEditor()
    }
    fun onDismissDiscard() = _uiState.update { it.copy(showDiscardConfirmation = false) }
    fun onConfirmDiscard() {
        _uiState.update { it.copy(showDiscardConfirmation = false) }
        closeEditor()
    }
    fun onDeleteRequested() = _uiState.update { it.copy(showDeleteConfirmation = true) }
    fun onDismissDelete() = _uiState.update { it.copy(showDeleteConfirmation = false) }
    fun onConfirmDelete() {
        val id = _uiState.value.alarmId ?: return
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            alarmRepository.delete(id)
            closeEditor()
        }
    }

    fun onLeaveEditor() {
        audioPreview.release()
    }

    override fun onCleared() {
        generationJob?.cancel()
        audioPreview.release()
    }

    private fun edit(transform: AlarmEditorUiState.() -> AlarmEditorUiState) {
        _uiState.update { it.transform().copy(isDirty = true, validationMessage = null) }
    }
    private fun applySuggestion() = onMessageDraftChanged(SUGGESTED_PHRASE)
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
    private fun closeEditor() {
        if (!sessionActive) return
        sessionActive = false
        cancelGeneration()
        audioPreview.release()
        _closeEvents.tryEmit(Unit)
    }
    private fun formatOccurrence(instant: Instant): String {
        val value = instant.atZone(timeProvider.zoneId())
        return value.format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' HH:mm"))
    }

    companion object {
        const val SUGGESTED_PHRASE = "Wake up (wake up)\nGrab a brush and put a little make-up"
        const val GENERATION_TIMEOUT_MILLIS = 45_000L
    }
}
