package com.yavin.wakeupsamurai.tts

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.yavin.wakeupsamurai.data.alarm.RoomAudioAssetRepository
import com.yavin.wakeupsamurai.data.audio.AudioAssetStorage
import com.yavin.wakeupsamurai.data.local.WakeUpSamuraiDatabase
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Network-dependent manual spike check; it is intentionally not part of JVM validation. */
@RunWith(AndroidJUnit4::class)
class EdgeSpeechGenerationDeviceTest {
    @Test
    fun generatesRussianMp3OnAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val generator = generator()

        val result = generator.generate(
            SpeechGenerationRequest(
                text = "Доброе утро. Пора вставать.",
                voice = SpikeVoices.RUSSIAN,
                revision = DEVICE_TEST_REVISION,
            ),
        )

        assertTrue(result.file.isFile)
        assertTrue(result.file.length() > 256L)
        assertTrue(result.durationMillis > 0L)
        assertEquals(SpikeVoices.RUSSIAN, result.voice)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "stream",
                    "EDGE_TTS_RUSSIAN latencyMs=${result.generationLatencyMillis} " +
                        "durationMs=${result.durationMillis} bytes=${result.file.length()}\n",
                )
            },
        )
    }

    @Test
    fun reportsFailureWithoutNetwork() = runBlocking {
        val generator = generator()
        val startedAt = android.os.SystemClock.elapsedRealtime()

        val failure = runCatching {
            generator.generate(
                SpeechGenerationRequest(
                    text = "Network failure probe",
                    voice = SpikeVoices.ENGLISH,
                    revision = 9_002L,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is SpeechGenerationException)
        sendMetric(
            "EDGE_TTS_OFFLINE failureMs=${android.os.SystemClock.elapsedRealtime() - startedAt} " +
                "type=${failure?.javaClass?.simpleName}",
        )
    }

    @Test
    fun cancellationCleansTemporaryFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val speechDirectory = File(context.filesDir, "speech")
        val generator = generator()
        val job = launch {
            generator.generate(
                SpeechGenerationRequest(
                    text = "Cancel this request",
                    voice = SpikeVoices.ENGLISH,
                    revision = 9_003L,
                ),
            )
        }

        yield()
        job.cancelAndJoin()

        assertTrue(speechDirectory.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    private fun generator(): EdgeSpeechGeneration {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val validator = AudioFileValidator()
        val storage = AudioAssetStorage(context, validator)
        val database = Room.inMemoryDatabaseBuilder(context, WakeUpSamuraiDatabase::class.java).build()
        return EdgeSpeechGeneration(
            context = context,
            client = OkHttpClient(),
            validator = validator,
            assetRepository = RoomAudioAssetRepository(database.audioAssetDao(), storage),
            assetStorage = storage,
        )
    }

    private fun sendMetric(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "$message\n") },
        )
    }

    private companion object {
        const val DEVICE_TEST_REVISION = 9_001L
    }
}
