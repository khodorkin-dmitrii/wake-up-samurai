package com.yavin.wakeupsamurai.ui.alarmeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.alarm.AlarmSaveResult
import com.yavin.wakeupsamurai.alarm.OneShotAlarmCoordinator
import com.yavin.wakeupsamurai.audio.VoiceRecorder
import com.yavin.wakeupsamurai.audio.RecordingValidator
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import com.yavin.wakeupsamurai.domain.alarm.AudioSelectionKind
import com.yavin.wakeupsamurai.domain.alarm.AlarmContentMode
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetProvenance
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
import java.io.File
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

@HiltViewModel
class AlarmEditorViewModel @Inject constructor(
    private val speechGeneration: SpeechGeneration,
    private val audioPreview: AudioPreview,
    private val voiceRecorder: VoiceRecorder,
    private val recordingValidator: RecordingValidator,
    private val alarmRepository: AlarmRepository,
    private val audioAssetRepository: AudioAssetRepository,
    private val assetUsageRegistry: AudioAssetUsageRegistry,
    private val storage: AudioAssetFileStore,
    private val calculator: NextOccurrenceCalculator,
    private val timeProvider: TimeProvider,
    private val alarmCoordinator: OneShotAlarmCoordinator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlarmEditorUiState())
    val uiState: StateFlow<AlarmEditorUiState> = _uiState.asStateFlow()
    private val _closeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeEvents: SharedFlow<Unit> = _closeEvents.asSharedFlow()
    private val _savedAudioPickerUiState = MutableStateFlow(SavedAudioPickerUiState())
    val savedAudioPickerUiState: StateFlow<SavedAudioPickerUiState> = _savedAudioPickerUiState.asStateFlow()

    private var generationJob: Job? = null
    private var saveJob: Job? = null
    private var recordingValidationJob: Job? = null
    private var activeRequestId = 0L
    private var activeRecordingId = 0L
    private var recordingTickerJob: Job? = null
    private var recordingFile: File? = null
    private var recordingStartedAtMillis: Long = 0L
    private var generatedSpeech: GeneratedSpeech? = null
    private var loadedAlarm: Alarm? = null
    private var activeSessionId: String? = null
    private var sessionActive = false
    private var availableAudioAssets = emptyMap<String, com.yavin.wakeupsamurai.domain.alarm.AudioAsset>()

    init {
        viewModelScope.launch {
            audioPreview.state.collect { previewState ->
                _uiState.update {
                    it.copy(isPreviewPlaying = previewState.isPlaying, previewErrorMessage = previewState.errorMessage)
                }
                _savedAudioPickerUiState.update {
                    it.copy(
                        previewErrorMessage = previewState.errorMessage,
                        previewingAssetId = if (previewState.isPlaying) it.previewingAssetId else null,
                    )
                }
            }
        }
        viewModelScope.launch {
            audioAssetRepository.observeAll().collect { assets ->
                availableAudioAssets = assets.associateBy { it.id }
                _savedAudioPickerUiState.value = SavedAudioPickerUiState(
                    assets = assets.map { asset ->
                        val available = storage.isReadable(asset.relativePath)
                        SavedAudioItemUi(
                            id = asset.id,
                            title = asset.sourceText?.takeIf { it.isNotBlank() }
                                ?: if (asset.relativePath.substringAfterLast('/').startsWith("voice-")) {
                                    "Voice recording"
                                } else {
                                    "Saved audio ${asset.id.take(8)}"
                                },
                            durationMillis = asset.durationMillis,
                            isAvailable = available,
                            unavailableReason = if (available) null else "Audio file unavailable",
                        )
                    },
                    isLoading = false,
                )
            }
        }
    }

    fun startSession(alarmId: String?) {
        if (sessionActive && activeSessionId == alarmId) return
        sessionActive = true
        activeSessionId = alarmId
        assetUsageRegistry.setEditorDraftAsset(null)
        generationJob?.cancel()
        audioPreview.stop()
        _uiState.value = AlarmEditorUiState(isLoading = alarmId != null, alarmId = alarmId)
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
            assetUsageRegistry.setEditorDraftAsset(asset?.id)
            _uiState.value = AlarmEditorUiState(
                alarmId = row.alarm.id,
                label = row.alarm.label,
                messageDraft = row.alarm.messageText,
                generatedAudio = asset?.let {
                    GeneratedAudioUi(
                        assetId = it.id,
                        sourceRevision = 0L,
                        durationMillis = it.durationMillis ?: 0L,
                        generationLatencyMillis = 0L,
                        isTextSynchronized = row.alarm.audioSelectionKind != AudioSelectionKind.SAVED_ASSET,
                    )
                },
                generationStatus = if (asset != null) GenerationStatus.Ready(0L) else GenerationStatus.Idle,
                audioSourceText = asset?.sourceText,
                selectedAudioProvenance = asset?.provenance,
                audioCreationMode = asset?.provenance.toEditorModeOrNull()
                    ?: row.alarm.contentMode.toEditorMode(),
                hour = row.alarm.time.hour,
                minute = row.alarm.time.minute,
                repeatDays = row.alarm.repeatDays,
                configuredEnabled = row.alarm.configuredEnabled,
                resolvedOneShotEpochMillis = row.alarm.resolvedOneShotAt?.toEpochMilli(),
                hasPersistedAlarm = true,
                schedulingState = row.alarm.schedulingState,
                hasPendingRegularOccurrence = row.alarm.scheduledOccurrenceId != null,
            )
            refreshNextOccurrence()
        }
    }

    fun onLabelChanged(value: String) = edit { copy(label = value) }

    fun onAudioCreationModeChanged(mode: AudioCreationMode) {
        val state = _uiState.value
        if (state.audioCreationMode == mode) return
        if (state.generationStatus is GenerationStatus.Generating ||
            state.recordingState is RecordingState.Recording ||
            state.recordingState is RecordingState.AwaitingPermission ||
            state.recordingState is RecordingState.Reviewing ||
            state.recordingState is RecordingState.Error
        ) {
            _uiState.update {
                it.copy(pendingAudioCreationMode = mode, showModeSwitchConfirmation = true)
            }
        } else {
            changeAudioCreationMode(mode)
        }
    }

    fun onConfirmAudioCreationModeChange() {
        val mode = _uiState.value.pendingAudioCreationMode ?: return
        if (_uiState.value.generationStatus is GenerationStatus.Generating) cancelGeneration()
        if (_uiState.value.recordingState is RecordingState.Recording ||
            _uiState.value.recordingState is RecordingState.AwaitingPermission ||
            _uiState.value.recordingState is RecordingState.Reviewing ||
            _uiState.value.recordingState is RecordingState.Error
        ) onDiscardRecording()
        _uiState.update { it.copy(pendingAudioCreationMode = null, showModeSwitchConfirmation = false) }
        changeAudioCreationMode(mode)
    }

    fun onDismissAudioCreationModeChange() {
        _uiState.update { it.copy(pendingAudioCreationMode = null, showModeSwitchConfirmation = false) }
    }

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
        _uiState.update {
            it.copy(
                audioCreationMode = AudioCreationMode.TEXT,
                generationStatus = GenerationStatus.Generating(request.revision),
                previewErrorMessage = null,
            )
        }
        generationJob = viewModelScope.launch {
            try {
                val result = withTimeout(GENERATION_TIMEOUT_MILLIS) { speechGeneration.generate(request) }
                if (requestId != activeRequestId || _uiState.value.draftRevision != request.revision) return@launch
                audioPreview.stop()
                generatedSpeech = result
                assetUsageRegistry.setEditorDraftAsset(result.assetId)
                _uiState.update {
                    it.copy(
                        generationStatus = GenerationStatus.Ready(result.sourceRevision),
                        generatedAudio = GeneratedAudioUi(
                            result.assetId,
                            result.sourceRevision,
                            result.durationMillis,
                            result.generationLatencyMillis,
                            isTextSynchronized = true,
                        ),
                        audioSourceText = request.text.trim(),
                        selectedAudioProvenance = AudioAssetProvenance.GENERATED_TEXT,
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
        if (_uiState.value.recordingState is RecordingState.Recording) return
        val speech = generatedSpeech ?: return
        if (_uiState.value.isPreviewPlaying) audioPreview.pause() else audioPreview.play(speech.file)
    }

    fun onRecordVoiceRequested() {
        if (_uiState.value.recordingState is RecordingState.Recording ||
            _uiState.value.recordingState is RecordingState.AwaitingPermission
        ) return
        audioPreview.stop()
        _uiState.update {
            it.copy(
                audioCreationMode = AudioCreationMode.VOICE,
                recordingState = RecordingState.AwaitingPermission,
                previewErrorMessage = null,
            )
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        if (_uiState.value.recordingState !is RecordingState.AwaitingPermission) return
        if (!granted) {
            _uiState.update {
                it.copy(
                    recordingState = if (permanentlyDenied) {
                        RecordingState.PermissionPermanentlyDenied
                    } else {
                        RecordingState.PermissionDenied
                    },
                )
            }
            return
        }
        startRecording()
    }

    fun onStopRecording() {
        if (_uiState.value.recordingState !is RecordingState.Recording) return
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        try {
            recordingFile = voiceRecorder.stop()
        } catch (exception: Exception) {
            voiceRecorder.discard()
            recordingFile = null
            _uiState.update {
                it.copy(recordingState = RecordingState.Error(exception.message ?: "Could not finish recording."))
            }
            return
        }
        val file = recordingFile ?: return
        val recordingId = activeRecordingId
        recordingValidationJob?.cancel()
        recordingValidationJob = viewModelScope.launch {
            val duration = runCatching { recordingValidator.validate(file) }
            duration.onSuccess { validDuration ->
                if (recordingId != activeRecordingId) return@onSuccess
                _uiState.update { it.copy(recordingState = RecordingState.Reviewing(validDuration)) }
            }.onFailure { exception ->
                if (recordingId != activeRecordingId) return@onFailure
                voiceRecorder.discard()
                recordingFile = null
                _uiState.update {
                    it.copy(recordingState = RecordingState.Error(exception.message ?: "The recording is invalid."))
                }
            }
        }
    }

    fun onToggleRecordingPreview() {
        if (_uiState.value.recordingState !is RecordingState.Reviewing) return
        val file = recordingFile?.takeIf { it.isFile && it.canRead() } ?: return
        if (_uiState.value.isPreviewPlaying) audioPreview.pause() else audioPreview.play(file)
    }

    fun onKeepRecording() {
        val file = recordingFile ?: return
        val duration = (_uiState.value.recordingState as? RecordingState.Reviewing)?.durationMillis ?: return
        viewModelScope.launch {
            try {
                audioPreview.stop()
                val completed = voiceRecorder.promote(file)
                recordingFile = completed
                val asset = audioAssetRepository.register(
                    com.yavin.wakeupsamurai.domain.alarm.AudioAsset(
                        id = UUID.randomUUID().toString(),
                        relativePath = storage.relativePath(completed),
                        sourceText = null,
                        voice = null,
                        languageTag = null,
                        createdAt = timeProvider.now(),
                        durationMillis = duration,
                        provenance = AudioAssetProvenance.RECORDED_VOICE,
                    ),
                )
                recordingFile = null
                generatedSpeech = GeneratedSpeech(
                    assetId = asset.id,
                    file = completed,
                    durationMillis = duration,
                    sourceRevision = _uiState.value.draftRevision,
                    voice = "",
                    generationLatencyMillis = 0L,
                )
                assetUsageRegistry.setEditorDraftAsset(asset.id)
                _uiState.update {
                    it.copy(
                        generatedAudio = GeneratedAudioUi(
                            assetId = asset.id,
                            sourceRevision = it.draftRevision,
                            durationMillis = duration,
                            generationLatencyMillis = 0L,
                            isTextSynchronized = false,
                        ),
                        generationStatus = GenerationStatus.Ready(it.draftRevision),
                        audioSourceText = null,
                        selectedAudioProvenance = AudioAssetProvenance.RECORDED_VOICE,
                        recordingState = RecordingState.Idle,
                        isDirty = true,
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(recordingState = RecordingState.Error(exception.message ?: "Could not keep recording."))
                }
            }
        }
    }

    fun onDiscardRecording() {
        audioPreview.stop()
        activeRecordingId++
        recordingValidationJob?.cancel()
        recordingValidationJob = null
        recordingTickerJob?.cancel()
        recordingTickerJob = null
        voiceRecorder.discard()
        recordingFile = null
        _uiState.update { it.copy(recordingState = RecordingState.Idle) }
    }

    fun onHostBackgrounded() {
        val recording = _uiState.value.recordingState
        if (recording is RecordingState.Recording || recording is RecordingState.Reviewing) onDiscardRecording()
    }

    fun onToggleSavedAudioPreview(assetId: String) {
        val asset = availableAudioAssets[assetId] ?: return
        if (!storage.isReadable(asset.relativePath)) return
        val file = storage.resolve(asset.relativePath) ?: return
        val picker = _savedAudioPickerUiState.value
        if (picker.previewingAssetId == assetId && _uiState.value.isPreviewPlaying) {
            audioPreview.pause()
        } else {
            audioPreview.play(file)
            _savedAudioPickerUiState.update { it.copy(previewingAssetId = assetId, previewErrorMessage = null) }
        }
    }

    fun onSelectSavedAudio(assetId: String): Boolean {
        val asset = availableAudioAssets[assetId] ?: return false
        if (!storage.isReadable(asset.relativePath)) return false
        val file = storage.resolve(asset.relativePath)?.takeIf { it.isFile && it.canRead() } ?: return false
        audioPreview.stop()
        generatedSpeech = GeneratedSpeech(
            assetId = asset.id,
            file = file,
            durationMillis = asset.durationMillis ?: 0L,
            sourceRevision = _uiState.value.draftRevision,
            voice = asset.voice.orEmpty(),
            generationLatencyMillis = 0L,
        )
        assetUsageRegistry.setEditorDraftAsset(asset.id)
        _uiState.update {
            it.copy(
                generatedAudio = GeneratedAudioUi(
                    assetId = asset.id,
                    sourceRevision = it.draftRevision,
                    durationMillis = asset.durationMillis ?: 0L,
                    generationLatencyMillis = 0L,
                    isTextSynchronized = false,
                ),
                generationStatus = GenerationStatus.Ready(it.draftRevision),
                audioSourceText = asset.sourceText,
                selectedAudioProvenance = asset.provenance,
                previewErrorMessage = null,
                isDirty = true,
            )
        }
        return true
    }

    fun onLeaveSavedAudioPicker() {
        audioPreview.stop()
        _savedAudioPickerUiState.update { it.copy(previewingAssetId = null, previewErrorMessage = null) }
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
                state.generatedAudio?.isTextSynchronized == true &&
                    state.audioSourceText != state.messageDraft.trim() ->
                    "Regenerate audio or restore its original text before enabling."
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
                audioSelectionKind = when {
                        audio == null -> null
                        state.generatedAudio?.isTextSynchronized == true -> AudioSelectionKind.GENERATED_FOR_TEXT
                        else -> AudioSelectionKind.SAVED_ASSET
                    },
                contentMode = state.audioCreationMode.toDomainMode(),
                automaticVoiceTitle = existing?.automaticVoiceTitle,
                )
                when (val result = alarmCoordinator.save(alarm)) {
                    is AlarmSaveResult.Scheduled -> completeSave(result.alarm, close = true)
                    is AlarmSaveResult.SavedWithoutSchedule -> {
                        completeSave(result.alarm, close = true)
                    }
                    is AlarmSaveResult.BlockedExactAlarmAccess -> {
                        completeSave(result.alarm, close = false)
                        _uiState.update {
                            it.copy(
                                validationMessage = "Allow alarms & reminders to schedule this alarm.",
                                schedulingAccessRequest = SchedulingAccessRequest.EXACT_ALARM,
                            )
                        }
                    }
                    is AlarmSaveResult.BlockedNotificationPermission -> {
                        completeSave(result.alarm, close = false)
                        _uiState.update {
                            it.copy(
                                validationMessage = "Allow notifications so the ringing alarm can be dismissed.",
                                schedulingAccessRequest = SchedulingAccessRequest.NOTIFICATIONS,
                            )
                        }
                    }
                    is AlarmSaveResult.BlockedNotificationsDisabled -> {
                        completeSave(result.alarm, close = false)
                        _uiState.update {
                            it.copy(
                                validationMessage = "Enable app notifications so the alarm screen and controls remain available.",
                                schedulingAccessRequest = SchedulingAccessRequest.APP_NOTIFICATIONS,
                            )
                        }
                    }
                    is AlarmSaveResult.BlockedAlarmChannel -> {
                        completeSave(result.alarm, close = false)
                        _uiState.update {
                            it.copy(
                                validationMessage = "Enable the Ringing alarms channel for notification controls and the alarm screen.",
                                schedulingAccessRequest = SchedulingAccessRequest.ALARM_CHANNEL,
                            )
                        }
                    }
                    is AlarmSaveResult.Failed -> _uiState.update {
                        it.copy(isSaving = false, validationMessage = result.message)
                    }
                }
            } catch (exception: Exception) {
                _uiState.update { it.copy(isSaving = false, validationMessage = exception.message ?: "Could not save alarm.") }
            }
        }
    }

    fun onBackRequested() {
        if (_uiState.value.recordingState is RecordingState.Recording ||
            _uiState.value.recordingState is RecordingState.Reviewing
        ) onDiscardRecording()
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
            alarmCoordinator.delete(id)
            closeEditor()
        }
    }

    /** Called from the host after Android's exact-alarm settings panel returns. */
    fun onSchedulingAccessReturned() {
        val id = _uiState.value.alarmId ?: return
        if (_uiState.value.schedulingAccessRequest !in setOf(
                SchedulingAccessRequest.EXACT_ALARM,
                SchedulingAccessRequest.APP_NOTIFICATIONS,
                SchedulingAccessRequest.ALARM_CHANNEL,
            )
        ) return
        retryScheduling(id)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (_uiState.value.schedulingAccessRequest != SchedulingAccessRequest.NOTIFICATIONS) return
        if (!granted) {
            _uiState.update { it.copy(schedulingAccessRequest = null, validationMessage = "Notifications are required for the Dismiss action.") }
            return
        }
        _uiState.value.alarmId?.let(::retryScheduling)
    }

    fun onLeaveEditor() {
        audioPreview.release()
    }

    override fun onCleared() {
        generationJob?.cancel()
        onDiscardRecording()
        audioPreview.release()
        assetUsageRegistry.setEditorDraftAsset(null)
    }

    private fun edit(transform: AlarmEditorUiState.() -> AlarmEditorUiState) {
        _uiState.update { it.transform().copy(isDirty = true, validationMessage = null) }
    }
    private fun applySuggestion() = onMessageDraftChanged(SUGGESTED_PHRASE)

    private fun changeAudioCreationMode(mode: AudioCreationMode) {
        audioPreview.stop()
        edit { copy(audioCreationMode = mode, previewErrorMessage = null) }
    }

    private fun AudioCreationMode.toDomainMode(): AlarmContentMode = when (this) {
        AudioCreationMode.TEXT -> AlarmContentMode.TEXT
        AudioCreationMode.VOICE -> AlarmContentMode.VOICE
    }

    private fun AlarmContentMode.toEditorMode(): AudioCreationMode = when (this) {
        AlarmContentMode.TEXT -> AudioCreationMode.TEXT
        AlarmContentMode.VOICE -> AudioCreationMode.VOICE
    }

    private fun AudioAssetProvenance?.toEditorModeOrNull(): AudioCreationMode? = when (this) {
        AudioAssetProvenance.GENERATED_TEXT -> AudioCreationMode.TEXT
        AudioAssetProvenance.RECORDED_VOICE -> AudioCreationMode.VOICE
        AudioAssetProvenance.UNKNOWN, null -> null
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
    private fun closeEditor() {
        if (!sessionActive) return
        sessionActive = false
        cancelGeneration()
        onDiscardRecording()
        audioPreview.release()
        assetUsageRegistry.setEditorDraftAsset(null)
        _closeEvents.tryEmit(Unit)
    }

    private fun retryScheduling(id: String) {
        if (saveJob?.isActive == true) return
        saveJob = viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, validationMessage = null, schedulingAccessRequest = null) }
            when (val result = alarmCoordinator.retry(id)) {
                is AlarmSaveResult.Scheduled -> completeSave(result.alarm, close = true)
                is AlarmSaveResult.BlockedExactAlarmAccess -> _uiState.update {
                    it.copy(isSaving = false, validationMessage = "Alarm access is still disabled.")
                }
                is AlarmSaveResult.BlockedNotificationPermission -> _uiState.update {
                    it.copy(isSaving = false, validationMessage = "Notification permission is still required.", schedulingAccessRequest = SchedulingAccessRequest.NOTIFICATIONS)
                }
                is AlarmSaveResult.BlockedNotificationsDisabled -> _uiState.update {
                    it.copy(isSaving = false, validationMessage = "App notifications are still disabled.", schedulingAccessRequest = SchedulingAccessRequest.APP_NOTIFICATIONS)
                }
                is AlarmSaveResult.BlockedAlarmChannel -> _uiState.update {
                    it.copy(isSaving = false, validationMessage = "The Ringing alarms channel is still disabled.", schedulingAccessRequest = SchedulingAccessRequest.ALARM_CHANNEL)
                }
                is AlarmSaveResult.SavedWithoutSchedule -> completeSave(result.alarm, close = false)
                is AlarmSaveResult.Failed -> _uiState.update { it.copy(isSaving = false, validationMessage = result.message) }
            }
        }
    }

    private fun completeSave(alarm: Alarm, close: Boolean) {
        loadedAlarm = alarm
        _uiState.update {
            it.copy(
                alarmId = alarm.id,
                isSaving = false,
                isDirty = false,
                hasPersistedAlarm = true,
                resolvedOneShotEpochMillis = alarm.resolvedOneShotAt?.toEpochMilli(),
                isScheduleDirty = false,
                schedulingState = alarm.schedulingState,
                hasPendingRegularOccurrence = alarm.scheduledOccurrenceId != null,
                schedulingAccessRequest = null,
            )
        }
        if (close) closeEditor()
    }

    private fun startRecording() {
        try {
            audioPreview.stop()
            recordingFile = voiceRecorder.start()
            activeRecordingId++
            recordingStartedAtMillis = System.nanoTime() / 1_000_000L
            _uiState.update { it.copy(recordingState = RecordingState.Recording()) }
            recordingTickerJob?.cancel()
            recordingTickerJob = viewModelScope.launch {
                while (true) {
                    delay(RECORDING_TICK_MILLIS)
                    val elapsed = (System.nanoTime() / 1_000_000L - recordingStartedAtMillis).coerceAtLeast(0L)
                    _uiState.update {
                        if (it.recordingState is RecordingState.Recording) {
                            it.copy(recordingState = RecordingState.Recording(elapsed))
                        } else {
                            it
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            voiceRecorder.discard()
            recordingFile = null
            _uiState.update {
                it.copy(recordingState = RecordingState.Error(exception.message ?: "Could not start recording."))
            }
        }
    }
    private fun formatOccurrence(instant: Instant): String {
        val value = instant.atZone(timeProvider.zoneId())
        return value.format(DateTimeFormatter.ofPattern("EEE, d MMM 'at' HH:mm"))
    }

    companion object {
        const val SUGGESTED_PHRASE = "Wake up (wake up)\nGrab a brush and put a little make-up"
        const val GENERATION_TIMEOUT_MILLIS = 45_000L
        const val RECORDING_TICK_MILLIS = 250L
    }
}
