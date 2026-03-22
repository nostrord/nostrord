package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable

enum class MediaAccept {
    /** Only still images: jpg, png, gif, webp */
    Images,
    /** Images + video + audio */
    ImagesVideosAudio,
}

/**
 * Platform-specific file picker launcher.
 * Call [launch] to open the native file/image picker.
 * [onFilePicked] is called with (bytes, filename) when the user selects a file.
 */
expect class MediaPickerLauncher {
    fun launch()
}

@Composable
expect fun rememberMediaPickerLauncher(
    accept: MediaAccept = MediaAccept.Images,
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher
