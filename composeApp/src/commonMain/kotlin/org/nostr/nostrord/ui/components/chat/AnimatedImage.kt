package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Platform-specific renderer for animated images (GIF and animated WebP).
 *
 * - Android:  delegates to Coil's AsyncImage — the registered AnimatedImageDecoder /
 *             GifDecoder (from coil-gif) handles GIF and animated WebP natively.
 * - JVM:      downloads bytes and decodes frame-by-frame via ImageIO (GIF) or Skia
 *             Codec (WebP), then drives a Compose timer to cycle frames.
 * - iOS/Web:  delegates to Coil's AsyncImage (best-effort; Skia may animate on iOS).
 *
 * [url]          The original (un-proxied) image URL.
 * [modifier]     Standard Compose modifier applied to the image container. Size constraints
 *                and clipping belong here — callers provide them so the same composable
 *                works for both chat thumbnails (400×300 max) and the full-screen viewer.
 * [contentScale] How to scale the image within its bounds. Use [ContentScale.FillWidth]
 *                for chat thumbnails and [ContentScale.Fit] for the full-screen viewer.
 * [onClick]      Invoked when the user taps/clicks the image.
 */
@Composable
expect fun AnimatedImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
    onClick: () -> Unit = {}
)
