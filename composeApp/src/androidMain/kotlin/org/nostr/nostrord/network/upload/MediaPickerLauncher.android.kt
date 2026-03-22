package org.nostr.nostrord.network.upload

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

actual class MediaPickerLauncher(private val doLaunch: () -> Unit) {
    actual fun launch() = doLaunch()
}

@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher {
    val context = LocalContext.current
    val currentCallback = rememberUpdatedState(onFilePicked)
    val mimeTypes = when (accept) {
        MediaAccept.Images            -> arrayOf("image/*")
        MediaAccept.ImagesVideosAudio -> arrayOf("image/*", "video/mp4", "video/quicktime", "audio/*")
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "upload.jpg"

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) currentCallback.value(bytes, name)
        }
    }
    return remember(launcher) {
        MediaPickerLauncher { launcher.launch(mimeTypes) }
    }
}
