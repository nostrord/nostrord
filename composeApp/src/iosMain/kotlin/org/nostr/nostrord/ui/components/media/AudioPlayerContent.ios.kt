package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS stub: renders the player chrome but has no playback engine yet (AVPlayer integration
 * is deferred, like the iOS video player). Tapping does nothing.
 */
@Composable
actual fun AudioPlayerContent(
    url: String,
    modifier: Modifier,
) {
    AudioPlayerChrome(
        isPlaying = false,
        currentMs = 0L,
        durationMs = 0L,
        fileName = audioFileName(url),
        onToggle = {},
        modifier = modifier,
    )
}
