package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable

enum class MediaAccept {
    /** Only still images: jpg, png, gif, webp, avif */
    Images,

    /** Images + video (mp4, mov, webm) + audio (mp3, ogg, wav, flac, m4a, aac, opus) */
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
    onPickStart: () -> Unit = {},
    onError: (String) -> Unit = {},
    onFilePicked: (ByteArray, String) -> Unit,
): MediaPickerLauncher
