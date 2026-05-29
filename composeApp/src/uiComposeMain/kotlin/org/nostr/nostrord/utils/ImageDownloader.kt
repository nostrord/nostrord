package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

/** True on platforms that can save an image to the device (gallery / downloads folder). */
expect val supportsImageDownload: Boolean

/**
 * Returns a suspend callback that saves image [bytes] to the device under [fileName] with
 * [mimeType] (e.g. "image/png") and returns whether it succeeded. The call suspends for the
 * duration of the save (so callers can show a progress indicator) and surfaces its own user
 * feedback (Toast / system message) per platform.
 */
@Composable
expect fun rememberImageDownloader(): suspend (bytes: ByteArray, fileName: String, mimeType: String) -> Boolean
