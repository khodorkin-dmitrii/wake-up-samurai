package com.yavin.wakeupsamurai.ui.audiolibrary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.domain.alarm.AudioDeletionResult
import com.yavin.wakeupsamurai.domain.alarm.AudioLibraryRepository
import com.yavin.wakeupsamurai.domain.alarm.ManagedAudioAsset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AudioLibraryViewModel @Inject constructor(
    private val repository: AudioLibraryRepository,
    private val storage: AudioAssetFileStore,
    private val preview: AudioPreview,
) : ViewModel() {
    private var assetsById = emptyMap<String, ManagedAudioAsset>()
    private val _uiState = MutableStateFlow(AudioLibraryUiState())
    val uiState: StateFlow<AudioLibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(repository.observeManagedAssets(), preview.state) { assets, previewState -> assets to previewState }
                .collect { (assets, previewState) ->
                    assetsById = assets.associateBy { it.asset.id }
                    _uiState.update {
                        it.copy(
                            assets = assets.map(ManagedAudioAsset::toUi),
                            previewingAssetId = if (previewState.isPlaying) it.previewingAssetId else null,
                            previewError = previewState.errorMessage,
                        )
                    }
                }
        }
    }

    fun onTogglePreview(assetId: String) {
        val asset = assetsById[assetId] ?: return
        if (!asset.isAvailable) return
        val file = storage.resolve(asset.asset.relativePath) ?: return
        if (_uiState.value.previewingAssetId == assetId && _uiState.value.isPreviewing) preview.pause()
        else {
            preview.play(file)
            _uiState.update { it.copy(previewingAssetId = assetId, previewError = null) }
        }
    }

    fun onDeleteRequested(assetId: String) {
        if (_uiState.value.isDeleting) return
        _uiState.update { it.copy(pendingDeleteAssetId = assetId, message = null) }
    }

    fun onDismissDelete() = _uiState.update { it.copy(pendingDeleteAssetId = null) }

    fun onConfirmDelete() {
        val assetId = _uiState.value.pendingDeleteAssetId ?: return
        _uiState.update { it.copy(pendingDeleteAssetId = null, isDeleting = true) }
        viewModelScope.launch {
            val result = repository.delete(assetId)
            _uiState.update { it.copy(isDeleting = false, message = result.message()) }
        }
    }

    fun onCleanupRequested() {
        if (_uiState.value.isDeleting) return
        val candidates = _uiState.value.unusedAssets
        if (candidates.isEmpty()) {
            _uiState.update { it.copy(message = "There is no unused saved audio to clean up.") }
        } else {
            _uiState.update { it.copy(showCleanupConfirmation = true, message = null) }
        }
    }

    fun onDismissCleanup() = _uiState.update { it.copy(showCleanupConfirmation = false) }

    fun onConfirmCleanup() {
        val ids = _uiState.value.unusedAssets.map(ManagedAudioAssetUi::id)
        if (ids.isEmpty()) return
        _uiState.update { it.copy(showCleanupConfirmation = false, isDeleting = true) }
        viewModelScope.launch {
            var deleted = 0
            val issues = mutableListOf<String>()
            ids.forEach { id ->
                when (val result = repository.delete(id)) {
                    is AudioDeletionResult.Deleted -> deleted++
                    else -> issues += result.message()
                }
            }
            _uiState.update {
                it.copy(
                    isDeleting = false,
                    message = if (issues.isEmpty()) "Removed $deleted unused track(s)." else
                        "Removed $deleted track(s). ${issues.first()}",
                )
            }
        }
    }

    fun onLeave() = preview.stop()
}

data class AudioLibraryUiState(
    val assets: List<ManagedAudioAssetUi> = emptyList(),
    val previewingAssetId: String? = null,
    val previewError: String? = null,
    val pendingDeleteAssetId: String? = null,
    val showCleanupConfirmation: Boolean = false,
    val isDeleting: Boolean = false,
    val message: String? = null,
) {
    val isPreviewing: Boolean get() = previewingAssetId != null
    val unusedAssets: List<ManagedAudioAssetUi> get() = assets.filter { it.alarmUses.isEmpty() }
    val unusedSizeBytes: Long get() = unusedAssets.sumOf { it.fileSizeBytes ?: 0L }
}

data class ManagedAudioAssetUi(
    val id: String,
    val title: String,
    val durationMillis: Long?,
    val fileSizeBytes: Long?,
    val isAvailable: Boolean,
    val alarmUses: List<String>,
)

private fun ManagedAudioAsset.toUi() = ManagedAudioAssetUi(
    id = asset.id,
    title = asset.sourceText?.takeIf { it.isNotBlank() }
        ?: if (asset.relativePath.substringAfterLast('/').startsWith("voice-")) "Voice recording" else "Saved audio ${asset.id.take(8)}",
    durationMillis = asset.durationMillis,
    fileSizeBytes = fileSizeBytes,
    isAvailable = isAvailable,
    alarmUses = alarmUses.map { it.label },
)

private fun AudioDeletionResult.message(): String = when (this) {
    is AudioDeletionResult.Deleted -> if (fileWasMissing) "Removed the unavailable track from the library." else "Saved audio deleted."
    is AudioDeletionResult.Referenced -> "This track is used by: ${uses.joinToString { it.label }}."
    AudioDeletionResult.ActiveUse -> "This track is selected in the open alarm editor. Save or leave that draft first."
    is AudioDeletionResult.Failed -> message
}
