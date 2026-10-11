package com.yavin.wakeupsamurai.ui.alarmeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yavin.wakeupsamurai.R
import java.util.Locale

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
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

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

            uiState.generatedAudio?.let { audio ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (uiState.isGeneratedAudioOutdated) R.string.generation_outdated
                                else R.string.generation_ready,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (uiState.isGeneratedAudioOutdated) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            stringResource(
                                R.string.audio_duration,
                                formatDuration(audio.durationMillis),
                            ),
                        )
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
                        Button(onClick = onTogglePreview) {
                            Text(
                                stringResource(
                                    if (uiState.isPreviewPlaying) R.string.pause_audio
                                    else R.string.play_audio,
                                ),
                            )
                        }
                        uiState.previewErrorMessage?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
