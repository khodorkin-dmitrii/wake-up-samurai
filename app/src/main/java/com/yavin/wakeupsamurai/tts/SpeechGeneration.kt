package com.yavin.wakeupsamurai.tts

import java.io.File

interface SpeechGeneration {
    suspend fun generate(request: SpeechGenerationRequest): GeneratedSpeech
}

data class SpeechGenerationRequest(
    val text: String,
    val voice: String,
    val revision: Long,
)

data class GeneratedSpeech(
    val file: File,
    val durationMillis: Long,
    val sourceRevision: Long,
    val voice: String,
    val generationLatencyMillis: Long,
)

object SpikeVoices {
    const val ENGLISH = "en-US-EmmaMultilingualNeural"
    const val RUSSIAN = "ru-RU-SvetlanaNeural"

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    fun forText(text: String): String = if (cyrillic.containsMatchIn(text)) RUSSIAN else ENGLISH
}

class SpeechGenerationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
