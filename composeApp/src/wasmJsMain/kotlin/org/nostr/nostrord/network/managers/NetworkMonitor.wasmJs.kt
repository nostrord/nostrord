@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.network.managers

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("""(onOnline, onOffline) => {
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    return () => {
        window.removeEventListener('online', onOnline);
        window.removeEventListener('offline', onOffline);
    };
}""")
private external fun jsAddNetworkListeners(
    onOnline: () -> Unit,
    onOffline: () -> Unit
): () -> Unit

actual fun createNetworkMonitorFlow(): Flow<NetworkEvent> = callbackFlow {
    val cleanup = jsAddNetworkListeners(
        onOnline = { trySend(NetworkEvent.CONNECTED) },
        onOffline = { trySend(NetworkEvent.DISCONNECTED) }
    )
    awaitClose { cleanup() }
}
