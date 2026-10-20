package com.yavin.wakeupsamurai.ui.savedaudio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.ui.alarmeditor.SavedAudioItemUi
import com.yavin.wakeupsamurai.ui.alarmeditor.SavedAudioPickerUiState
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import com.yavin.wakeupsamurai.ui.components.ThemeModeAction
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedAudioPickerScreen(
    uiState: SavedAudioPickerUiState,
    onPreview: (String) -> Unit,
    onChoose: (String) -> Unit,
    onBack: () -> Unit,
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Choose saved audio") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left_24),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    ThemeModeAction(themeMode = themeMode, onCycleThemeMode = onCycleThemeMode)
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("Loading saved audio…")
            }
        } else if (uiState.assets.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No saved audio yet. Generate a track in the editor, then you can reuse it here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(uiState.assets, key = SavedAudioItemUi::id) { asset ->
                    SavedAudioCard(
                        asset = asset,
                        isPreviewing = uiState.previewingAssetId == asset.id,
                        onPreview = { onPreview(asset.id) },
                        onChoose = { onChoose(asset.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedAudioCard(
    asset: SavedAudioItemUi,
    isPreviewing: Boolean,
    onPreview: () -> Unit,
    onChoose: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                asset.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                asset.durationMillis?.let(::formatDuration) ?: "Duration unavailable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            asset.unavailableReason?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPreview, enabled = asset.isAvailable, modifier = Modifier.weight(1f)) {
                    Text(if (isPreviewing) "Pause" else "Preview")
                }
                OutlinedButton(onClick = onChoose, enabled = asset.isAvailable, modifier = Modifier.weight(1f)) {
                    Text("Choose")
                }
            }
        }
    }
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000L
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
