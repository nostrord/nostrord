package org.nostr.nostrord.network.upload

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow

internal object ShareMediaQueue {
    val pending = MutableStateFlow<List<Uri>>(emptyList())
    fun offer(uris: List<Uri>) { pending.value = uris }
    fun consume(): List<Uri> = pending.value.also { pending.value = emptyList() }
}

@Composable
actual fun ShareMediaEffect(
    onMediaPasted: (ByteArray, String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val currentCallback = rememberUpdatedState(onMediaPasted)
    val currentOnError  = rememberUpdatedState(onError)

    LaunchedEffect(Unit) {
        ShareMediaQueue.pending.collect { uris ->
            if (uris.isEmpty()) return@collect
            val toProcess = ShareMediaQueue.consume()
            for (uri in toProcess) {
                try {
                    val mimeType = context.contentResolver.getType(uri) ?: continue
                    if (!isSupportedUploadMime(mimeType)) {
                        currentOnError.value("This file type is not supported.\n\n$SUPPORTED_FORMATS_MESSAGE")
                        continue
                    }
                    val size = context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    if (size != null && size > MAX_UPLOAD_BYTES) {
                        currentOnError.value("This file is too large. The maximum upload size is 20 MB.")
                        continue
                    }
                    val name = context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: "shared_media"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: continue
                    currentCallback.value(bytes, name)
                } catch (_: Throwable) {}
            }
        }
    }
}
