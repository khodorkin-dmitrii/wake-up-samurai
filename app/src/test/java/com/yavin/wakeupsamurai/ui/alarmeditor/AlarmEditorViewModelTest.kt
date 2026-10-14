package com.yavin.wakeupsamurai.ui.alarmeditor

import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.audio.AudioPreviewState
import com.yavin.wakeupsamurai.audio.VoiceRecorder
import com.yavin.wakeupsamurai.audio.RecordingValidator
import com.yavin.wakeupsamurai.tts.GeneratedSpeech
import com.yavin.wakeupsamurai.tts.SpeechGeneration
import com.yavin.wakeupsamurai.tts.SpeechGenerationRequest
import java.io.File
import java.nio.file.Files
import com.yavin.wakeupsamurai.data.audio.AudioAssetFileStore
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmWithAudio
import com.yavin.wakeupsamurai.domain.alarm.NextOccurrenceCalculator
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AudioAsset
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetRepository
import com.yavin.wakeupsamurai.domain.alarm.AudioAssetUsageRegistry
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var outputDirectory: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        outputDirectory = Files.createTempDirectory("editor-test").toFile()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        outputDirectory.deleteRecursively()
    }

    @Test
    fun repeatedGenerateTapsStartOnlyOneRequest() = runTest(dispatcher) {
        val pending = CompletableDeferred<GeneratedSpeech>()
        val generator = FakeSpeechGeneration { pending.await() }
        val viewModel = createViewModel(generator)

        viewModel.onMessageDraftChanged("Morning")
        viewModel.onGenerateAudio()
        viewModel.onGenerateAudio()
        runCurrent()

        assertEquals(1, generator.requests.size)
        assertTrue(viewModel.uiState.value.generationStatus is GenerationStatus.Generating)

        pending.complete(generatedSpeech(revision = 1L))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.generationStatus is GenerationStatus.Ready)
    }

    @Test
    fun editingDuringGenerationCancelsRequestAndPreventsStaleReadyState() = runTest(dispatcher) {
        val pending = CompletableDeferred<GeneratedSpeech>()
        val generator = FakeSpeechGeneration { pending.await() }
        val viewModel = createViewModel(generator)

        viewModel.onMessageDraftChanged("First")
        viewModel.onGenerateAudio()
        runCurrent()
        viewModel.onMessageDraftChanged("Second")
        pending.complete(generatedSpeech(revision = 1L))
        advanceUntilIdle()

        assertEquals("Second", viewModel.uiState.value.messageDraft)
        assertTrue(viewModel.uiState.value.generationStatus is GenerationStatus.Idle)
        assertNull(viewModel.uiState.value.generatedAudio)
    }

    @Test
    fun changingTextMarksExistingAudioOutdatedWithoutDeletingIt() = runTest(dispatcher) {
        val generated = generatedSpeech(revision = 1L)
        val viewModel = createViewModel(FakeSpeechGeneration { generated })

        viewModel.onMessageDraftChanged("First")
        viewModel.onGenerateAudio()
        advanceUntilIdle()
        viewModel.onMessageDraftChanged("Revised")

        assertTrue(viewModel.uiState.value.isGeneratedAudioOutdated)
        assertTrue(generated.file.exists())
        assertTrue(viewModel.uiState.value.generationStatus is GenerationStatus.Idle)
    }

    @Test
    fun replacingPreviewRetainsBothCompletedAssets() = runTest(dispatcher) {
        val outputs = mutableListOf<GeneratedSpeech>()
        val viewModel = createViewModel(
            FakeSpeechGeneration { request ->
                generatedSpeech(request.revision).also(outputs::add)
            },
        )

        viewModel.onMessageDraftChanged("First")
        viewModel.onGenerateAudio()
        advanceUntilIdle()
        viewModel.onMessageDraftChanged("Second")
        viewModel.onGenerateAudio()
        advanceUntilIdle()

        assertEquals(2, outputs.size)
        assertTrue(outputs.all { it.file.exists() })
        assertEquals(2L, viewModel.uiState.value.generatedAudio?.sourceRevision)
    }

    @Test
    fun suggestionRequiresConfirmationForNonEmptyDraft() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeSpeechGeneration { error("Not expected") })

        viewModel.onMessageDraftChanged("Keep me")
        viewModel.onTryPhrase()

        assertEquals("Keep me", viewModel.uiState.value.messageDraft)
        assertTrue(viewModel.uiState.value.showSuggestionReplacementConfirmation)

        viewModel.onConfirmSuggestionReplacement()
        assertEquals(AlarmEditorViewModel.SUGGESTED_PHRASE, viewModel.uiState.value.messageDraft)
        assertFalse(viewModel.uiState.value.showSuggestionReplacementConfirmation)
    }

    @Test
    fun retryAfterFailureUsesCurrentDraft() = runTest(dispatcher) {
        var attempt = 0
        val generator = FakeSpeechGeneration { request ->
            attempt++
            if (attempt == 1) error("offline") else generatedSpeech(request.revision)
        }
        val viewModel = createViewModel(generator)

        viewModel.onMessageDraftChanged("Try again")
        viewModel.onGenerateAudio()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.generationStatus is GenerationStatus.Error)

        viewModel.onRetryGeneration()
        advanceUntilIdle()
        assertEquals(2, generator.requests.size)
        assertTrue(viewModel.uiState.value.generationStatus is GenerationStatus.Ready)
    }

    @Test
    fun timeoutBecomesRetryableError() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeSpeechGeneration { awaitCancellation() })

        viewModel.onMessageDraftChanged("Slow request")
        viewModel.onGenerateAudio()
        runCurrent()
        advanceTimeBy(AlarmEditorViewModel.GENERATION_TIMEOUT_MILLIS + 1L)
        runCurrent()

        val status = viewModel.uiState.value.generationStatus
        assertTrue(status is GenerationStatus.Error)
        assertTrue((status as GenerationStatus.Error).message.contains("timed out"))
        assertTrue(viewModel.uiState.value.canGenerate)
    }

    @Test
    fun repeatedSaveCreatesOneDisabledDraftWithoutGenerating() = runTest(dispatcher) {
        val generator = FakeSpeechGeneration { error("Generation must not run while saving") }
        val repository = FakeAlarmRepository()
        val viewModel = createViewModel(generator, repository)
        viewModel.startSession(null)
        viewModel.onLabelChanged("Morning walk")

        viewModel.onSave()
        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(1, repository.saveCount)
        assertEquals(0, generator.requests.size)
        assertFalse(repository.values.value.single().alarm.configuredEnabled)
    }

    @Test
    fun configurationReentryKeepsUnsavedDraft() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeSpeechGeneration { error("Not expected") })
        viewModel.startSession(null)
        viewModel.onMessageDraftChanged("Keep across rotation")

        viewModel.onLeaveEditor()
        viewModel.startSession(null)

        assertEquals("Keep across rotation", viewModel.uiState.value.messageDraft)
        assertTrue(viewModel.uiState.value.isDirty)
    }

    @Test
    fun selectingSavedAudioKeepsDraftAndDoesNotMarkTrackOutdated() = runTest(dispatcher) {
        val savedFile = File(outputDirectory, "saved.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val asset = AudioAsset(
            id = "shared-asset",
            relativePath = savedFile.absolutePath,
            sourceText = "A different, saved message",
            voice = "test-voice",
            languageTag = "en-US",
            createdAt = Instant.EPOCH,
            durationMillis = 2_000L,
        )
        val viewModel = createViewModel(
            generator = FakeSpeechGeneration { error("Not expected") },
            audioAssets = FakeAudioAssetRepository(listOf(asset)),
        )
        advanceUntilIdle()
        viewModel.startSession(null)
        viewModel.onMessageDraftChanged("Keep this alarm draft")

        assertTrue(viewModel.onSelectSavedAudio(asset.id))

        assertEquals("Keep this alarm draft", viewModel.uiState.value.messageDraft)
        assertEquals(asset.id, viewModel.uiState.value.generatedAudio?.assetId)
        assertFalse(viewModel.uiState.value.generatedAudio?.isTextSynchronized ?: true)
        assertFalse(viewModel.uiState.value.isGeneratedAudioOutdated)
    }

    @Test
    fun leavingSavedAudioPickerDoesNotChangeCurrentSelection() = runTest(dispatcher) {
        val generated = generatedSpeech(revision = 1L)
        val viewModel = createViewModel(FakeSpeechGeneration { generated })
        viewModel.onMessageDraftChanged("Current")
        viewModel.onGenerateAudio()
        advanceUntilIdle()

        viewModel.onLeaveSavedAudioPicker()

        assertEquals(generated.assetId, viewModel.uiState.value.generatedAudio?.assetId)
    }

    @Test
    fun unavailableSavedAudioIsShownButCannotBeSelected() = runTest(dispatcher) {
        val asset = AudioAsset(
            id = "missing-asset",
            relativePath = File(outputDirectory, "missing.mp3").absolutePath,
            sourceText = null,
            voice = null,
            languageTag = null,
            createdAt = null,
            durationMillis = null,
        )
        val viewModel = createViewModel(
            generator = FakeSpeechGeneration { error("Not expected") },
            audioAssets = FakeAudioAssetRepository(listOf(asset)),
        )
        advanceUntilIdle()

        assertFalse(viewModel.savedAudioPickerUiState.value.assets.single().isAvailable)
        assertFalse(viewModel.onSelectSavedAudio(asset.id))
        assertNull(viewModel.uiState.value.generatedAudio)
    }

    @Test
    fun savingSavedAudioUsesSharedAssetReferenceWithoutGenerating() = runTest(dispatcher) {
        val savedFile = File(outputDirectory, "shared.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val asset = AudioAsset("shared", savedFile.absolutePath, null, null, null, null, 2_000L)
        val repository = FakeAlarmRepository()
        val generator = FakeSpeechGeneration { error("Generation must not run") }
        val viewModel = createViewModel(generator, repository, FakeAudioAssetRepository(listOf(asset)))
        advanceUntilIdle()
        viewModel.startSession(null)
        assertTrue(viewModel.onSelectSavedAudio(asset.id))

        viewModel.onSave()
        advanceUntilIdle()

        assertEquals(asset.id, repository.values.value.single().alarm.audioAssetId)
        assertEquals(
            com.yavin.wakeupsamurai.domain.alarm.AudioSelectionKind.SAVED_ASSET,
            repository.values.value.single().alarm.audioSelectionKind,
        )
        assertEquals(0, generator.requests.size)
    }

    @Test
    fun keepingRecordingSelectsNewAssetWithoutReplacingUntilKeep() = runTest(dispatcher) {
        val old = generatedSpeech(revision = 1L)
        val recorder = FakeVoiceRecorder(outputDirectory)
        val assets = FakeAudioAssetRepository()
        val viewModel = createViewModel(
            generator = FakeSpeechGeneration { old },
            audioAssets = assets,
            voiceRecorder = recorder,
        )
        viewModel.onMessageDraftChanged("Existing")
        viewModel.onGenerateAudio()
        advanceUntilIdle()

        viewModel.onRecordVoiceRequested()
        viewModel.onMicrophonePermissionResult(granted = true, permanentlyDenied = false)
        viewModel.onStopRecording()
        advanceUntilIdle()

        assertEquals(old.assetId, viewModel.uiState.value.generatedAudio?.assetId)
        assertTrue(viewModel.uiState.value.recordingState is RecordingState.Reviewing)

        viewModel.onKeepRecording()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
        assertTrue(viewModel.uiState.value.generatedAudio?.assetId != old.assetId)
        assertEquals(1, assets.registered.size)
        assertTrue(assets.registered.single().relativePath.contains("voice-"))
    }

    @Test
    fun discardingRecordingPreservesPreviousSelectedAsset() = runTest(dispatcher) {
        val old = generatedSpeech(revision = 1L)
        val recorder = FakeVoiceRecorder(outputDirectory)
        val viewModel = createViewModel(
            generator = FakeSpeechGeneration { old },
            voiceRecorder = recorder,
        )
        viewModel.onMessageDraftChanged("Existing")
        viewModel.onGenerateAudio()
        advanceUntilIdle()
        viewModel.onRecordVoiceRequested()
        viewModel.onMicrophonePermissionResult(granted = true, permanentlyDenied = false)

        viewModel.onDiscardRecording()

        assertEquals(old.assetId, viewModel.uiState.value.generatedAudio?.assetId)
        assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
        assertTrue(recorder.discardCount > 0)
    }

    @Test
    fun permanentMicrophoneDenialKeepsRecordingIdleAndShowsSettingsState() = runTest(dispatcher) {
        val viewModel = createViewModel(FakeSpeechGeneration { error("Not expected") })

        viewModel.onRecordVoiceRequested()
        viewModel.onMicrophonePermissionResult(granted = false, permanentlyDenied = true)

        assertTrue(viewModel.uiState.value.recordingState is RecordingState.PermissionPermanentlyDenied)
    }

    @Test
    fun backgroundingDiscardsUnfinishedRecording() = runTest(dispatcher) {
        val recorder = FakeVoiceRecorder(outputDirectory)
        val viewModel = createViewModel(
            generator = FakeSpeechGeneration { error("Not expected") },
            voiceRecorder = recorder,
        )
        viewModel.onRecordVoiceRequested()
        viewModel.onMicrophonePermissionResult(granted = true, permanentlyDenied = false)

        viewModel.onHostBackgrounded()

        assertTrue(viewModel.uiState.value.recordingState is RecordingState.Idle)
        assertTrue(recorder.discardCount > 0)
    }

    private fun generatedSpeech(revision: Long): GeneratedSpeech {
        val file = File(outputDirectory, "audio-$revision-${System.nanoTime()}.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        return GeneratedSpeech(
            assetId = "asset-$revision-${System.nanoTime()}",
            file = file,
            durationMillis = 2_000L,
            sourceRevision = revision,
            voice = "test-voice",
            generationLatencyMillis = 100L,
        )
    }

    private fun createViewModel(
        generator: SpeechGeneration,
        repository: FakeAlarmRepository = FakeAlarmRepository(),
        audioAssets: FakeAudioAssetRepository = FakeAudioAssetRepository(),
        voiceRecorder: VoiceRecorder = FakeVoiceRecorder(outputDirectory),
    ): AlarmEditorViewModel = AlarmEditorViewModel(
        generator,
        FakeAudioPreview(),
        voiceRecorder,
        FakeRecordingValidator(),
        repository,
        audioAssets,
        FakeAudioAssetUsageRegistry(),
        FakeFileStore(),
        NextOccurrenceCalculator(),
        object : TimeProvider {
            override fun now(): Instant = Instant.parse("2026-10-13T06:00:00Z")
            override fun zoneId(): ZoneId = ZoneId.of("UTC")
        },
    )
}

