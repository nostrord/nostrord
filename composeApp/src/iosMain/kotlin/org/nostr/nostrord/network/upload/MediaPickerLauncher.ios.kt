package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

actual class MediaPickerLauncher(private val doLaunch: () -> Unit) {
    actual fun launch() = doLaunch()
}

// iOS: file picker not yet implemented — users can paste image URLs directly.
@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher {
    return remember { MediaPickerLauncher { } }
}
