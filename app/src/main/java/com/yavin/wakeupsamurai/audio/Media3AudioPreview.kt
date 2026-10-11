package com.yavin.wakeupsamurai.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class Media3AudioPreview @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AudioPreview {
    private val _state = MutableStateFlow(AudioPreviewState())
    override val state: StateFlow<AudioPreviewState> = _state.asStateFlow()

    private var player: ExoPlayer? = null
    private var loadedFile: File? = null

    override fun play(file: File) {
        val currentPlayer = player ?: createPlayer().also { player = it }
        if (loadedFile != file) {
            currentPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            currentPlayer.prepare()
            loadedFile = file
        } else if (currentPlayer.playbackState == Player.STATE_ENDED) {
            currentPlayer.seekTo(0L)
        }
        currentPlayer.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        player?.stop()
        loadedFile = null
        _state.value = AudioPreviewState()
    }

    override fun release() {
        player?.release()
        player = null
        loadedFile = null
        _state.value = AudioPreviewState()
    }

    private fun createPlayer(): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(isPlaying = isPlaying, errorMessage = null)
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = AudioPreviewState(errorMessage = "This audio could not be played.")
                }
            },
        )
    }
}
