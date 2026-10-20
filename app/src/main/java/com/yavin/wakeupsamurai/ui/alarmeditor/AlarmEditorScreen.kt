package com.yavin.wakeupsamurai.ui.alarmeditor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetProvenance
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import com.yavin.wakeupsamurai.ui.components.ThemeModeAction
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import com.yavin.wakeupsamurai.ui.components.GradientFilledIconButton
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

private const val LOADING_PLACEHOLDER_DELAY_MILLIS = 180L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(
    uiState: AlarmEditorUiState,
    onAudioCreationModeChanged: (AudioCreationMode) -> Unit,
    onMessageDraftChanged: (String) -> Unit,
    onTryPhrase: () -> Unit,
    onConfirmSuggestionReplacement: () -> Unit,
    onDismissSuggestionReplacement: () -> Unit,
    onConfirmAudioCreationModeChange: () -> Unit,
    onDismissAudioCreationModeChange: () -> Unit,
    onGenerateAudio: () -> Unit,
    onCancelGeneration: () -> Unit,
    onRetryGeneration: () -> Unit,
    onTogglePreview: () -> Unit,
    onRecordVoiceRequested: () -> Unit,
    onMicrophonePermissionResult: (Boolean, Boolean) -> Unit,
    onStopRecording: () -> Unit,
    onToggleRecordingPreview: () -> Unit,
    onKeepRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onChooseSavedAudio: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onTimeChanged: (Int, Int) -> Unit,
    onRepeatDayToggled: (DayOfWeek) -> Unit,
    onConfiguredEnabledChanged: (Boolean) -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onOpenAlarmChannelSettings: () -> Unit,
    onRestoreAudioText: () -> Unit,
    onSave: () -> Unit,
    onDeleteRequested: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onBack: () -> Unit,
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // Unlike Configuration.locales, LocalLocale is present in layout previews and updates on locale changes.
    val locale = LocalLocale.current.platformLocale
    var showTimePicker by remember { mutableStateOf(false) }
    var showLoadingPlaceholder by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isLoading) {
        showLoadingPlaceholder = false
        if (uiState.isLoading) {
            delay(LOADING_PLACEHOLDER_DELAY_MILLIS)
            showLoadingPlaceholder = true
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanentlyDenied = !granted &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        onMicrophonePermissionResult(granted, permanentlyDenied)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onNotificationPermissionResult(it)
    }
    val requestRecording = {
        focusManager.clearFocus()
        onRecordVoiceRequested()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onMicrophonePermissionResult(true, false)
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(uiState.hour, uiState.minute, is24Hour = true)
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Choose time") },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChanged(pickerState.hour, pickerState.minute)
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { TimePicker(state = pickerState) }
    }

    EditorDialogs(
        uiState = uiState,
        onConfirmSuggestionReplacement = onConfirmSuggestionReplacement,
        onDismissSuggestionReplacement = onDismissSuggestionReplacement,
        onConfirmAudioCreationModeChange = onConfirmAudioCreationModeChange,
        onDismissAudioCreationModeChange = onDismissAudioCreationModeChange,
        onConfirmDelete = onConfirmDelete,
        onDismissDelete = onDismissDelete,
        onConfirmDiscard = onConfirmDiscard,
        onDismissDiscard = onDismissDiscard,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (uiState.alarmId == null) R.string.new_alarm else R.string.edit_alarm))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (uiState.hasPersistedAlarm) {
                        IconButton(
                            onClick = onDeleteRequested,
                            enabled = !uiState.isLoading && !uiState.isSaving,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_24),
                                contentDescription = stringResource(R.string.delete_alarm),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            onSave()
                        },
                        enabled = !uiState.isLoading && !uiState.isSaving,
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_save_24),
                                contentDescription = stringResource(R.string.save_alarm),
                            )
                        }
                    }
                    ThemeModeAction(themeMode = themeMode, onCycleThemeMode = onCycleThemeMode)
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (uiState.isLoading && showLoadingPlaceholder) {
                LoadingEditorContent()
            } else if (!uiState.isLoading) {
                AudioCreationModeSelector(
                    selected = uiState.audioCreationMode,
                    enabled = !uiState.isSaving,
                    onSelected = {
                        focusManager.clearFocus()
                        onAudioCreationModeChanged(it)
                    },
                )
                val textModeVisible = uiState.audioCreationMode == AudioCreationMode.TEXT
                AnimatedVisibility(
                    visible = textModeVisible,
                    enter = fadeIn(animationSpec = tween(120)) +
                        expandVertically(animationSpec = tween(180), expandFrom = Alignment.Top),
                    exit = fadeOut(animationSpec = tween(90)) +
                        shrinkVertically(animationSpec = tween(180), shrinkTowards = Alignment.Top),
                ) {
                    TextMessageInput(
                        uiState = uiState,
                        isActive = textModeVisible,
                        onMessageDraftChanged = onMessageDraftChanged,
                        onTryPhrase = {
                            focusManager.clearFocus()
                            onTryPhrase()
                        },
                    )
                }
                LabelInput(
                    label = uiState.label,
                    onLabelChanged = onLabelChanged,
                )
                AudioCard(
                    uiState = uiState,
                    onGenerateAudio = {
                        focusManager.clearFocus()
                        onGenerateAudio()
                    },
                    onCancelGeneration = onCancelGeneration,
                    onRetryGeneration = onRetryGeneration,
                    onTogglePreview = onTogglePreview,
                    onStartRecording = requestRecording,
                    onStopRecording = onStopRecording,
                    onToggleRecordingPreview = onToggleRecordingPreview,
                    onKeepRecording = onKeepRecording,
                    onRecordAgain = {
                        onDiscardRecording()
                        requestRecording()
                    },
                    onDiscardRecording = onDiscardRecording,
                    onChooseSavedAudio = onChooseSavedAudio,
                    onRestoreAudioText = onRestoreAudioText,
                    onOpenAppSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        })
                    },
                )
                ScheduleCard(
                    uiState = uiState,
                    locale = locale,
                    onChooseTime = { showTimePicker = true },
                    onRepeatDayToggled = onRepeatDayToggled,
                    onConfiguredEnabledChanged = onConfiguredEnabledChanged,
                    onResolveAccess = {
                        when (uiState.schedulingAccessRequest) {
                            SchedulingAccessRequest.EXACT_ALARM -> context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                },
                            )
                            SchedulingAccessRequest.NOTIFICATIONS ->
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            SchedulingAccessRequest.APP_NOTIFICATIONS -> context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                },
                            )
                            SchedulingAccessRequest.ALARM_CHANNEL -> onOpenAlarmChannelSettings()
                            null -> Unit
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TextMessageInput(
    uiState: AlarmEditorUiState,
    isActive: Boolean,
    onMessageDraftChanged: (String) -> Unit,
    onTryPhrase: () -> Unit,
) {
    val accessibilityModifier = if (isActive) Modifier else Modifier.clearAndSetSemantics { }
    val tryPhraseAlignment = when (LocalLayoutDirection.current) {
        LayoutDirection.Ltr -> Alignment.TopEnd
        LayoutDirection.Rtl -> Alignment.TopStart
    }
    Column(
        modifier = accessibilityModifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = uiState.messageDraft,
                onValueChange = onMessageDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                enabled = isActive,
                label = { Text(stringResource(R.string.editor_title)) },
                placeholder = { Text(stringResource(R.string.editor_hint)) },
            )
            TextButton(
                onClick = onTryPhrase,
                enabled = isActive,
                modifier = Modifier
                    .align(tryPhraseAlignment)
                    .offset(x = (0).dp, y = (0).dp)
                    .height(32.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_auto_awesome_24),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.try_phrase))
            }
        }
        if (uiState.isGeneratedAudioOutdated) {
            Text(
                text = stringResource(R.string.draft_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LabelInput(
    label: String,
    onLabelChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = label,
        onValueChange = onLabelChanged,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.label_optional)) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioCreationModeSelector(
    selected: AudioCreationMode,
    enabled: Boolean,
    onSelected: (AudioCreationMode) -> Unit,
) {
    val modes = AudioCreationMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val isSelected = selected == mode
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelected(mode) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                modifier = Modifier.weight(1f),
                icon = {},
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(18.dp)
                            .alpha(if (isSelected) 1f else 0f),
                        contentAlignment = Alignment.Center,
                    ) {
                        SegmentedButtonDefaults.Icon(active = true)
                    }
                    Text(
                        text = stringResource(
                            if (mode == AudioCreationMode.TEXT) R.string.audio_from_text
                            else R.string.audio_record_voice,
                        ),
                        modifier = Modifier.align(Alignment.Center),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioCard(
    uiState: AlarmEditorUiState,
    onGenerateAudio: () -> Unit,
    onCancelGeneration: () -> Unit,
    onRetryGeneration: () -> Unit,
    onTogglePreview: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onToggleRecordingPreview: () -> Unit,
    onKeepRecording: () -> Unit,
    onRecordAgain: () -> Unit,
    onDiscardRecording: () -> Unit,
    onChooseSavedAudio: () -> Unit,
    onRestoreAudioText: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val recording = uiState.recordingState
    val showingVoiceDraft = uiState.audioCreationMode == AudioCreationMode.VOICE && recording !is RecordingState.Idle
    val isGenerating = uiState.generationStatus is GenerationStatus.Generating
    val canPreview = if (showingVoiceDraft && recording is RecordingState.Reviewing) true else uiState.generatedAudio != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(72.dp), contentAlignment = Alignment.CenterStart) {
                    when {
                        isGenerating && uiState.generatedAudio == null -> GradientFilledIconButton(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.size(48.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        canPreview -> GradientFilledIconButton(
                            onClick = if (showingVoiceDraft) onToggleRecordingPreview else onTogglePreview,
                            animateGradient = uiState.isPreviewPlaying,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (uiState.isPreviewPlaying) R.drawable.ic_pause_24
                                    else R.drawable.ic_play_arrow_24,
                                ),
                                contentDescription = stringResource(
                                    if (uiState.isPreviewPlaying) R.string.pause_audio
                                    else R.string.play_audio,
                                ),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val (title, detail) = audioStatusText(uiState, showingVoiceDraft)
                    val hasError = uiState.previewErrorMessage != null || if (uiState.selectedAudioProvenance == AudioAssetProvenance.GENERATED_TEXT ||
                        uiState.generatedAudio == null && uiState.audioCreationMode == AudioCreationMode.TEXT
                    ) {
                        uiState.isGeneratedAudioOutdated || uiState.generationStatus is GenerationStatus.Error
                    } else {
                        recording is RecordingState.Error ||
                            recording is RecordingState.PermissionDenied ||
                            recording is RecordingState.PermissionPermanentlyDenied
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = uiState.previewErrorMessage ?: detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.previewErrorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.audioCreationMode == AudioCreationMode.TEXT) {
                    when (uiState.generationStatus) {
                        is GenerationStatus.Generating -> AudioActionButton(
                            label = stringResource(R.string.cancel_generation),
                            onClick = onCancelGeneration,
                        )
                        is GenerationStatus.Error -> AudioActionButton(
                            label = stringResource(R.string.retry_generation),
                            onClick = onRetryGeneration,
                        )
                        else -> AudioActionButton(
                            label = stringResource(if (uiState.generatedAudio == null) R.string.generate_audio else R.string.regenerate_audio),
                            onClick = onGenerateAudio,
                            enabled = uiState.canGenerate,
                        )
                    }
                    if (uiState.canRestoreAudioText) {
                        AudioActionButton(label = "Restore text", onClick = onRestoreAudioText)
                    }
                } else {
                    when (recording) {
                        RecordingState.AwaitingPermission -> AudioActionButton(
                            label = stringResource(R.string.cancel),
                            onClick = onDiscardRecording,
                        )
                        RecordingState.PermissionPermanentlyDenied -> AudioActionButton(
                            label = stringResource(R.string.open_settings),
                            onClick = onOpenAppSettings,
                        )
                        is RecordingState.Recording -> {
                            AudioActionButton(label = stringResource(R.string.stop_recording), onClick = onStopRecording)
                            AudioActionButton(label = stringResource(R.string.cancel), onClick = onDiscardRecording)
                        }
                        is RecordingState.Reviewing -> {
                            AudioActionButton(label = stringResource(R.string.keep_recording), onClick = onKeepRecording)
                            AudioActionButton(label = stringResource(R.string.discard_recording), onClick = onDiscardRecording)
                            AudioActionButton(label = stringResource(R.string.record_again), onClick = onRecordAgain)
                        }
                        else -> AudioActionButton(label = stringResource(R.string.start_recording), onClick = onStartRecording)
                    }
                }
                if (recording !is RecordingState.Reviewing) {
                    AudioActionButton(
                        label = stringResource(R.string.choose_saved_audio),
                        onClick = onChooseSavedAudio,
                        enabled = recording !is RecordingState.Recording &&
                            recording !is RecordingState.AwaitingPermission &&
                            !isGenerating,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.AudioActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun audioStatusText(uiState: AlarmEditorUiState, showingVoiceDraft: Boolean): Pair<String, String> {
    if (showingVoiceDraft) {
        return when (val recording = uiState.recordingState) {
            RecordingState.AwaitingPermission -> stringResource(R.string.audio_record_voice) to stringResource(R.string.recording_waiting_permission)
            RecordingState.PermissionDenied -> stringResource(R.string.recording_permission_denied) to stringResource(R.string.audio_record_voice)
            RecordingState.PermissionPermanentlyDenied -> stringResource(R.string.recording_permission_blocked) to stringResource(R.string.audio_record_voice)
            is RecordingState.Recording -> stringResource(R.string.recording_in_progress, formatDuration(recording.elapsedMillis)) to
                "Recording stays in this editor until you keep it."
            is RecordingState.Reviewing -> stringResource(R.string.recording_ready) to
                stringResource(R.string.audio_duration, formatDuration(recording.durationMillis))
            is RecordingState.Error -> "Recording failed" to recording.message
            RecordingState.Idle -> error("Idle recording is not a voice draft")
        }
    }

    val audio = uiState.generatedAudio
    if (audio == null) {
        return if (uiState.audioCreationMode == AudioCreationMode.VOICE) {
            stringResource(R.string.audio_not_created) to stringResource(R.string.record_voice_hint)
        } else {
            textGenerationStatus(uiState, null)
        }
    }

    return when (uiState.selectedAudioProvenance) {
        AudioAssetProvenance.RECORDED_VOICE -> stringResource(R.string.voice_audio_selected) to
            selectedAudioDetail(uiState, audio)
        AudioAssetProvenance.UNKNOWN -> stringResource(R.string.saved_audio_selected) to
            selectedAudioDetail(uiState, audio)
        AudioAssetProvenance.GENERATED_TEXT -> if (uiState.audioCreationMode == AudioCreationMode.TEXT) {
            textGenerationStatus(uiState, audio)
        } else {
            stringResource(R.string.text_audio_selected) to selectedAudioDetail(uiState, audio)
        }
        null -> textGenerationStatus(uiState, audio)
    }
}

@Composable
private fun textGenerationStatus(uiState: AlarmEditorUiState, audio: GeneratedAudioUi?): Pair<String, String> =
    when (val generation = uiState.generationStatus) {
        is GenerationStatus.Generating -> stringResource(R.string.generation_generating) to
            if (audio == null) stringResource(R.string.generation_online_notice)
            else "Your previous audio remains available while the replacement is generated."
        is GenerationStatus.Error -> stringResource(R.string.generation_error, generation.message) to
            if (audio == null) stringResource(R.string.generation_online_notice) else "The previous audio is still available."
        else -> when {
            audio == null -> stringResource(R.string.audio_not_created) to stringResource(R.string.generation_online_notice)
            uiState.isGeneratedAudioOutdated -> stringResource(R.string.generation_outdated) to audioDurationText(audio)
            audio.isTextSynchronized -> stringResource(R.string.generation_ready) to audioDurationText(audio)
            else -> stringResource(R.string.saved_audio_selected) to audioDurationText(audio)
        }
    }

@Composable
private fun selectedAudioDetail(uiState: AlarmEditorUiState, audio: GeneratedAudioUi): String {
    val modeDiffers = when (uiState.selectedAudioProvenance) {
        AudioAssetProvenance.RECORDED_VOICE -> uiState.audioCreationMode != AudioCreationMode.VOICE
        AudioAssetProvenance.GENERATED_TEXT -> uiState.audioCreationMode != AudioCreationMode.TEXT
        AudioAssetProvenance.UNKNOWN, null -> false
    }
    return if (modeDiffers) stringResource(R.string.selected_audio_from_other_mode, audioDurationText(audio))
    else audioDurationText(audio)
}

@Composable
private fun audioDurationText(audio: GeneratedAudioUi): String =
    if (audio.durationMillis > 0L) stringResource(R.string.audio_duration, formatDuration(audio.durationMillis))
    else stringResource(R.string.duration_unavailable)

@Composable
private fun ScheduleCard(
    uiState: AlarmEditorUiState,
    locale: Locale,
    onChooseTime: () -> Unit,
    onRepeatDayToggled: (DayOfWeek) -> Unit,
    onConfiguredEnabledChanged: (Boolean) -> Unit,
    onResolveAccess: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.schedule), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(
                    onClick = onChooseTime,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        width = 3.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFC38BA9),
                                Color(0xFFFFD1B5),
                                Color(0xFFBFA6E8),
                            ),
                        ),
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        String.format(Locale.US, "%02d:%02d", uiState.hour, uiState.minute),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = uiState.configuredEnabled, onCheckedChange = onConfiguredEnabledChanged)
                }
            }
            Text(
                text = stringResource(
                    if (uiState.repeatDays.isEmpty()) R.string.one_occurrence_summary else R.string.weekly_occurrence_summary,
                    uiState.nextOccurrenceSummary,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            DayRows(uiState.repeatDays, locale, onRepeatDayToggled)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiState.validationMessage ?: scheduleStatus(uiState),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.validationMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (uiState.schedulingAccessRequest != null) {
                    TextButton(onClick = onResolveAccess) { Text(stringResource(R.string.fix_access)) }
                }
            }
        }
    }
}

@Composable
private fun DayRows(
    selectedDays: Set<DayOfWeek>,
    locale: Locale,
    onToggle: (DayOfWeek) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DayOfWeek.entries.chunked(4).forEach { days ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { day ->
                    FilterChip(
                        selected = day in selectedDays,
                        onClick = { onToggle(day) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        label = {
                            Text(
                                day.getDisplayName(TextStyle.SHORT, locale),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - days.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun scheduleStatus(uiState: AlarmEditorUiState): String = when {
    uiState.isScheduleDirty && uiState.configuredEnabled -> "Save to apply this schedule."
    uiState.hasPendingRegularOccurrence && uiState.configuredEnabled -> "Scheduled with Android."
    uiState.schedulingState.name == "SNOOZED" && uiState.configuredEnabled -> "Snoozed with Android."
    uiState.configuredEnabled -> "Save to request access and schedule this alarm."
    else -> "Enable and save to schedule this alarm."
}

@Composable
private fun LoadingEditorContent() {
    AudioCreationModeSelector(
        selected = AudioCreationMode.TEXT,
        enabled = false,
        onSelected = {},
    )
    OutlinedTextField(
        value = "",
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        minLines = 3,
        maxLines = 6,
        label = { Text(stringResource(R.string.editor_title)) },
    )
    OutlinedTextField(
        value = "",
        onValueChange = {},
        modifier = Modifier.fillMaxWidth(),
        enabled = false,
        singleLine = true,
        label = { Text(stringResource(R.string.label_optional)) },
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(32.dp))
            Text(stringResource(R.string.loading_alarm), style = MaterialTheme.typography.titleMedium)
        }
    }
    LoadingScheduleCard()
}

@Composable
private fun LoadingScheduleCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.schedule), style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = {}, enabled = false, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("--:--", style = MaterialTheme.typography.headlineMedium)
                }
                Switch(checked = false, onCheckedChange = null, enabled = false)
            }
            Text(
                text = stringResource(R.string.loading_alarm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LoadingDayRows()
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun LoadingDayRows() {
    DayOfWeek.entries.chunked(4).forEach { days ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { day ->
                FilterChip(
                    selected = false,
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(
                            day.getDisplayName(TextStyle.SHORT, LocalLocale.current.platformLocale),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(4 - days.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun EditorDialogs(
    uiState: AlarmEditorUiState,
    onConfirmSuggestionReplacement: () -> Unit,
    onDismissSuggestionReplacement: () -> Unit,
    onConfirmAudioCreationModeChange: () -> Unit,
    onDismissAudioCreationModeChange: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
) {
    if (uiState.showModeSwitchConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissAudioCreationModeChange,
            title = { Text(stringResource(R.string.switch_audio_mode_title)) },
            text = { Text(stringResource(R.string.switch_audio_mode_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmAudioCreationModeChange) {
                    Text(stringResource(R.string.discard_and_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissAudioCreationModeChange) {
                    Text(stringResource(R.string.keep_current_mode))
                }
            },
        )
    }
    if (uiState.showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved alarm changes will be lost. Completed audio stays in the local asset store.") },
            confirmButton = { TextButton(onClick = onConfirmDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = onDismissDiscard) { Text("Keep editing") } },
        )
    }
    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Delete alarm?") },
            text = { Text("The alarm definition will be deleted. Its completed audio asset will be retained.") },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (uiState.showSuggestionReplacementConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissSuggestionReplacement,
            title = { Text(stringResource(R.string.suggestion_replace_title)) },
            text = { Text(stringResource(R.string.suggestion_replace_body)) },
            confirmButton = { TextButton(onClick = onConfirmSuggestionReplacement) { Text(stringResource(R.string.replace)) } },
            dismissButton = { TextButton(onClick = onDismissSuggestionReplacement) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

@Preview(name = "Text ready", group = "Editor", showBackground = true)
@Composable
private fun AlarmEditorTextReadyPreview() = AlarmEditorScreenPreviewContent(
    AlarmEditorUiState(
        messageDraft = "Wake up (wake up)\nGrab a brush and put a little make-up",
        draftRevision = 1L,
        generationStatus = GenerationStatus.Ready(sourceRevision = 1L),
        generatedAudio = GeneratedAudioUi("preview", 1L, 4_224L, 935L),
        selectedAudioProvenance = AudioAssetProvenance.GENERATED_TEXT,
        nextOccurrenceSummary = "Tomorrow at 07:00",
        repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        configuredEnabled = true,
    ),
)

@Preview(
    name = "Text ready dark",
    group = "Editor",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlarmEditorTextReadyDarkPreview() = AlarmEditorScreenPreviewContent(
    uiState = AlarmEditorUiState(
        messageDraft = "Wake up (wake up)\nGrab a brush and put a little make-up",
        draftRevision = 1L,
        generationStatus = GenerationStatus.Ready(sourceRevision = 1L),
        generatedAudio = GeneratedAudioUi("preview", 1L, 18_000L, 1L, isTextSynchronized = true),
        nextOccurrenceSummary = "Tomorrow at 07:00",
        repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        configuredEnabled = true,
    ),
    darkTheme = true,
)

@Preview(name = "Text generating", group = "Editor", showBackground = true)
@Composable
private fun AlarmEditorTextGeneratingPreview() = AlarmEditorScreenPreviewContent(
    AlarmEditorUiState(
        messageDraft = "A new reason to wake up",
        draftRevision = 2L,
        generationStatus = GenerationStatus.Generating(sourceRevision = 2L),
        generatedAudio = GeneratedAudioUi("previous", 1L, 4_224L, 935L),
        audioSourceText = "The previous reason",
        selectedAudioProvenance = AudioAssetProvenance.GENERATED_TEXT,
        nextOccurrenceSummary = "Tomorrow at 07:00",
    ),
)

@Preview(name = "Voice ready", group = "Editor", showBackground = true)
@Composable
private fun AlarmEditorVoiceReadyPreview() = AlarmEditorScreenPreviewContent(
    AlarmEditorUiState(
        audioCreationMode = AudioCreationMode.VOICE,
        label = "Morning note",
        generatedAudio = GeneratedAudioUi("voice", 0L, 12_300L, 0L, isTextSynchronized = false),
        selectedAudioProvenance = AudioAssetProvenance.RECORDED_VOICE,
        nextOccurrenceSummary = "Tomorrow at 07:00",
    ),
)

@Preview(name = "Voice error", group = "Editor", showBackground = true)
@Composable
private fun AlarmEditorVoiceErrorPreview() = AlarmEditorScreenPreviewContent(
    AlarmEditorUiState(
        audioCreationMode = AudioCreationMode.VOICE,
        recordingState = RecordingState.PermissionPermanentlyDenied,
        nextOccurrenceSummary = "Tomorrow at 07:00",
    ),
)

@Composable
private fun AlarmEditorScreenPreviewContent(
    uiState: AlarmEditorUiState,
    darkTheme: Boolean = false,
) {
    WakeUpSamuraiTheme(darkTheme = darkTheme) {
        AlarmEditorScreen(
            uiState = uiState,
            onAudioCreationModeChanged = {},
            onMessageDraftChanged = {},
            onTryPhrase = {},
            onConfirmSuggestionReplacement = {},
            onDismissSuggestionReplacement = {},
            onConfirmAudioCreationModeChange = {},
            onDismissAudioCreationModeChange = {},
            onGenerateAudio = {},
            onCancelGeneration = {},
            onRetryGeneration = {},
            onTogglePreview = {},
            onRecordVoiceRequested = {},
            onMicrophonePermissionResult = { _, _ -> },
            onStopRecording = {},
            onToggleRecordingPreview = {},
            onKeepRecording = {},
            onDiscardRecording = {},
            onChooseSavedAudio = {},
            onLabelChanged = {},
            onTimeChanged = { _, _ -> },
            onRepeatDayToggled = {},
            onConfiguredEnabledChanged = {},
            onNotificationPermissionResult = {},
            onOpenAlarmChannelSettings = {},
            onRestoreAudioText = {},
            onSave = {},
            onDeleteRequested = {},
            onConfirmDelete = {},
            onDismissDelete = {},
            onConfirmDiscard = {},
            onDismissDiscard = {},
            onBack = {},
            themeMode = AppThemeMode.SYSTEM,
            onCycleThemeMode = {},
        )
    }
}

@Preview(name = "Empty text audio", group = "AudioCard", showBackground = true, widthDp = 380)
@Composable
private fun AudioCardEmptyPreview() = AudioCardPreview(
    AlarmEditorUiState(messageDraft = "Pancakes before the city wakes"),
)

@Preview(name = "Generating replacement", group = "AudioCard", showBackground = true, widthDp = 380)
@Composable
private fun AudioCardGeneratingPreview() = AudioCardPreview(
    AlarmEditorUiState(
        messageDraft = "A new reason to wake up",
        draftRevision = 2L,
        generationStatus = GenerationStatus.Generating(sourceRevision = 2L),
        generatedAudio = GeneratedAudioUi("old-audio", 1L, 8_400L, 1_100L),
        audioSourceText = "The previous reason",
    ),
)

@Preview(name = "Outdated audio", group = "AudioCard", showBackground = true, widthDp = 380)
@Composable
private fun AudioCardOutdatedPreview() = AudioCardPreview(
    AlarmEditorUiState(
        messageDraft = "Edited wake-up message",
        draftRevision = 2L,
        generationStatus = GenerationStatus.Ready(sourceRevision = 1L),
        generatedAudio = GeneratedAudioUi("ready-audio", 1L, 12_650L, 820L),
        audioSourceText = "Original wake-up message",
    ),
)

@Preview(name = "Generation error", group = "AudioCard", showBackground = true, widthDp = 380)
@Composable
private fun AudioCardErrorPreview() = AudioCardPreview(
    AlarmEditorUiState(
        messageDraft = "Wake up for a quiet hour",
        draftRevision = 1L,
        generationStatus = GenerationStatus.Error("The speech service is temporarily unavailable.", 1L),
    ),
)

@Preview(name = "Voice review actions", group = "AudioCard", showBackground = true, widthDp = 380)
@Composable
private fun AudioCardVoicePreview() = AudioCardPreview(
    AlarmEditorUiState(
        audioCreationMode = AudioCreationMode.VOICE,
        recordingState = RecordingState.Reviewing(durationMillis = 15_300L),
        isPreviewPlaying = true,
    ),
)

@Preview(name = "Playback error", group = "AudioCard", showBackground = true, widthDp = 380)
@Composable
private fun AudioCardPlaybackErrorPreview() = AudioCardPreview(
    AlarmEditorUiState(
        messageDraft = "Wake up for a quiet hour",
        draftRevision = 1L,
        generationStatus = GenerationStatus.Ready(sourceRevision = 1L),
        generatedAudio = GeneratedAudioUi("ready-audio", 1L, 12_650L, 820L),
        audioSourceText = "Wake up for a quiet hour",
        previewErrorMessage = "Couldn't play this audio. Try again.",
    ),
)

@Composable
private fun AudioCardPreview(uiState: AlarmEditorUiState) {
    WakeUpSamuraiTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            AudioCard(
                uiState = uiState,
                onGenerateAudio = {},
                onCancelGeneration = {},
                onRetryGeneration = {},
                onTogglePreview = {},
                onStartRecording = {},
                onStopRecording = {},
                onToggleRecordingPreview = {},
                onKeepRecording = {},
                onRecordAgain = {},
                onDiscardRecording = {},
                onChooseSavedAudio = {},
                onRestoreAudioText = {},
                onOpenAppSettings = {},
            )
        }
    }
}

@Preview(name = "One-time disabled", group = "ScheduleCard", showBackground = true, widthDp = 380)
@Composable
private fun ScheduleCardOneTimePreview() = ScheduleCardPreview(
    AlarmEditorUiState(
        hour = 7,
        minute = 0,
        nextOccurrenceSummary = "Tomorrow at 07:00",
    ),
)

@Preview(name = "Weekly scheduled", group = "ScheduleCard", showBackground = true, widthDp = 380)
@Composable
private fun ScheduleCardWeeklyPreview() = ScheduleCardPreview(
    AlarmEditorUiState(
        hour = 6,
        minute = 45,
        repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        nextOccurrenceSummary = "Wed, 21 Oct at 06:45",
        configuredEnabled = true,
        hasPendingRegularOccurrence = true,
    ),
)

@Preview(name = "Access required", group = "ScheduleCard", showBackground = true, widthDp = 380)
@Composable
private fun ScheduleCardAccessPreview() = ScheduleCardPreview(
    AlarmEditorUiState(
        hour = 8,
        minute = 30,
        repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        nextOccurrenceSummary = "Sat, 24 Oct at 08:30",
        configuredEnabled = true,
        validationMessage = "Allow alarms & reminders to schedule this alarm.",
        schedulingAccessRequest = SchedulingAccessRequest.EXACT_ALARM,
    ),
)

@Composable
private fun ScheduleCardPreview(uiState: AlarmEditorUiState) {
    WakeUpSamuraiTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            ScheduleCard(
                uiState = uiState,
                locale = Locale.ENGLISH,
                onChooseTime = {},
                onRepeatDayToggled = {},
                onConfiguredEnabledChanged = {},
                onResolveAccess = {},
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
