package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * JVM Desktop: delegates to AnimatedImage which does frame-by-frame decode
 * via ImageIO (GIF) or Skia Codec (WebP). Coil on JVM only shows the first
 * frame of animated images, so we bypass it entirely.
 *
 * The URL is passed directly — AnimatedImage.jvm fetches via HttpURLConnection
 * without needing CDN proxy (which can 404 on some hosts like betterttv).
 */
@Composable
actual fun EmojiImage(
    url: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onError: () -> Unit
) {
    AnimatedImage(
        url = url,
        modifier = modifier,
        contentScale = contentScale,
        onError = onError
    )
}
