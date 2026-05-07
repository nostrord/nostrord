package org.nostr.nostrord.network.upload

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import org.nostr.nostrord.network.upload.MAX_UPLOAD_BYTES

actual class MediaPickerLauncher(private val doLaunch: () -> Unit) {
    actual fun launch() = doLaunch()
}

@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onPickStart: () -> Unit,
    onError: (String) -> Unit,
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher {
    val context = LocalContext.current
    val currentCallback = rememberUpdatedState(onFilePicked)
    val currentErrorCallback = rememberUpdatedState(onError)
    val currentPickStart = rememberUpdatedState(onPickStart)
    val mimeTypes = when (accept) {
        MediaAccept.Images            -> arrayOf("image/*")
        MediaAccept.ImagesVideosAudio -> arrayOf("image/*", "video/mp4", "video/quicktime", "video/webm", "audio/*")
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType == null || !isSupportedUploadMime(mimeType)) {
                currentErrorCallback.value("This file type is not supported.\n\n$SUPPORTED_FORMATS_MESSAGE")
                return@rememberLauncherForActivityResult
            }
            currentPickStart.value()
            val size = context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0) cursor.getLong(idx) else null
                    } else null
                }
            if (size != null && size > MAX_UPLOAD_BYTES) {
                currentErrorCallback.value("File is too large. The maximum upload size is 20 MB.")
                return@rememberLauncherForActivityResult
            }

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
