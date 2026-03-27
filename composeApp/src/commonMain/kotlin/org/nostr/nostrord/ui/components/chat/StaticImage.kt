package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Platform-specific static image renderer.
 *
 * Web (JS/WasmJS): uses the browser's createImageBitmap() API to decode images off the
 * JS main thread, eliminating the UI freeze that occurs when Skia decodes images inline.
 *
 * Other platforms (Android, JVM, iOS): delegates to Coil AsyncImage with crossfade and
 * disk/memory cache, identical to the previous ChatImage behaviour.
 *
 * [url]          The original (un-proxied) image URL.
 * [modifier]     Compose modifier (size, clip, etc.) applied to the image container.
 * [contentScale] How to scale the image within its bounds.
 * [onClick]      Invoked when the user taps/clicks the image.
 * [onError]      Invoked if the image fails to load (so the caller can show a fallback).
 */
@Composable
expect fun StaticImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onClick: () -> Unit = {},
    onError: () -> Unit = {}
)
