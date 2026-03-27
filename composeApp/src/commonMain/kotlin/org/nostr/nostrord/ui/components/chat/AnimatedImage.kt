package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/** When true, web AnimatedImage hides its HTML overlay (used when ImageViewerModal is open). */
val LocalAnimatedImageHidden = compositionLocalOf { false }

/**
 * Platform-specific renderer for animated images (GIF and animated WebP).
 *
 * - Android:  Coil AsyncImage with AnimatedImageDecoder / GifDecoder.
 * - JVM:      frame-by-frame decode via ImageIO (GIF) or Skia Codec (WebP).
 * - JS/WasmJS: native HTML <img> via WebElementView (browser handles animation).
 * - iOS:      Coil AsyncImage (best-effort).
 */
@Composable
expect fun AnimatedImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    onClick: () -> Unit = {}
)
