package org.nostr.nostrord.ui.components.media

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
import org.w3c.dom.Audio

actual class AudioPlayer actual constructor() {
    private var audio: Audio? = null
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
        if (url != currentUrl) {
            audio?.pause()
            currentUrl = url
            audio = Audio(url).apply {
                onloadedmetadata = {
                    _durationMs.value = (duration * 1000).toLong()
                    null
                }
                onended = {
                    _isPlaying.value = false
                    _currentPositionMs.value = _durationMs.value
                    positionJob?.cancel()
                    null
                }
                onerror = { _, _, _, _, _ ->
                    _isPlaying.value = false
                    positionJob?.cancel()
                    null
                }
                play()
            }
            _isPlaying.value = true
            startPositionTracking()
        } else {
            audio?.let { a ->
                a.play()
                _isPlaying.value = true
                startPositionTracking()
            }
        }
    }

    actual fun pause() {
        audio?.pause()
        _isPlaying.value = false
        positionJob?.cancel()
    }

    actual fun stop() {
        positionJob?.cancel()
        audio?.let { a ->
            a.pause()
            a.currentTime = 0.0
        }
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        currentUrl = null
    }

    actual fun seekTo(positionMs: Long) {
        audio?.currentTime = positionMs / 1000.0
        _currentPositionMs.value = positionMs
    }

    actual fun release() {
        positionJob?.cancel()
        audio?.pause()
        audio = null
        currentUrl = null
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    private fun startPositionTracking() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                audio?.let { a ->
                    _currentPositionMs.value = (a.currentTime * 1000).toLong()
                    // Update duration if it wasn't available during loadedmetadata
                    val dur = a.duration
                    if (!dur.isNaN() && dur.isFinite()) {
                        _durationMs.value = (dur * 1000).toLong()
                    }
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
