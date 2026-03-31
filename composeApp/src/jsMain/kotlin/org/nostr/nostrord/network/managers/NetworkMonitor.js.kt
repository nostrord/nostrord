package org.nostr.nostrord.network.managers

import kotlinx.browser.window
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.w3c.dom.events.Event

actual fun createNetworkMonitorFlow(): Flow<NetworkEvent> = callbackFlow {
    val onOnline: (Event) -> Unit = { trySend(NetworkEvent.CONNECTED) }
    val onOffline: (Event) -> Unit = { trySend(NetworkEvent.DISCONNECTED) }

    window.addEventListener("online", onOnline)
    window.addEventListener("offline", onOffline)

    awaitClose {
        window.removeEventListener("online", onOnline)
        window.removeEventListener("offline", onOffline)
    }
}
