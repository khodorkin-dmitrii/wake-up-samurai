package com.yavin.wakeupsamurai.ui.audiolibrary

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import com.yavin.wakeupsamurai.ui.components.ThemeModeAction
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLibraryScreen(
    uiState: AudioLibraryUiState,
    onPreview: (String) -> Unit,
    onDeleteRequested: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCleanupRequested: () -> Unit,
    onDismissCleanup: () -> Unit,
    onConfirmCleanup: () -> Unit,
    onBack: () -> Unit,
    themeMode: AppThemeMode,
    onCycleThemeMode: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = uiState.assets.firstOrNull { it.id == uiState.pendingDeleteAssetId }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Manage audio") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = onCleanupRequested, enabled = !uiState.isDeleting) {
                        Text("Clean up")
                    }
                    ThemeModeAction(themeMode = themeMode, onCycleThemeMode = onCycleThemeMode)
                },
            )
        },
    ) { innerPadding ->
        if (uiState.assets.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) { Text("No saved audio yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                uiState.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                items(uiState.assets, key = ManagedAudioAssetUi::id) { asset ->
                    AudioAssetCard(asset, uiState.previewingAssetId == asset.id, !uiState.isDeleting, { onPreview(asset.id) }, { onDeleteRequested(asset.id) })
                }
            }
        }
    }
    if (pending != null) AlertDialog(onDismissRequest = onDismissDelete, title = { Text("Delete saved audio?") }, text = { Text("This removes the local file and its library entry. This cannot be undone.") }, confirmButton = { TextButton(onClick = onConfirmDelete) { Text("Delete") } }, dismissButton = { TextButton(onClick = onDismissDelete) { Text("Cancel") } })
    if (uiState.showCleanupConfirmation) AlertDialog(onDismissRequest = onDismissCleanup, title = { Text("Clean up unused audio?") }, text = { Text("Delete ${uiState.unusedAssets.size} unreferenced track(s), ${formatBytes(uiState.unusedSizeBytes)} in total? Each track will be checked again before deletion.") }, confirmButton = { TextButton(onClick = onConfirmCleanup) { Text("Delete unused") } }, dismissButton = { TextButton(onClick = onDismissCleanup) { Text("Cancel") } })
}

@Composable
private fun AudioAssetCard(asset: ManagedAudioAssetUi, isPreviewing: Boolean, enabled: Boolean, onPreview: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(asset.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(asset.durationMillis?.let(::formatDuration), asset.fileSizeBytes?.let(::formatBytes)).joinToString(" · ").ifBlank { "Duration unavailable" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (asset.alarmUses.isEmpty()) Text("Not used by a saved alarm", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) else Text("Used by ${asset.alarmUses.size}: ${asset.alarmUses.joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (!asset.isAvailable) Text("Audio file unavailable", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPreview, enabled = enabled && asset.isAvailable, modifier = Modifier.weight(1f)) { Text(if (isPreviewing) "Pause" else "Preview") }
            OutlinedButton(onClick = onDelete, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Delete") }
        }
    } }
}

private fun formatDuration(durationMillis: Long): String = String.format(Locale.US, "%d:%02d", durationMillis / 60_000L, (durationMillis / 1_000L) % 60L)
private fun formatBytes(bytes: Long): String = if (bytes < 1_024L) "$bytes B" else String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
