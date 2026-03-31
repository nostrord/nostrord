package org.nostr.nostrord.network.managers

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Must be called once from Application.onCreate() before [createNetworkMonitorFlow].
 */
object AndroidNetworkMonitorInit {
    @SuppressLint("StaticFieldLeak") // Application context is safe to hold statically
    internal var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }
}

actual fun createNetworkMonitorFlow(): Flow<NetworkEvent> {
    val context = AndroidNetworkMonitorInit.appContext ?: return emptyFlow()
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return emptyFlow()

    return callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastNetworkId: Long? = null

            override fun onAvailable(network: Network) {
                val currentId = network.toString().hashCode().toLong()
                if (lastNetworkId != null && lastNetworkId != currentId) {
                    trySend(NetworkEvent.CHANGED)
                } else {
                    trySend(NetworkEvent.CONNECTED)
                }
                lastNetworkId = currentId
            }

            override fun onLost(network: Network) {
                trySend(NetworkEvent.DISCONNECTED)
                lastNetworkId = null
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val currentId = network.toString().hashCode().toLong()
                if (lastNetworkId != null && lastNetworkId != currentId) {
                    trySend(NetworkEvent.CHANGED)
                    lastNetworkId = currentId
                }
            }
        }

        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
}
