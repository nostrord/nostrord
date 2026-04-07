package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer as JfxMediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual class AudioPlayer actual constructor() {
    private var jfxPlayer: JfxMediaPlayer? = null
    private var currentUrl: String? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionMs = MutableStateFlow(0L)
    actual val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    actual val durationMs: StateFlow<Long> = _durationMs

    actual fun play(url: String) {
        if (!JfxHelper.isAvailable) return

        if (url != currentUrl) {
            jfxPlayer?.dispose()
            currentUrl = url
            try {
                val media = Media(url)
                jfxPlayer = JfxMediaPlayer(media).apply {
                    setOnReady {
                        _durationMs.value = totalDuration.toMillis().toLong()
                        play()
                        _isPlaying.value = true
                        startPositionTracking()
                    }
                    setOnEndOfMedia {
                        _isPlaying.value = false
                        _currentPositionMs.value = _durationMs.value
                        positionJob?.cancel()
                    }
                    setOnError {
                        _isPlaying.value = false
                        positionJob?.cancel()
                    }
                }
            } catch (_: Throwable) {
                _isPlaying.value = false
                currentUrl = null
            }
        } else {
            jfxPlayer?.let { mp ->
                mp.play()
                _isPlaying.value = true
                startPositionTracking()
            }
        }
    }

    actual fun pause() {
        jfxPlayer?.pause()
        _isPlaying.value = false
        positionJob?.cancel()
    }

    actual fun stop() {
        positionJob?.cancel()
        jfxPlayer?.stop()
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        currentUrl = null
    }

    actual fun seekTo(positionMs: Long) {
        jfxPlayer?.seek(Duration.millis(positionMs.toDouble()))
        _currentPositionMs.value = positionMs
    }

    actual fun release() {
        positionJob?.cancel()
        jfxPlayer?.dispose()
        jfxPlayer = null
        currentUrl = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                jfxPlayer?.let { mp ->
                    try {
                        _currentPositionMs.value = mp.currentTime.toMillis().toLong()
                    } catch (_: Throwable) {}
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
