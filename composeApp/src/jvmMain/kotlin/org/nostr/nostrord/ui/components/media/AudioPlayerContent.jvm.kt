package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

/**
 * Desktop audio uses the same kdroidfilter player as the inline video (system GStreamer on
 * Linux, MediaFoundation on Windows, AVPlayer on macOS), so it shares the full system codec
 * set. The previous JavaFX-media engine had a crippled codec set on Linux and played little
 * correctly. No video surface is rendered; an audio-only URL plays through the audio sink.
 */
@Composable
actual fun AudioPlayerContent(
    url: String,
    modifier: Modifier,
) {
    val state = rememberVideoPlayerState()

    // Open paused so duration shows before the user hits play; the player loads metadata.
    LaunchedEffect(url) {
        state.openUri(url, InitialPlayerState.PAUSE)
    }
    // The library doesn't stop on composition exit; pause so leaving the chat releases audio.
    DisposableEffect(url) {
        onDispose { state.pause() }
    }

    val durationMs = state.metadata.duration ?: 0L
    val currentMs = (state.currentTime * 1000).toLong()

    AudioPlayerChrome(
        isPlaying = state.isPlaying,
        currentMs = currentMs,
        durationMs = durationMs,
        fileName = audioFileName(url),
        onToggle = {
            if (state.isPlaying) {
                state.pause()
            } else {
                // At end-of-media currentTime sits at the end; rewind so play replays instead
                // of doing nothing (slider runs 0..1000).
                val d = state.metadata.duration ?: 0L
                val c = (state.currentTime * 1000).toLong()
                if (d > 0 && c >= d - 250) state.seekTo(0f)
                state.play()
            }
        },
        modifier = modifier,
    )
}
