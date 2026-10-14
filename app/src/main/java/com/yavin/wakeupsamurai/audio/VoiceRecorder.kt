package com.yavin.wakeupsamurai.audio

import java.io.File

/** Owns the one foreground microphone recording session used by the editor. */
interface VoiceRecorder {
    fun start(): File
    fun stop(): File
    fun promote(recording: File): File
    fun discard()
}
