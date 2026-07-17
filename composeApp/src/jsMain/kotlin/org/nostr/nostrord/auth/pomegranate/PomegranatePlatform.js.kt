package org.nostr.nostrord.auth.pomegranate

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import org.nostr.nostrord.nostr.hexToByteArray
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val POPUP_TIMEOUT_MS = 5 * 60 * 1000

internal actual object PomegranatePopups {
    actual val isAvailable: Boolean = true

    actual suspend fun awaitTokenFromPopup(
        url: String,
        expectedOrigin: String,
    ): String = awaitPopupMessage(url, expectedOrigin, "PomegranateLogin") { data ->
        val token = data?.token
        if (jsTypeOf(token) == "string") token.unsafeCast<String>() else null
    }

    actual suspend fun awaitShardFromPopup(
        url: String,
        expectedOrigin: String,
    ): String = awaitPopupMessage(url, expectedOrigin, "PomegranateRecover") { data ->
        if (jsTypeOf(data) == "string") data.unsafeCast<String>() else null
    }

    /**
     * Opens a centered popup and resolves with the first message posted from
     * [expectedOrigin] by that window for which [extract] returns non-null. Rejects
     * when the popup is closed first (silent-cancel exception) or on timeout. Must be
     * reached synchronously-enough from a user gesture or `window.open` is blocked.
     */
    private suspend fun awaitPopupMessage(
        url: String,
        expectedOrigin: String,
        name: String,
        extract: (dynamic) -> String?,
    ): String {
        val width = 600
        val height = 700
        val left = window.screenX + maxOf(0, (window.outerWidth - width) / 2)
        val top = window.screenY + maxOf(0, (window.outerHeight - height) / 2)
        val popup =
            window.open(url, name, "popup=yes,width=$width,height=$height,left=$left,top=$top")
                ?: throw PomegranatePopupBlockedException()
        return suspendCancellableCoroutine { cont ->
            var closeMonitor = -1
            var timer = -1
            var onMessage: ((Event) -> Unit)? = null

            fun cleanup() {
                onMessage?.let { window.removeEventListener("message", it) }
                window.clearInterval(closeMonitor)
                window.clearTimeout(timer)
            }
            onMessage = listener@{ event: Event ->
                val msg = event.unsafeCast<MessageEvent>()
                // Referential !== only: Kotlin's != compiles to .equals(), and touching any
                // property of a cross-origin Window (the popup) throws a SecurityError.
                if (msg.origin != expectedOrigin || msg.source !== popup) return@listener
                val value = extract(msg.data.asDynamic()) ?: return@listener
                cleanup()
                popup.close()
                cont.resume(value)
            }
            closeMonitor =
                window.setInterval({
                    if (popup.closed) {
                        cleanup()
                        cont.resumeWithException(PomegranatePopupClosedException())
                    }
                }, 300)
            timer =
                window.setTimeout({
                    cleanup()
                    popup.close()
                    cont.resumeWithException(RuntimeException("Timed out waiting for the popup"))
                }, POPUP_TIMEOUT_MS)
            window.addEventListener("message", onMessage)
            cont.invokeOnCancellation {
                cleanup()
                popup.close()
            }
        }
    }
}

internal actual object PomegranateDealer {
    actual fun deal(
        secretKeyHex: String,
        threshold: Int,
        count: Int,
    ): List<PomegranateShard> {
        val masterSk = js("BigInt('0x' + secretKeyHex)")
        val shards = trustedKeyDeal(masterSk, threshold, count).shards
        return List(count) { i ->
            val shard = shards[i]
            PomegranateShard(shardHex = hexShard(shard), pubShardHex = hexPubShard(shard.pubShard))
        }
    }

    actual fun aggregate(shardHexes: List<String>): String {
        val decoded = shardHexes.map { decodeShard(it.hexToUint8Array()) }.toTypedArray()
        val secret = aggregateSecretKeyShards(decoded)
        return bigintToHex(secret).padStart(64, '0')
    }
}

private fun bigintToHex(n: dynamic): String = n.toString(16).unsafeCast<String>()

private fun String.hexToUint8Array(): Uint8Array = Uint8Array(hexToByteArray().toTypedArray())
