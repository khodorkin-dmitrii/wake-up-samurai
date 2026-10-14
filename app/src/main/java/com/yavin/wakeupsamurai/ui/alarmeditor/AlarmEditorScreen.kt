package com.yavin.wakeupsamurai.ui.alarmeditor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import java.util.Locale
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditorScreen(
    uiState: AlarmEditorUiState,
    onMessageDraftChanged: (String) -> Unit,
    onTryPhrase: () -> Unit,
    onConfirmSuggestionReplacement: () -> Unit,
    onDismissSuggestionReplacement: () -> Unit,
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
    onRestoreAudioText: () -> Unit,
    onSave: () -> Unit,
    onDeleteRequested: () -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onDismissDiscard: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permanentlyDenied = !granted &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
        onMicrophonePermissionResult(granted, permanentlyDenied)
    }
    val locale = LocalConfiguration.current.locales[0]
    var showTimePicker by remember { mutableStateOf(false) }

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
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        ) { TimePicker(state = pickerState) }
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
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("Cancel") } },
        )
    }

    if (uiState.showSuggestionReplacementConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissSuggestionReplacement,
            title = { Text(stringResource(R.string.suggestion_replace_title)) },
            text = { Text(stringResource(R.string.suggestion_replace_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmSuggestionReplacement) {
                    Text(stringResource(R.string.replace))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissSuggestionReplacement) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            focusManager.clearFocus()
                            onBack()
                        },
                    ) {
                        Text(stringResource(R.string.back))
                    }
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
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.label,
                onValueChange = onLabelChanged,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Label (optional)") },
            )
            OutlinedTextField(
                value = uiState.messageDraft,
                onValueChange = onMessageDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 10,
                label = { Text(stringResource(R.string.editor_title)) },
                placeholder = { Text(stringResource(R.string.editor_hint)) },
                supportingText = { Text(stringResource(R.string.draft_notice)) },
            )

            TextButton(onClick = onTryPhrase) {
                Text(stringResource(R.string.try_phrase))
            }

            Text(
                text = stringResource(R.string.generation_online_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val status = uiState.generationStatus) {
                GenerationStatus.Idle,
                is GenerationStatus.Ready,
                -> Button(
                    onClick = {
                        focusManager.clearFocus()
                        onGenerateAudio()
                    },
                    enabled = uiState.canGenerate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.generate_audio))
                }

                is GenerationStatus.Generating -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.generation_generating))
                            OutlinedButton(onClick = onCancelGeneration) {
                                Text(stringResource(R.string.cancel_generation))
                            }
                        }
                    }
                }

                is GenerationStatus.Error -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.generation_error, status.message),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = onRetryGeneration) {
                                Text(stringResource(R.string.retry_generation))
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = onChooseSavedAudio, modifier = Modifier.fillMaxWidth()) {
                Text("Choose saved audio")
            }

            when (val recording = uiState.recordingState) {
                RecordingState.Idle,
                RecordingState.PermissionDenied,
                is RecordingState.Error,
                -> {
                    if (recording is RecordingState.PermissionDenied) {
                        Text(
                            "Microphone access was denied. Allow it to record a voice message.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (recording is RecordingState.Error) {
                        Text(recording.message, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            onRecordVoiceRequested()
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                onMicrophonePermissionResult(true, false)
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Record voice") }
                }

                RecordingState.AwaitingPermission -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text("Waiting for microphone permission…", modifier = Modifier.padding(16.dp))
                    }
                }

                RecordingState.PermissionPermanentlyDenied -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Microphone access is unavailable. Enable it in app settings to record a voice message.")
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        },
                                    )
                                },
                            ) { Text("Open app settings") }
                        }
                    }
                }

                is RecordingState.Recording -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Recording ${formatDuration(recording.elapsedMillis)}", style = MaterialTheme.typography.titleMedium)
                            Text("Recording stays in this editor only until you choose Keep.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onStopRecording) { Text("Stop") }
                                OutlinedButton(onClick = onDiscardRecording) { Text("Cancel") }
                            }
                        }
                    }
                }

                is RecordingState.Reviewing -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Voice recording ready", style = MaterialTheme.typography.titleMedium)
                            Text("Duration: ${formatDuration(recording.durationMillis)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onToggleRecordingPreview) {
                                    Text(if (uiState.isPreviewPlaying) "Pause" else "Play")
                                }
                                Button(onClick = onKeepRecording) { Text("Keep recording") }
                            }
                            OutlinedButton(onClick = onDiscardRecording) { Text("Discard / record again") }
                        }
                    }
                }
            }

            uiState.generatedAudio?.let { audio ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.generatedAudio.isTextSynchronized && uiState.isGeneratedAudioOutdated) R.string.generation_outdated
                                else if (uiState.generatedAudio.isTextSynchronized) R.string.generation_ready
                                else R.string.saved_audio_selected
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (uiState.generatedAudio.isTextSynchronized && uiState.isGeneratedAudioOutdated) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (audio.durationMillis > 0L) {
                            Text(
                                stringResource(
                                    R.string.audio_duration,
                                    formatDuration(audio.durationMillis),
                                ),
                            )
                        } else {
                            Text("Duration unavailable")
                        }
                        if (audio.isTextSynchronized) {
                            Text(
                                stringResource(
                                    R.string.generation_latency,
                                    String.format(
                                        Locale.US,
                                        "%.1f",
                                        audio.generationLatencyMillis / 1_000.0,
                                    ),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = onTogglePreview) {
                            Text(
                                stringResource(
                                    if (uiState.isPreviewPlaying) R.string.pause_audio
                                    else R.string.play_audio,
                                ),
                            )
                        }
                        if (uiState.canRestoreAudioText) {
                            OutlinedButton(onClick = onRestoreAudioText) {
                                Text("Use this audio's original text")
                            }
                        }
                        uiState.previewErrorMessage?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Text("Schedule", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(onClick = { showTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text(String.format(Locale.US, "%02d:%02d", uiState.hour, uiState.minute))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in uiState.repeatDays,
                        onClick = { onRepeatDayToggled(day) },
                        label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                    )
                }
            }
            Text(
                if (uiState.repeatDays.isEmpty()) "One occurrence" else "Repeats weekly",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Next: ${uiState.nextOccurrenceSummary}")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enabled intent")
                    Text(
                        "Scheduling is not implemented; this alarm will not ring yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Switch(checked = uiState.configuredEnabled, onCheckedChange = onConfiguredEnabledChanged)
            }
            uiState.validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = onSave,
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (uiState.isSaving) "Saving…" else "Save alarm") }
            if (uiState.hasPersistedAlarm) {
                OutlinedButton(onClick = onDeleteRequested, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete alarm")
                }
            }
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

@Preview(name = "Light", showBackground = true)
@Preview(
    name = "Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AlarmEditorScreenPreview() {
    WakeUpSamuraiTheme {
        AlarmEditorScreen(
            uiState = AlarmEditorUiState(
                messageDraft = "Wake up (wake up)\nGrab a brush and put a little make-up",
                draftRevision = 1L,
                generationStatus = GenerationStatus.Ready(sourceRevision = 1L),
                generatedAudio = GeneratedAudioUi(
                    assetId = "preview",
                    sourceRevision = 1L,
                    durationMillis = 4_224L,
                    generationLatencyMillis = 935L,
                ),
            ),
            onMessageDraftChanged = {},
            onTryPhrase = {},
            onConfirmSuggestionReplacement = {},
            onDismissSuggestionReplacement = {},
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
            onRestoreAudioText = {},
            onSave = {},
            onDeleteRequested = {},
            onConfirmDelete = {},
            onDismissDelete = {},
            onConfirmDiscard = {},
            onDismissDiscard = {},
            onBack = {},
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
