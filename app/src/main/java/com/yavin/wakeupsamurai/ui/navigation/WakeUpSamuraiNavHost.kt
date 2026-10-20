package com.yavin.wakeupsamurai.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.yavin.wakeupsamurai.ui.components.AlarmAmbientBackground
import com.yavin.wakeupsamurai.alarm.RingingNotificationChannel
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import java.time.Duration
import java.time.Instant

@Composable
fun WakeUpSamuraiNavHost(
    alarmListViewModel: AlarmListViewModel,
    alarmEditorViewModel: AlarmEditorViewModel,
    audioLibraryViewModel: AudioLibraryViewModel,
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(AlarmListKey)
    val context = LocalContext.current
    val alarmListUiState by alarmListViewModel.uiState.collectAsStateWithLifecycle()
    val nearestOccurrence = alarmListUiState.nearestScheduledOccurrenceAt

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AlarmAmbientBackground(
            outerProgress = nearestOccurrence?.let {
                alarmCountdownProgress(alarmListUiState.currentTime, it)
            },
            outerProgressKey = nearestOccurrence?.toEpochMilli(),
            modifier = Modifier.fillMaxSize(),
        )
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
        ) { outerPadding ->
            NavDisplay(
                backStack = backStack,
                modifier = Modifier
                    .padding(outerPadding)
                    .consumeWindowInsets(outerPadding),
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
                AlarmListScreen(
                    uiState = alarmListUiState,
                    onAddAlarm = dropUnlessResumed {
                        backStack.pushEditorIfAbsentOnTop(AlarmEditorKey())
                    },
                    onOpenAlarm = { id -> backStack.pushEditorIfAbsentOnTop(AlarmEditorKey(id)) },
                    onEnabledChanged = alarmListViewModel::setConfiguredEnabled,
                    onCancelSnooze = alarmListViewModel::cancelSnooze,
                    onManageAudio = dropUnlessResumed { backStack.pushAudioLibraryIfAbsentOnTop() },
                    onNotificationPermissionResult = alarmListViewModel::onNotificationPermissionResult,
                    onOpenAlarmChannelSettings = {
                        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, RingingNotificationChannel.ID)
                        })
                    },
                    themeMode = themeMode,
                    onCycleThemeMode = onCycleThemeMode,
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
                    onAudioCreationModeChanged = alarmEditorViewModel::onAudioCreationModeChanged,
                    onMessageDraftChanged = alarmEditorViewModel::onMessageDraftChanged,
                    onTryPhrase = alarmEditorViewModel::onTryPhrase,
                    onConfirmSuggestionReplacement = alarmEditorViewModel::onConfirmSuggestionReplacement,
                    onDismissSuggestionReplacement = alarmEditorViewModel::onDismissSuggestionReplacement,
                    onConfirmAudioCreationModeChange = alarmEditorViewModel::onConfirmAudioCreationModeChange,
                    onDismissAudioCreationModeChange = alarmEditorViewModel::onDismissAudioCreationModeChange,
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
                    onOpenAlarmChannelSettings = {
                        context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            putExtra(Settings.EXTRA_CHANNEL_ID, RingingNotificationChannel.ID)
                        })
                    },
                    onRestoreAudioText = alarmEditorViewModel::onRestoreAudioText,
                    onSave = alarmEditorViewModel::onSave,
                    onDeleteRequested = alarmEditorViewModel::onDeleteRequested,
                    onConfirmDelete = alarmEditorViewModel::onConfirmDelete,
                    onDismissDelete = alarmEditorViewModel::onDismissDelete,
                    onConfirmDiscard = alarmEditorViewModel::onConfirmDiscard,
                    onDismissDiscard = alarmEditorViewModel::onDismissDiscard,
                    onBack = alarmEditorViewModel::onBackRequested,
                    themeMode = themeMode,
                    onCycleThemeMode = onCycleThemeMode,
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
                    themeMode = themeMode,
                    onCycleThemeMode = onCycleThemeMode,
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
                    themeMode = themeMode,
                    onCycleThemeMode = onCycleThemeMode,
                )
                    }
                },
            )
        }
    }
}

private fun alarmCountdownProgress(now: Instant, target: Instant): Float? {
    val remainingMillis = Duration.between(now, target).toMillis()
    if (remainingMillis !in 0..COUNTDOWN_WINDOW_MILLIS) return null
    return 1f - remainingMillis.toFloat() / COUNTDOWN_WINDOW_MILLIS
}

private const val COUNTDOWN_WINDOW_MILLIS = 24 * 60 * 60 * 1_000L
