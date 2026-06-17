package org.nostr.nostrord.nostr

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private fun jsIsNip07Available(): Boolean = js("typeof window !== 'undefined' && typeof window.nostr !== 'undefined' && window.nostr !== null")
    .unsafeCast<Boolean>()

private fun jsGetPublicKeyPromise(): dynamic = js("window.nostr.getPublicKey()")

private fun jsSignEventPromise(eventJson: String): dynamic = js("window.nostr.signEvent(JSON.parse(eventJson))")

// Wrapped in Promise.resolve so both sync and promise-returning extension impls work.
private fun jsNip44EncryptPromise(peer: String, plaintext: String): dynamic = js("Promise.resolve(window.nostr.nip44.encrypt(peer, plaintext))")

private fun jsNip44DecryptPromise(peer: String, ciphertext: String): dynamic = js("Promise.resolve(window.nostr.nip44.decrypt(peer, ciphertext))")

private fun jsStringify(obj: dynamic): String = js("JSON.stringify(obj)").unsafeCast<String>()

actual object Nip07 {
    actual fun isAvailable(): Boolean = jsIsNip07Available()

    actual suspend fun getPublicKey(): String = suspendCoroutine { cont ->
        val promise = jsGetPublicKeyPromise()
        promise.then(
            { pubkey: dynamic ->
                cont.resume(pubkey.toString())
                null
            },
            { err: dynamic ->
                cont.resumeWithException(Exception(err.toString()))
                null
            },
        )
    }

    actual suspend fun signEvent(eventJson: String): String = suspendCoroutine { cont ->
        val promise =
            try {
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
                val message =
                    if (err != null && err != undefined) {
                        try {
                            err.message?.toString() ?: err.toString()
                        } catch (_: Throwable) {
                            "Signing request was rejected"
                        }
                    } else {
                        "Signing request was rejected"
                    }
                cont.resumeWithException(Exception(message))
                null
            },
        )
    }

    actual suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String =
        nip44Call({ jsNip44EncryptPromise(peerPubkeyHex, plaintext) })

    actual suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        nip44Call({ jsNip44DecryptPromise(peerPubkeyHex, ciphertext) })

    private suspend fun nip44Call(start: () -> dynamic): String = suspendCoroutine { cont ->
        val available = js("typeof window !== 'undefined' && window.nostr && window.nostr.nip44").unsafeCast<Boolean>()
        if (!available) {
            cont.resumeWithException(Exception("This extension does not support NIP-44 (window.nostr.nip44)"))
            return@suspendCoroutine
        }
        val promise =
            try {
                start()
            } catch (e: Throwable) {
                cont.resumeWithException(Exception("NIP-44 request failed: ${e.message}"))
                return@suspendCoroutine
            }
        promise.then(
            { result: dynamic ->
                if (result == null) {
                    cont.resumeWithException(Exception("NIP-44 request was rejected"))
                } else {
                    cont.resume(result.toString())
                }
                null
            },
            { err: dynamic ->
                val message =
                    if (err != null && err != undefined) {
                        try {
                            err.message?.toString() ?: err.toString()
                        } catch (_: Throwable) {
                            "NIP-44 request was rejected"
                        }
                    } else {
                        "NIP-44 request was rejected"
                    }
                cont.resumeWithException(Exception(message))
                null
            },
        )
    }
}
