package com.yavin.wakeupsamurai.audio

import android.content.Context
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRecorderVoiceRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var temporaryFile: File? = null

    override fun start(): File {
        check(recorder == null) { "A recording is already in progress." }
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val output = File(directory, "recording-${UUID.randomUUID()}.m4a")
        val newRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(output.absolutePath)
        }
        try {
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            temporaryFile = output
            return output
        } catch (exception: Exception) {
            newRecorder.release()
            output.delete()
            throw IOException("Could not start microphone recording.", exception)
        }
    }

    override fun stop(): File {
        val activeRecorder = recorder ?: throw IllegalStateException("No recording is in progress.")
        val output = temporaryFile ?: throw IllegalStateException("Recording output is missing.")
        try {
            activeRecorder.stop()
        } catch (exception: RuntimeException) {
            output.delete()
            throw IOException("The recording was too short or could not be finalized.", exception)
        } finally {
            activeRecorder.release()
            recorder = null
        }
        return output
    }

    override fun promote(recording: File): File {
        if (recording.name.startsWith("voice-") && recording.isFile) return recording
        check(recording == temporaryFile) { "Only the active temporary recording can be kept." }
        val completed = File(recording.parentFile, "voice-${UUID.randomUUID()}.m4a")
        if (!recording.renameTo(completed)) {
            throw IOException("Could not save the completed recording.")
        }
        temporaryFile = null
        return completed
    }

    override fun discard() {
        recorder?.runCatching { reset() }
        recorder?.release()
        recorder = null
        temporaryFile?.delete()
        temporaryFile = null
    }
}