private class FakeVoiceRecorder(
    private val directory: File,
) : VoiceRecorder {
    private var temporary: File? = null
    var discardCount = 0

    override fun start(): File = File(directory, "recording-temp-${System.nanoTime()}.m4a").also {
        it.writeBytes(byteArrayOf(1, 2, 3))
        temporary = it
    }

    override fun stop(): File = requireNotNull(temporary)

    override fun promote(recording: File): File = File(directory, "voice-${System.nanoTime()}.m4a").also {
        recording.renameTo(it)
        temporary = null
    }

    override fun discard() {
        discardCount++
        temporary?.delete()
        temporary = null
    }
}

private class FakeRecordingValidator : RecordingValidator {
    override suspend fun validate(file: File): Long = 2_000L
}

private class FakeSpeechGeneration(
    private val result: suspend (SpeechGenerationRequest) -> GeneratedSpeech,
) : SpeechGeneration {
    val requests = mutableListOf<SpeechGenerationRequest>()

    override suspend fun generate(request: SpeechGenerationRequest): GeneratedSpeech {
        requests += request
        return result(request)
    }
}

private class FakeAudioPreview : AudioPreview {
    override val state: StateFlow<AudioPreviewState> = MutableStateFlow(AudioPreviewState())

    override fun play(file: File) = Unit
    override fun pause() = Unit
    override fun stop() = Unit
    override fun release() = Unit
}

