package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class ClipboardImageReader {
    actual fun hasImage(): Boolean = false

    actual suspend fun read(): Pair<ByteArray, String>? = null
}

@Composable
actual fun rememberClipboardImageReader(): ClipboardImageReader = remember { ClipboardImageReader() }

@Composable
actual fun PasteMediaEffect(
    onMediaPasted: (ByteArray, String) -> Unit,
    onError: (String) -> Unit,
) {}
