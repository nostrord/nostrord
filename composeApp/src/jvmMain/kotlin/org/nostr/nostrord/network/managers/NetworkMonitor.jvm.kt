package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** JVM desktop rarely changes IP — heartbeat-based detection suffices. */
actual fun createNetworkMonitorFlow(): Flow<NetworkEvent> = emptyFlow()
