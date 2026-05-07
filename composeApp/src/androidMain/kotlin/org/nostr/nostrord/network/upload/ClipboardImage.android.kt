package org.nostr.nostrord.network.upload

import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class ClipboardImageReader(private val context: Context) {
    private val cm = context.getSystemService(ClipboardManager::class.java)

    actual fun hasImage(): Boolean {
        val desc = cm?.primaryClipDescription ?: return false
        return desc.hasMimeType("image/*") ||
               desc.hasMimeType("video/*") ||
               desc.hasMimeType("audio/*")
    }

    actual suspend fun read(): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val clip = cm?.primaryClip ?: return@withContext null
        val item = clip.getItemAt(0) ?: return@withContext null
        val uri = item.uri ?: return@withContext null
        val mimeType = context.contentResolver.getType(uri) ?: return@withContext null
        if (!isSupportedMediaMime(mimeType)) return@withContext null
        val fileSize = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        if (fileSize != null && fileSize > MAX_UPLOAD_BYTES) throw FileTooLargeException()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext null
        val ext = mimeToExtension(mimeType)
        bytes to "clipboard.$ext"
    }
}

private fun mimeToExtension(mime: String): String = when (mime) {
    "image/jpeg"      -> "jpg"
    "image/png"       -> "png"
    "image/gif"       -> "gif"
    "image/webp"      -> "webp"
    "video/mp4"       -> "mp4"
    "video/quicktime" -> "mov"
    "audio/mpeg"      -> "mp3"
    "audio/ogg"       -> "ogg"
    "audio/wav"       -> "wav"
    "audio/flac"      -> "flac"
    "audio/mp4"       -> "m4a"
    "audio/aac"       -> "aac"
    else              -> mime.substringAfterLast('/')
}

@Composable
actual fun rememberClipboardImageReader(): ClipboardImageReader {
    val context = LocalContext.current
    return remember(context) { ClipboardImageReader(context) }
}

@Composable
actual fun PasteMediaEffect(onMediaPasted: (ByteArray, String) -> Unit, onError: (String) -> Unit) {}
