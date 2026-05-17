@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.js.ExperimentalWasmJsInterop

actual class ClipboardImageReader {
    actual fun hasImage(): Boolean = false

    actual suspend fun read(): Pair<ByteArray, String>? = null
}

@Composable
actual fun rememberClipboardImageReader(): ClipboardImageReader = remember { ClipboardImageReader() }

// Stores the pasted File in the JS-side cache (__nc) and returns just a key.
// Avoids reading clipboard data into Kotlin/Wasm memory, preventing the 6-8x
// memory amplification of the base64 round-trip.
@JsFun(
    """() => new Promise((resolve) => {
    const SUPPORTED = new Set([
        'image/jpeg','image/png','image/gif','image/webp','image/avif',
        'video/mp4','video/quicktime','video/webm',
        'audio/mpeg','audio/ogg','audio/wav','audio/flac','audio/mp4','audio/aac','audio/opus'
    ]);
    document.addEventListener('paste', function handler(event) {
        const items = event.clipboardData && event.clipboardData.items;
        if (!items) return;
        for (let i = 0; i < items.length; i++) {
            const item = items[i];
            if (item.kind !== 'file') continue;
            const type = item.type;
            if (!type.startsWith('image/') && !type.startsWith('video/') && !type.startsWith('audio/')) continue;
            const file = item.getAsFile();
            if (!file) continue;
            event.preventDefault();
            document.removeEventListener('paste', handler);
            if (!SUPPORTED.has(type)) {
                resolve(JSON.stringify({ error: 'unsupported_type' }));
                return;
            }
            if (file.size > 20971520) {
                resolve(JSON.stringify({ error: 'too_large' }));
                return;
            }
            if (!globalThis.__nc) { globalThis.__nc = new Map(); globalThis.__ncSeq = 0; }
            const key = '__nc_' + (++globalThis.__ncSeq);
            globalThis.__nc.set(key, file);
            resolve(JSON.stringify({ name: file.name || 'paste.png', mime: type, key: key }));
            return;
        }
    });
})""",
)
private external fun jsAwaitNextPaste(): kotlin.js.Promise<JsString?>

@Composable
actual fun PasteMediaEffect(
    onMediaPasted: (ByteArray, String) -> Unit,
    onError: (String) -> Unit,
) {
    val currentCallback = rememberUpdatedState(onMediaPasted)
    val currentOnError = rememberUpdatedState(onError)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        var active = true
        val job =
            scope.launch {
                while (active) {
                    val result: JsString? = jsAwaitNextPaste().await()
                    if (!active) break
                    val jsonStr = result?.toString() ?: continue
                    try {
                        val json = Json.parseToJsonElement(jsonStr).jsonObject
                        val error = json["error"]?.jsonPrimitive?.contentOrNull
                        if (error != null) {
                            when (error) {
                                "too_large" -> currentOnError.value("This file is too large. The maximum upload size is 20 MB.")
                                "unsupported_type" -> currentOnError.value("This file type is not supported.\n\n$SUPPORTED_FORMATS_MESSAGE")
                            }
                            continue
                        }
                        val name = json["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        val mime = json["mime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                        val key = json["key"]?.jsonPrimitive?.contentOrNull ?: continue
                        currentCallback.value(ByteArray(0), blobRef(mime, name, key))
                    } catch (_: Throwable) {
                    }
                }
            }
        onDispose {
            active = false
            job.cancel()
        }
    }
}