private class FakeAlarmRepository : AlarmRepository {
    val values = MutableStateFlow<List<AlarmWithAudio>>(emptyList())
    var saveCount = 0
    override fun observeAll() = values
    override suspend fun get(id: String): AlarmWithAudio? = values.value.firstOrNull { it.alarm.id == id }
    override suspend fun save(alarm: Alarm) {
        saveCount++
        values.value = values.value.filterNot { it.alarm.id == alarm.id } + AlarmWithAudio(alarm, null)
    }
    override suspend fun delete(id: String) { values.value = values.value.filterNot { it.alarm.id == id } }
}

private class FakeAudioAssetRepository(
    assets: List<AudioAsset> = emptyList(),
) : AudioAssetRepository {
    private val values = MutableStateFlow(assets)
    val registered = mutableListOf<AudioAsset>()
    override fun observeAll() = values
    override suspend fun get(id: String): AudioAsset? = values.value.firstOrNull { it.id == id }
    override suspend fun register(asset: AudioAsset): AudioAsset {
        registered += asset
        values.value = values.value.filterNot { it.id == asset.id } + asset
        return asset
    }
    override suspend fun importExistingCompletedFiles() = Unit
}

private class FakeFileStore : AudioAssetFileStore {
    override fun relativePath(file: File) = file.name
    override fun resolve(relativePath: String) = File(relativePath)
    override fun isReadable(relativePath: String) = File(relativePath).isFile
    override fun completedFiles() = emptyList<File>()
    override suspend fun validatedDuration(file: File) = 2_000L
}

private class FakeAudioAssetUsageRegistry : AudioAssetUsageRegistry {
    private var assetId: String? = null
    override fun setEditorDraftAsset(assetId: String?) { this.assetId = assetId }
    override fun isInUse(assetId: String): Boolean = this.assetId == assetId
    override suspend fun <T> withWriteLock(block: suspend () -> T): T = block()
}
