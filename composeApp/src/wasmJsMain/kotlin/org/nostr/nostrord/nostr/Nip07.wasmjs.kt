@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.nostr

import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.await

@JsFun("() => typeof window !== 'undefined' && typeof window.nostr !== 'undefined' && window.nostr !== null")
private external fun jsIsNip07Available(): Boolean

@JsFun("() => window.nostr.getPublicKey()")
private external fun jsGetPublicKey(): kotlin.js.Promise<JsString>

@JsFun("(eventJson) => window.nostr.signEvent(JSON.parse(eventJson)).then(e => JSON.stringify(e))")
private external fun jsSignEvent(eventJson: String): kotlin.js.Promise<JsString>

actual object Nip07 {

    actual fun isAvailable(): Boolean = jsIsNip07Available()

    actual suspend fun getPublicKey(): String {
        val result: JsString = jsGetPublicKey().await()
        return result.toString()
    }

    actual suspend fun signEvent(eventJson: String): String {
        val result: JsString = jsSignEvent(eventJson).await()
        return result.toString()
    }
}
