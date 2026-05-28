package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific audio playback engine.
 *
 * Each platform provides its own implementation:
 * - Android: android.media.MediaPlayer
 * - JVM Desktop: javax.sound.sampled / javafx.media
 * - JS: HTML5 Audio API
 * - WasmJS: HTML5 Audio API
 */
expect class AudioPlayer() {
    /** Start or resume playback of the given URL. */
    fun play(url: String)

    /** Pause playback without releasing resources. */
    fun pause()

    /** Stop playback and reset position. */
    fun stop()

    /** Seek to position in milliseconds. */
    fun seekTo(positionMs: Long)

    /** Whether audio is currently playing. */
    val isPlaying: StateFlow<Boolean>

    /** Current playback position in milliseconds. */
    val currentPositionMs: StateFlow<Long>

    /** Total duration in milliseconds (0 if unknown). */
    val durationMs: StateFlow<Long>

    /** Release all resources. Call when the player is no longer needed. */
    fun release()
}

/**
 * Remember a single AudioPlayer instance scoped to the composition.
 * Automatically releases on disposal.
 */
@Composable
expect fun rememberAudioPlayer(): AudioPlayer
