@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Opens a native browser file picker, reads the selected file as base64 JSON, and
 * returns it to Kotlin via a Promise.  The base64 round-trip is the only way to pass
 * arbitrary binary data from Wasm-JS interop without resorting to unsafe memory tricks.
 *
 * Large files are encoded in 8 kB chunks to avoid call-stack overflow in String.fromCharCode.
 * The [accept] parameter maps directly to the HTML input accept attribute.
 */
@JsFun("""(accept) => new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.style.cssText = 'display:none;position:fixed;top:-9999px;left:-9999px';

    const cleanup = () => { try { document.body.removeChild(input); } catch(_) {} };

    input.addEventListener('cancel', () => { cleanup(); resolve(null); });

    input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) { cleanup(); resolve(null); return; }
        const reader = new FileReader();
        reader.onload = () => {
            const bytes = new Uint8Array(reader.result);
            let bin = '';
            const chunk = 8192;
            for (let i = 0; i < bytes.length; i += chunk) {
                bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
            }
            cleanup();
            resolve(JSON.stringify({ name: file.name, data: btoa(bin) }));
        };
        reader.onerror = () => { cleanup(); resolve(null); };
        reader.readAsArrayBuffer(file);
    };

    document.body.appendChild(input);
    input.click();
})""")
private external fun jsPickFile(accept: JsString): kotlin.js.Promise<JsString?>

actual class MediaPickerLauncher(private val doLaunch: () -> Unit) {
    actual fun launch() = doLaunch()
}

@OptIn(ExperimentalEncodingApi::class)
@Composable
actual fun rememberMediaPickerLauncher(
    accept: MediaAccept,
    onFilePicked: (ByteArray, String) -> Unit
): MediaPickerLauncher {
    val scope = rememberCoroutineScope()
    val currentCallback = rememberUpdatedState(onFilePicked)
    val acceptAttr = when (accept) {
        MediaAccept.Images            -> "image/*"
        MediaAccept.ImagesVideosAudio -> "image/*,video/mp4,video/quicktime,audio/*"
    }.toJsString()
    return remember(acceptAttr) {
        MediaPickerLauncher {
            scope.launch {
                try {
                    val result: JsString? = jsPickFile(acceptAttr).await()
                    val jsonStr = result?.toString() ?: return@launch
                    val json = Json.parseToJsonElement(jsonStr).jsonObject
                    val name = json["name"]?.jsonPrimitive?.contentOrNull ?: return@launch
                    val data = json["data"]?.jsonPrimitive?.contentOrNull ?: return@launch
                    val bytes = Base64.decode(data)
                    currentCallback.value(bytes, name)
                } catch (_: Throwable) {}
            }
        }
    }
}
