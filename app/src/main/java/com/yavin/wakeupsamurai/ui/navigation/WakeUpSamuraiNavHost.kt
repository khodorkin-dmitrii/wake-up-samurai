package com.yavin.wakeupsamurai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.yavin.wakeupsamurai.ui.alarmeditor.AlarmEditorScreen
import com.yavin.wakeupsamurai.ui.alarmeditor.AlarmEditorViewModel
import com.yavin.wakeupsamurai.ui.alarmlist.AlarmListScreen
import com.yavin.wakeupsamurai.ui.alarmlist.AlarmListViewModel
import com.yavin.wakeupsamurai.ui.savedaudio.SavedAudioPickerScreen
import com.yavin.wakeupsamurai.ui.audiolibrary.AudioLibraryScreen
import com.yavin.wakeupsamurai.ui.audiolibrary.AudioLibraryViewModel

@Composable
fun WakeUpSamuraiNavHost(
    alarmListViewModel: AlarmListViewModel,
    alarmEditorViewModel: AlarmEditorViewModel,
    audioLibraryViewModel: AudioLibraryViewModel,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(AlarmListKey)

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = {
            when (backStack.lastOrNull()) {
                is SavedAudioPickerKey -> {
                    alarmEditorViewModel.onLeaveSavedAudioPicker()
                    backStack.popIfTop(SavedAudioPickerKey)
                }
                is AudioLibraryKey -> backStack.popIfTop(AudioLibraryKey)
                else -> alarmEditorViewModel.onBackRequested()
            }
        },
        entryProvider = entryProvider {
            entry<AlarmListKey> {
                val uiState by alarmListViewModel.uiState.collectAsStateWithLifecycle()
                AlarmListScreen(
                    uiState = uiState,
                    onAddAlarm = dropUnlessResumed {
                        backStack.pushEditorIfAbsentOnTop(AlarmEditorKey())
                    },
                    onOpenAlarm = { id -> backStack.pushEditorIfAbsentOnTop(AlarmEditorKey(id)) },
                    onEnabledChanged = alarmListViewModel::setConfiguredEnabled,
                    onManageAudio = dropUnlessResumed { backStack.pushAudioLibraryIfAbsentOnTop() },
                    onNotificationPermissionResult = alarmListViewModel::onNotificationPermissionResult,
                )
            }
            entry<AlarmEditorKey> { key ->
                val uiState by alarmEditorViewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(key.alarmId) {
                    alarmEditorViewModel.startSession(key.alarmId)
                }
                LaunchedEffect(key) {
                    alarmEditorViewModel.closeEvents.collect {
                        backStack.popIfTop(key)
                    }
                }
                DisposableEffect(alarmEditorViewModel) {
                    onDispose(alarmEditorViewModel::onLeaveEditor)
                }
                AlarmEditorScreen(
                    uiState = uiState,
                    onMessageDraftChanged = alarmEditorViewModel::onMessageDraftChanged,
                    onTryPhrase = alarmEditorViewModel::onTryPhrase,
                    onConfirmSuggestionReplacement = alarmEditorViewModel::onConfirmSuggestionReplacement,
                    onDismissSuggestionReplacement = alarmEditorViewModel::onDismissSuggestionReplacement,
                    onGenerateAudio = alarmEditorViewModel::onGenerateAudio,
                    onCancelGeneration = alarmEditorViewModel::onCancelGeneration,
                    onRetryGeneration = alarmEditorViewModel::onRetryGeneration,
                    onTogglePreview = alarmEditorViewModel::onTogglePreview,
                    onRecordVoiceRequested = alarmEditorViewModel::onRecordVoiceRequested,
                    onMicrophonePermissionResult = alarmEditorViewModel::onMicrophonePermissionResult,
                    onStopRecording = alarmEditorViewModel::onStopRecording,
                    onToggleRecordingPreview = alarmEditorViewModel::onToggleRecordingPreview,
                    onKeepRecording = alarmEditorViewModel::onKeepRecording,
                    onDiscardRecording = alarmEditorViewModel::onDiscardRecording,
                    onChooseSavedAudio = dropUnlessResumed {
                        backStack.pushSavedAudioPickerIfAbsentOnTop()
                    },
                    onLabelChanged = alarmEditorViewModel::onLabelChanged,
                    onTimeChanged = alarmEditorViewModel::onTimeChanged,
                    onRepeatDayToggled = alarmEditorViewModel::onRepeatDayToggled,
                    onConfiguredEnabledChanged = alarmEditorViewModel::onConfiguredEnabledChanged,
                    onNotificationPermissionResult = alarmEditorViewModel::onNotificationPermissionResult,
                    onRestoreAudioText = alarmEditorViewModel::onRestoreAudioText,
                    onSave = alarmEditorViewModel::onSave,
                    onDeleteRequested = alarmEditorViewModel::onDeleteRequested,
                    onConfirmDelete = alarmEditorViewModel::onConfirmDelete,
                    onDismissDelete = alarmEditorViewModel::onDismissDelete,
                    onConfirmDiscard = alarmEditorViewModel::onConfirmDiscard,
                    onDismissDiscard = alarmEditorViewModel::onDismissDiscard,
                    onBack = alarmEditorViewModel::onBackRequested,
                )
            }
            entry<SavedAudioPickerKey> {
                val uiState by alarmEditorViewModel.savedAudioPickerUiState.collectAsStateWithLifecycle()
                DisposableEffect(alarmEditorViewModel) {
                    onDispose(alarmEditorViewModel::onLeaveSavedAudioPicker)
                }
                SavedAudioPickerScreen(
                    uiState = uiState,
                    onPreview = alarmEditorViewModel::onToggleSavedAudioPreview,
                    onChoose = { assetId ->
                        if (alarmEditorViewModel.onSelectSavedAudio(assetId)) {
                            backStack.popIfTop(SavedAudioPickerKey)
                        }
                    },
                    onBack = {
                        alarmEditorViewModel.onLeaveSavedAudioPicker()
                        backStack.popIfTop(SavedAudioPickerKey)
                    },
                )
            }
            entry<AudioLibraryKey> {
                val uiState by audioLibraryViewModel.uiState.collectAsStateWithLifecycle()
                DisposableEffect(audioLibraryViewModel) { onDispose(audioLibraryViewModel::onLeave) }
                AudioLibraryScreen(
                    uiState = uiState,
                    onPreview = audioLibraryViewModel::onTogglePreview,
                    onDeleteRequested = audioLibraryViewModel::onDeleteRequested,
                    onDismissDelete = audioLibraryViewModel::onDismissDelete,
                    onConfirmDelete = audioLibraryViewModel::onConfirmDelete,
                    onCleanupRequested = audioLibraryViewModel::onCleanupRequested,
                    onDismissCleanup = audioLibraryViewModel::onDismissCleanup,
                    onConfirmCleanup = audioLibraryViewModel::onConfirmCleanup,
                    onBack = { backStack.popIfTop(AudioLibraryKey) },
                )
            }
        },
    )
}
