package com.yavin.wakeupsamurai.ui.audiolibrary

import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.audio.AudioPreviewState
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.domain.alarm.AudioAsset
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetAlarmUse
import com.yavin.wakeupsamurai.domain.alarm.AudioDeletionResult
import com.yavin.wakeupsamurai.domain.alarm.AudioLibraryRepository
import com.yavin.wakeupsamurai.domain.alarm.ManagedAudioAsset
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun referencedAssetIsProtectedAfterConfirmedDelete() = runTest(dispatcher) {
        val use = AudioAssetAlarmUse("alarm", "Morning walk")
        val repository = FakeLibrary(
            listOf(asset("used", uses = listOf(use))),
            results = mapOf("used" to AudioDeletionResult.Referenced(listOf(use))),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onDeleteRequested("used")
        viewModel.onConfirmDelete()
        advanceUntilIdle()

        assertEquals(listOf("used"), repository.deleteCalls)
        assertTrue(viewModel.uiState.value.assets.any { it.id == "used" })
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("Morning walk"))
    }

    @Test
    fun activeDraftAssetIsProtectedAfterConfirmedDelete() = runTest(dispatcher) {
        val repository = FakeLibrary(listOf(asset("draft")), results = mapOf("draft" to AudioDeletionResult.ActiveUse))
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onDeleteRequested("draft")
        viewModel.onConfirmDelete()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.assets.any { it.id == "draft" })
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("open alarm editor"))
    }

    @Test
    fun confirmedUnusedDeletionRemovesAsset() = runTest(dispatcher) {
        val repository = FakeLibrary(listOf(asset("unused")))
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onDeleteRequested("unused")
        viewModel.onConfirmDelete()
        advanceUntilIdle()

        assertTrue(repository.deleteCalls.contains("unused"))
        assertTrue(viewModel.uiState.value.assets.isEmpty())
    }

    @Test
    fun cleanupReportsPartialFailureAndKeepsFailedAsset() = runTest(dispatcher) {
        val repository = FakeLibrary(
            assets = listOf(asset("first"), asset("second")),
            results = mapOf("second" to AudioDeletionResult.Failed("Could not delete the audio file. Try again.")),
        )
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.onCleanupRequested()
        assertTrue(viewModel.uiState.value.showCleanupConfirmation)
        viewModel.onConfirmCleanup()
        advanceUntilIdle()

        assertEquals(listOf("first", "second"), repository.deleteCalls)
        assertEquals(listOf("second"), viewModel.uiState.value.assets.map { it.id })
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("Removed 1 track"))
        assertTrue(viewModel.uiState.value.message.orEmpty().contains("Could not delete"))
        assertFalse(viewModel.uiState.value.isDeleting)
    }

    private fun viewModel(repository: FakeLibrary) = AudioLibraryViewModel(repository, FakeStore(), FakePreview())
    private fun asset(id: String, uses: List<AudioAssetAlarmUse> = emptyList()) = ManagedAudioAsset(
        asset = AudioAsset(id, "$id.mp3", "A small message", null, null, null, 1_000L),
        alarmUses = uses,
        fileSizeBytes = 64L,
        isAvailable = true,
    )
}

private class FakeLibrary(
    assets: List<ManagedAudioAsset>,
    private val results: Map<String, AudioDeletionResult> = emptyMap(),
) : AudioLibraryRepository {
    private val values = MutableStateFlow(assets)
    val deleteCalls = mutableListOf<String>()
    override fun observeManagedAssets() = values
    override suspend fun delete(assetId: String): AudioDeletionResult {
        deleteCalls += assetId
        val result = results[assetId] ?: AudioDeletionResult.Deleted(assetId, fileWasMissing = false)
        if (result is AudioDeletionResult.Deleted) values.value = values.value.filterNot { it.asset.id == assetId }
        return result
    }
}

private class FakeStore : AudioAssetFileStore {
    override fun relativePath(file: File): String = file.name
    override fun resolve(relativePath: String): File = File(relativePath)
    override fun isReadable(relativePath: String): Boolean = true
    override fun completedFiles(): List<File> = emptyList()
    override suspend fun validatedDuration(file: File): Long? = null
}

private class FakePreview : AudioPreview {
    override val state: StateFlow<AudioPreviewState> = MutableStateFlow(AudioPreviewState())
    override fun play(file: File) = Unit
    override fun pause() = Unit
    override fun stop() = Unit
    override fun release() = Unit
}
