package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Platform-specific renderer for custom emoji images (NIP-30).
 *
 * - JVM Desktop: uses AnimatedImage (frame-by-frame decode) because Coil only
 *   renders the first frame of GIF/WebP on JVM.
 * - Android/iOS: uses Coil AsyncImage which handles all formats natively.
 * - JS/WasmJS: uses Coil AsyncImage (browser handles animated formats on canvas).
 */
@Composable
expect fun EmojiImage(
    url: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onError: () -> Unit = {}
)
