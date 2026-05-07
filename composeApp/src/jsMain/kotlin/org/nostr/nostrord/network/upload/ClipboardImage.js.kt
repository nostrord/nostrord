package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.events.Event
import org.w3c.files.FileReader

actual class ClipboardImageReader {
    actual fun hasImage(): Boolean = false
    actual suspend fun read(): Pair<ByteArray, String>? = null
}

@Composable
actual fun rememberClipboardImageReader(): ClipboardImageReader = remember { ClipboardImageReader() }

@Composable
actual fun PasteMediaEffect(onMediaPasted: (ByteArray, String) -> Unit, onError: (String) -> Unit) {
    val currentCallback = rememberUpdatedState(onMediaPasted)
    val currentOnError = rememberUpdatedState(onError)
    DisposableEffect(Unit) {
        val handler: (Event) -> Unit = handler@{ event ->
            val items = event.asDynamic().clipboardData?.items ?: return@handler
            val len = items.length as? Int ?: return@handler
            for (i in 0 until len) {
                val item = items[i] ?: continue
                if (item.kind as? String != "file") continue
                val type = item.type as? String ?: continue
                if (!isSupportedMediaMime(type)) continue
                val jsFile = item.getAsFile() ?: continue
                event.preventDefault()
                if (!isSupportedUploadMime(type)) {
                    currentOnError.value("This file type is not supported.\n\n$SUPPORTED_FORMATS_MESSAGE")
                    return@handler
                }
                val fileSize = jsFile.asDynamic().size as? Double ?: 0.0
                if (fileSize > MAX_UPLOAD_BYTES) {
                    currentOnError.value("This file is too large. The maximum upload size is 20 MB.")
                    return@handler
                }
                val reader = FileReader()
                reader.onload = {
                    runCatching {
                        val buffer = reader.result as ArrayBuffer
                        val int8 = Int8Array(buffer)
                        val bytes = ByteArray(int8.length).also { arr ->
                            for (j in arr.indices) arr[j] = int8.asDynamic()[j]
                        }
                        val ext = type.substringAfterLast('/')
                        val name = jsFile.asDynamic().name as? String ?: "clipboard.$ext"
                        currentCallback.value(bytes, name)
                    }
                }
                reader.asDynamic().readAsArrayBuffer(jsFile)
                return@handler
            }
        }
        document.addEventListener("paste", handler)
        onDispose {
            document.removeEventListener("paste", handler)
        }
    }
}
