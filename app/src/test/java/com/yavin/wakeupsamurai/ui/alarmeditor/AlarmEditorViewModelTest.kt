package com.yavin.wakeupsamurai.ui.alarmeditor

import com.yavin.wakeupsamurai.audio.AudioPreview
import com.yavin.wakeupsamurai.audio.AudioPreviewState
import com.yavin.wakeupsamurai.tts.GeneratedSpeech
import com.yavin.wakeupsamurai.tts.SpeechGeneration
import com.yavin.wakeupsamurai.tts.SpeechGenerationRequest
import java.io.File
import java.nio.file.Files
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
        val viewModel = AlarmEditorViewModel(generator, FakeAudioPreview())

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
        val viewModel = AlarmEditorViewModel(generator, FakeAudioPreview())

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
        val viewModel = AlarmEditorViewModel(
            FakeSpeechGeneration { generated },
            FakeAudioPreview(),
        )

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
        val viewModel = AlarmEditorViewModel(
            FakeSpeechGeneration { request ->
                generatedSpeech(request.revision).also(outputs::add)
            },
            FakeAudioPreview(),
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
        val viewModel = AlarmEditorViewModel(
            FakeSpeechGeneration { error("Not expected") },
            FakeAudioPreview(),
        )

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
        val viewModel = AlarmEditorViewModel(generator, FakeAudioPreview())

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
        val viewModel = AlarmEditorViewModel(
            FakeSpeechGeneration { awaitCancellation() },
            FakeAudioPreview(),
        )

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

    private fun generatedSpeech(revision: Long): GeneratedSpeech {
        val file = File(outputDirectory, "audio-$revision-${System.nanoTime()}.mp3").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        return GeneratedSpeech(
            file = file,
            durationMillis = 2_000L,
            sourceRevision = revision,
            voice = "test-voice",
            generationLatencyMillis = 100L,
        )
    }
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
