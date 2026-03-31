package org.nostr.nostrord.network.managers

/**
 * Tracks the last-sent mux subscription state per relay to avoid
 * redundant CLOSE+REQ cycles when nothing has changed.
 *
 * Call [clearRelay] when a relay disconnects so reconnect always sends fresh subs.
 */
class MuxSubscriptionTracker {

    data class MuxState(
        val metadataGroupIds: Set<String>,
        val chatGroupIds: Set<String>,
        val chatSinceSeconds: Long
    )

    private val activeMuxState = mutableMapOf<String, MuxState>()

    /**
     * Returns true if the desired mux state differs from what was last sent.
     * Always returns true after [clearRelay] (disconnect), ensuring reconnect re-sends.
     */
    fun needsRefresh(relayUrl: String, desired: MuxState): Boolean {
        val active = activeMuxState[relayUrl] ?: return true
        if (active.metadataGroupIds != desired.metadataGroupIds) return true
        if (active.chatGroupIds != desired.chatGroupIds) return true
        if (desired.chatSinceSeconds < active.chatSinceSeconds) return true
        return false
    }

    fun update(relayUrl: String, state: MuxState) {
        activeMuxState[relayUrl] = state
    }

    fun clearRelay(relayUrl: String) {
        activeMuxState.remove(relayUrl)
    }

    fun clearAll() {
        activeMuxState.clear()
    }
}
