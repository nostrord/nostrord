package org.nostr.nostrord.ui.components.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific inline video player.
 *
 * - JS/WasmJS: Renders an HTML5 `<video>` element overlaid on the canvas
 * - Android: Uses AndroidView with VideoView
 * - JVM Desktop / iOS: Shows a thumbnail preview; clicking opens externally
 *
 * @param url Direct video URL (mp4, webm, etc.)
 * @param thumbnailUrl Optional thumbnail/poster image URL
 * @param aspectRatio Width/height ratio (default 16:9). Use imeta dimensions when available.
 * @param onFallbackClick Called when the platform doesn't support inline playback
 *        and the user clicks the play button. Typically opens the URL externally.
 */
@Composable
expect fun PlatformVideoPlayer(
    url: String,
    thumbnailUrl: String?,
    aspectRatio: Float,
    onFallbackClick: () -> Unit,
    modifier: Modifier
)
