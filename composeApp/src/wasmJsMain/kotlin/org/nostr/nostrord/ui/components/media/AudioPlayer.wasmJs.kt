@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// JS interop functions for HTML5 Audio
@JsFun("(url) => { const a = new Audio(url); a.preload = 'metadata'; return a; }")
private external fun jsCreateAudio(url: String): JsAny

@JsFun("(audio) => audio.play().catch(() => {})")
private external fun jsPlay(audio: JsAny)

@JsFun("(audio) => audio.pause()")
private external fun jsPause(audio: JsAny)

@JsFun("(audio) => audio.duration || 0")
private external fun jsGetDuration(audio: JsAny): Double

@JsFun("(audio) => audio.currentTime || 0")
private external fun jsGetCurrentTime(audio: JsAny): Double

@JsFun("(audio, time) => { audio.currentTime = time; }")
private external fun jsSetCurrentTime(audio: JsAny, time: Double)

@JsFun("(audio) => !audio.paused")
private external fun jsIsPlaying(audio: JsAny): Boolean

@JsFun("(audio, callback) => { audio.onloadedmetadata = () => callback(); }")
private external fun jsOnLoadedMetadata(audio: JsAny, callback: () -> Unit)

@JsFun("(audio, callback) => { audio.onended = () => callback(); }")
private external fun jsOnEnded(audio: JsAny, callback: () -> Unit)

actual class AudioPlayer actual constructor() {
    private var audio: JsAny? = null
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
            audio?.let { jsPause(it) }
            currentUrl = url
            val a = jsCreateAudio(url)
            audio = a
            jsOnLoadedMetadata(a) {
                val dur = jsGetDuration(a)
                if (!dur.isNaN() && dur.isFinite()) {
                    _durationMs.value = (dur * 1000).toLong()
                }
            }
            jsOnEnded(a) {
                _isPlaying.value = false
                _currentPositionMs.value = _durationMs.value
                positionJob?.cancel()
            }
            jsPlay(a)
            _isPlaying.value = true
            startPositionTracking()
        } else {
            audio?.let { a ->
                jsPlay(a)
                _isPlaying.value = true
                startPositionTracking()
            }
        }
    }

    actual fun pause() {
        audio?.let { jsPause(it) }
        _isPlaying.value = false
        positionJob?.cancel()
    }

    actual fun stop() {
        positionJob?.cancel()
        audio?.let { a ->
            jsPause(a)
            jsSetCurrentTime(a, 0.0)
        }
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        currentUrl = null
    }

    actual fun seekTo(positionMs: Long) {
        audio?.let { jsSetCurrentTime(it, positionMs / 1000.0) }
        _currentPositionMs.value = positionMs
    }

    actual fun release() {
        positionJob?.cancel()
        audio?.let { jsPause(it) }
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
                    _currentPositionMs.value = (jsGetCurrentTime(a) * 1000).toLong()
                    val dur = jsGetDuration(a)
                    if (!dur.isNaN() && dur.isFinite() && dur > 0) {
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
