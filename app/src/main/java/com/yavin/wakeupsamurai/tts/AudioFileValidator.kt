package com.yavin.wakeupsamurai.tts

import android.media.MediaMetadataRetriever
import java.io.File
import javax.inject.Inject

class AudioFileValidator @Inject constructor() {
    fun validate(file: File): Long {
        if (!file.isFile || file.length() < MINIMUM_AUDIO_BYTES) {
            throw SpeechGenerationException("The service returned an empty audio file.")
        }

        val hasMp3Header = file.inputStream().buffered().use { input ->
            val prefix = ByteArray(HEADER_SCAN_BYTES)
            val count = input.read(prefix)
            count >= 3 && (
                prefix.copyOfRange(0, 3).contentEquals("ID3".encodeToByteArray()) ||
                    (0 until count - 1).any { index ->
                        prefix[index].toInt() and 0xFF == 0xFF &&
                            prefix[index + 1].toInt() and 0xE0 == 0xE0
                    }
                )
        }
        if (!hasMp3Header) {
            throw SpeechGenerationException("The service response is not a valid MP3 stream.")
        }

        return readableDuration(file, "The generated audio has no readable duration.")
    }

    /** Validates a local recording without applying the MP3-specific Edge response check. */
    fun validateRecording(file: File): Long {
        if (!file.isFile || file.length() < MINIMUM_AUDIO_BYTES) {
            throw SpeechGenerationException("The recording is too short or empty.")
        }
        return readableDuration(file, "The recording has no readable duration.")
            .takeIf { it >= MINIMUM_RECORDING_DURATION_MILLIS }
            ?: throw SpeechGenerationException("Record at least half a second before stopping.")
    }

    fun readableLocalDuration(file: File): Long? = runCatching {
        if (!file.isFile || file.length() < MINIMUM_AUDIO_BYTES) return@runCatching null
        readableDuration(file, "The audio file has no readable duration.")
    }.getOrNull()

    private fun readableDuration(file: File, errorMessage: String): Long {
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
        return duration?.takeIf { it > 0L } ?: throw SpeechGenerationException(errorMessage)
    }

    private companion object {
        const val MINIMUM_AUDIO_BYTES = 256L
        const val MINIMUM_RECORDING_DURATION_MILLIS = 500L
        const val HEADER_SCAN_BYTES = 4_096
    }
}
