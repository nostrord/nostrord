package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable

fun isSupportedMediaMime(mime: String): Boolean = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

internal class FileTooLargeException : Exception("This file is too large. The maximum upload size is 20 MB.")

internal class UnsupportedFileTypeException(
    ext: String,
) : Exception("\".$ext\" files are not supported.\n\n$SUPPORTED_FORMATS_MESSAGE")

expect class ClipboardImageReader {
    fun hasImage(): Boolean

    suspend fun read(): Pair<ByteArray, String>?
}

@Composable
expect fun rememberClipboardImageReader(): ClipboardImageReader

/**
 * Side-effect composable that listens for paste events containing media (image/video/audio).
 * On desktop/Android this is a no-op (handled via onPreviewKeyEvent + ClipboardImageReader).
 * On web it registers a DOM paste listener for the lifetime of the composition.
 */
@Composable
expect fun PasteMediaEffect(
    onMediaPasted: (ByteArray, String) -> Unit,
    onError: (String) -> Unit = {},
)
