package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.Flow

enum class NetworkEvent {
    /** Device network became available after being offline. */
    CONNECTED,
    /** Device lost all network connectivity. */
    DISCONNECTED,
    /** Network interface or IP changed (e.g. WiFi → cellular, VPN toggle). */
    CHANGED
}

/**
 * Platform-specific network change detection.
 *
 * Returns a [Flow] of [NetworkEvent]s. Platforms that cannot detect network changes
 * (JVM desktop, iOS stub) return [kotlinx.coroutines.flow.emptyFlow].
 *
 * - Android: ConnectivityManager.registerDefaultNetworkCallback
 * - JS/WasmJs: window online/offline events
 * - JVM/iOS: no-op (heartbeat-based detection suffices)
 */
expect fun createNetworkMonitorFlow(): Flow<NetworkEvent>
