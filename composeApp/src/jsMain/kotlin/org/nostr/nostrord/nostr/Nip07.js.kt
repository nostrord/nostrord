package org.nostr.nostrord.nostr

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private fun jsIsNip07Available(): Boolean =
    js("typeof window !== 'undefined' && typeof window.nostr !== 'undefined' && window.nostr !== null")
        .unsafeCast<Boolean>()

private fun jsGetPublicKeyPromise(): dynamic =
    js("window.nostr.getPublicKey()")

private fun jsSignEventPromise(eventJson: String): dynamic =
    js("window.nostr.signEvent(JSON.parse(eventJson))")

private fun jsStringify(obj: dynamic): String =
    js("JSON.stringify(obj)").unsafeCast<String>()

actual object Nip07 {

    actual fun isAvailable(): Boolean = jsIsNip07Available()

    actual suspend fun getPublicKey(): String = suspendCoroutine { cont ->
        val promise = jsGetPublicKeyPromise()
        promise.then(
            { pubkey: dynamic -> cont.resume(pubkey.toString()); null },
            { err: dynamic -> cont.resumeWithException(Exception(err.toString())); null }
        )
    }

    actual suspend fun signEvent(eventJson: String): String = suspendCoroutine { cont ->
        val promise = try {
            jsSignEventPromise(eventJson)
        } catch (e: Throwable) {
            cont.resumeWithException(Exception("Signing request failed: ${e.message}"))
            return@suspendCoroutine
        }
        promise.then(
            { signedEvent: dynamic ->
                if (signedEvent == null) {
                    cont.resumeWithException(Exception("Signing request was rejected"))
                } else {
                    cont.resume(jsStringify(signedEvent))
                }
                null
            },
            { err: dynamic ->
                val message = if (err != null && err != undefined) {
                    try { err.message?.toString() ?: err.toString() } catch (_: Throwable) { "Signing request was rejected" }
                } else {
                    "Signing request was rejected"
                }
                cont.resumeWithException(Exception(message))
                null
            }
        )
    }
}
