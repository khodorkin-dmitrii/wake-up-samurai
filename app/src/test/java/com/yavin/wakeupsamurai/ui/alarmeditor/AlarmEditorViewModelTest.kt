package com.yavin.wakeupsamurai.ui.alarmeditor

import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.audio.AudioPreviewState
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
    ): AlarmEditorViewModel = AlarmEditorViewModel(
        generator,
        FakeAudioPreview(),
        repository,
        FakeFileStore(),
        NextOccurrenceCalculator(),
        object : TimeProvider {
            override fun now(): Instant = Instant.parse("2026-10-13T06:00:00Z")
            override fun zoneId(): ZoneId = ZoneId.of("UTC")
        },
    )
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

private class FakeFileStore : AudioAssetFileStore {
    override fun relativePath(file: File) = file.name
    override fun resolve(relativePath: String) = File(relativePath)
    override fun isReadable(relativePath: String) = File(relativePath).isFile
    override fun completedFiles() = emptyList<File>()
    override suspend fun validatedDuration(file: File) = 2_000L
}
