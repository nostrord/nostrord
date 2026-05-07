@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Stores the File object in a JS-side cache (__nc) and returns just a key.
// This avoids reading the file into Kotlin/Wasm memory entirely, preventing
// the 6-8x memory amplification that occurs with the base64 round-trip.
@JsFun("""(accept) => new Promise((resolve) => {
    if (!globalThis.__nc) { globalThis.__nc = new Map(); globalThis.__ncSeq = 0; }
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.style.cssText = 'display:none;position:fixed;top:-9999px;left:-9999px';

    const cleanup = () => { try { document.body.removeChild(input); } catch(_) {} };

    input.addEventListener('cancel', () => { cleanup(); resolve(null); });

    input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) { cleanup(); resolve(null); return; }
        if (file.size > 20971520) { cleanup(); resolve(null); return; }
        const key = '__nc_' + (++globalThis.__ncSeq);
        globalThis.__nc.set(key, file);
        cleanup();
        resolve(JSON.stringify({ name: file.name, mime: file.type || 'application/octet-stream', key: key }));
    };

    document.body.appendChild(input);
    input.click();
})""")
private external fun jsPickFile(accept: JsString): kotlin.js.Promise<JsString?>

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
    val scope = rememberCoroutineScope()
    val currentCallback = rememberUpdatedState(onFilePicked)
    val currentPickStart = rememberUpdatedState(onPickStart)
    val acceptAttr = when (accept) {
        MediaAccept.Images            -> "image/*"
        MediaAccept.ImagesVideosAudio -> "image/*,video/mp4,video/quicktime,video/webm,audio/*"
    }.toJsString()
    return remember(acceptAttr) {
        MediaPickerLauncher {
            scope.launch {
                try {
                    val result: JsString? = jsPickFile(acceptAttr).await()
                    val jsonStr = result?.toString() ?: return@launch
                    currentPickStart.value()
                    val json = Json.parseToJsonElement(jsonStr).jsonObject
                    val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return@launch
                    val mime = json["mime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
                    val key  = json["key"]?.jsonPrimitive?.contentOrNull ?: return@launch
                    currentCallback.value(ByteArray(0), blobRef(mime, name, key))
                } catch (_: Throwable) {}
            }
        }
    }
}

internal fun blobRef(mime: String, name: String, key: String) = "nostrord-blob|$mime|$name|$key"
