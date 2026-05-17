@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalEncodingApi::class)

package org.nostr.nostrord.network.upload

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop

// Retrieves a File/Blob from the JS-side cache (__nc) and uploads it directly
// as FormData, keeping the data entirely in JS memory with no Kotlin/Wasm copy.
@JsFun(
    """(url, key, filename, mimeType, authHeader) =>
    new Promise((resolve) => {
        const file = globalThis.__nc && globalThis.__nc.get(key);
        if (!file) { resolve(JSON.stringify({ status: 0, body: 'blob not in cache: ' + key })); return; }
        globalThis.__nc.delete(key);
        const form = new FormData();
        form.append('fileToUpload', file, filename);
        fetch(url, {
            method: 'POST',
            headers: { 'Authorization': authHeader },
            body: form
        })
        .then(r => r.text().then(b => resolve(JSON.stringify({ status: r.status, body: b }))))
        .catch(e => resolve(JSON.stringify({ status: 0, body: String(e) })));
    })
""",
)
private external fun jsFetchFromCache(
    url: JsString,
    key: JsString,
    filename: JsString,
    mimeType: JsString,
    authHeader: JsString,
): kotlin.js.Promise<JsString?>

// Passes bytes as base64 (single string interop call) so JS can reconstruct a
// Uint8Array without triggering Ktor's byte-by-byte Wasm→JS conversion, which
// would stall on files larger than a few hundred KB.
@JsFun(
    """(url, base64data, filename, mimeType, authHeader) =>
    new Promise((resolve) => {
        const bin = atob(base64data);
        const arr = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        const blob = new Blob([arr], { type: mimeType });
        const form = new FormData();
        form.append('fileToUpload', blob, filename);
        fetch(url, {
            method: 'POST',
            headers: { 'Authorization': authHeader },
            body: form
        })
        .then(r => r.text().then(b => resolve(JSON.stringify({ status: r.status, body: b }))))
        .catch(e => resolve(JSON.stringify({ status: 0, body: String(e) })));
    })
""",
)
private external fun jsFetch(
    url: JsString,
    base64data: JsString,
    filename: JsString,
    mimeType: JsString,
    authHeader: JsString,
): kotlin.js.Promise<JsString?>

internal actual suspend fun executeUpload(
    url: String,
    bytes: ByteArray,
    filename: String,
    mimeType: String,
    authHeader: String,
): Pair<Int, String> {
    val result: JsString? =
        if (isBlobRef(filename)) {
            val parts = filename.split("|")
            val realMime = parts.getOrNull(1) ?: mimeType
            val realName = parts.getOrNull(2) ?: "file"
            val key = parts.getOrNull(3) ?: return Pair(0, "invalid blob ref: missing key")
            jsFetchFromCache(
                url.toJsString(),
                key.toJsString(),
                realName.toJsString(),
                realMime.toJsString(),
                authHeader.toJsString(),
            ).await()
        } else {
            val base64 = Base64.encode(bytes)
            jsFetch(
                url.toJsString(),
                base64.toJsString(),
                filename.toJsString(),
                mimeType.toJsString(),
                authHeader.toJsString(),
            ).await()
        }
    val json = Json.parseToJsonElement(result?.toString() ?: """{"status":0,"body":"no response"}""").jsonObject
    val status = json["status"]?.jsonPrimitive?.intOrNull ?: 0
    val body = json["body"]?.jsonPrimitive?.contentOrNull ?: ""
    return Pair(status, body)
}
