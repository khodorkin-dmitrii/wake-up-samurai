package com.yavin.wakeupsamurai.audio

import java.io.File
import kotlinx.coroutines.flow.StateFlow

interface AudioPreview {
    val state: StateFlow<AudioPreviewState>

    fun play(file: File)
    fun pause()
    fun stop()
    fun release()
}

data class AudioPreviewState(
    val isPlaying: Boolean = false,
    val errorMessage: String? = null,
)
