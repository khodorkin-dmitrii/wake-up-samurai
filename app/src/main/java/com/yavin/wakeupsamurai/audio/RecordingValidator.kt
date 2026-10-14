package com.yavin.wakeupsamurai.audio

import com.yavin.wakeupsamurai.tts.AudioFileValidator
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface RecordingValidator {
    suspend fun validate(file: File): Long
}

class LocalRecordingValidator @Inject constructor(
    private val audioFileValidator: AudioFileValidator,
) : RecordingValidator {
    override suspend fun validate(file: File): Long = withContext(Dispatchers.IO) {
        audioFileValidator.validateRecording(file)
    }
}
