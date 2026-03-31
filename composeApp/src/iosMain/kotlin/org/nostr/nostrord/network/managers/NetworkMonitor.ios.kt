package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** iOS stub — NWPathMonitor can be added later. Heartbeat suffices for now. */
actual fun createNetworkMonitorFlow(): Flow<NetworkEvent> = emptyFlow()
