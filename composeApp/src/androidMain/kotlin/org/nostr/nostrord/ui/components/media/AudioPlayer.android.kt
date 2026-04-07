package org.nostr.nostrord.ui.components.media

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual class AudioPlayer actual constructor() {
    private var mediaPlayer: MediaPlayer? = null
    private var currentUrl: String? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionMs = MutableStateFlow(0L)
    actual val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    actual val durationMs: StateFlow<Long> = _durationMs

    actual fun play(url: String) {
        if (url != currentUrl) {
            mediaPlayer?.release()
            currentUrl = url
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { mp ->
                    _durationMs.value = mp.duration.toLong()
                    mp.start()
                    _isPlaying.value = true
                    startPositionTracking()
                }
                setOnCompletionListener {
                    _isPlaying.value = false
                    _currentPositionMs.value = _durationMs.value
                    positionJob?.cancel()
                }
                setOnErrorListener { _, _, _ ->
                    _isPlaying.value = false
                    positionJob?.cancel()
                    true
                }
                prepareAsync()
            }
        } else {
            mediaPlayer?.let { mp ->
                if (!mp.isPlaying) {
                    mp.start()
                    _isPlaying.value = true
                    startPositionTracking()
                }
            }
        }
    }

    actual fun pause() {
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                _isPlaying.value = false
                positionJob?.cancel()
            }
        }
    }

    actual fun stop() {
        positionJob?.cancel()
        mediaPlayer?.let { mp ->
            mp.stop()
            mp.reset()
        }
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        currentUrl = null
    }

    actual fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        _currentPositionMs.value = positionMs
    }

    actual fun release() {
        positionJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        currentUrl = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    try {
                        if (mp.isPlaying) {
                            _currentPositionMs.value = mp.currentPosition.toLong()
                        }
                    } catch (_: IllegalStateException) {}
                }
                delay(250)
            }
        }
    }
}

@Composable
actual fun rememberAudioPlayer(): AudioPlayer {
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    return player
}
