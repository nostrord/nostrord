package org.nostr.nostrord.network.managers

import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Tracks the last-sent mux subscription state per relay to avoid
 * redundant CLOSE+REQ cycles when nothing has changed.
 *
 * Call [clearRelay] when a relay disconnects so reconnect always sends fresh subs.
 *
 * Also tracks per-relay proof of life ([noteActivity] / [isStale]): a sub the relay
 * silently dropped (idle reap without CLOSED, REQ eaten pre-AUTH) leaves the tracked
 * state identical to the desired one, so [needsRefresh] alone would no-op every
 * periodic refresh while the feed is deaf. Staleness lets the periodic refresh force
 * a re-send.
 *
 * Keys are normalized so callers holding different spellings of the same relay URL
 * (raw socket URL vs normalized joined-list key) always hit the same entry.
 */
class MuxSubscriptionTracker {
    data class MuxState(
        val metadataGroupIds: Set<String>,
        val chatGroupIds: Set<String>,
        val chatSinceSeconds: Long,
        // kind:9000 put-user watch (#p = self). null when logged out.
        val putUserPubkey: String? = null,
        val putUserSinceSeconds: Long = 0L,
    )

    private val activeMuxState = mutableMapOf<String, MuxState>()
    private val lastActivityMs = mutableMapOf<String, Long>()

    private fun key(relayUrl: String) = relayUrl.normalizeRelayUrl()

    /**
     * Returns true if the desired mux state differs from what was last sent.
     * Always returns true after [clearRelay] (disconnect), ensuring reconnect re-sends.
     */
    fun needsRefresh(
        relayUrl: String,
        desired: MuxState,
    ): Boolean {
        val active = activeMuxState[key(relayUrl)] ?: return true
        if (active.metadataGroupIds != desired.metadataGroupIds) return true
        if (active.chatGroupIds != desired.chatGroupIds) return true
        if (desired.chatSinceSeconds < active.chatSinceSeconds) return true
        if (active.putUserPubkey != desired.putUserPubkey) return true
        // An ADVANCED put-user cursor alone is not a trigger: the live watch already streams.
        if (desired.putUserSinceSeconds < active.putUserSinceSeconds) return true
        return false
    }

    fun update(
        relayUrl: String,
        state: MuxState,
        nowMs: Long = epochMillis(),
    ) {
        activeMuxState[key(relayUrl)] = state
        // Sending counts as activity: a fresh REQ deserves its full quiet window before
        // being declared stale, whether or not the relay ever answers it.
        lastActivityMs[key(relayUrl)] = nowMs
    }

    /** Record proof of life: any EVENT or EOSE on one of [relayUrl]'s mux subs. */
    fun noteActivity(
        relayUrl: String,
        nowMs: Long = epochMillis(),
    ) {
        lastActivityMs[key(relayUrl)] = nowMs
    }

    /**
     * True when [relayUrl] has an active mux state but nothing has been heard on its
     * mux subs for [staleAfterMs]. A quiet-but-alive sub re-proves itself cheaply when
     * the caller reacts by clearing + re-sending (the EOSE refreshes the activity mark).
     */
    fun isStale(
        relayUrl: String,
        nowMs: Long,
        staleAfterMs: Long,
    ): Boolean {
        val k = key(relayUrl)
        if (k !in activeMuxState) return false
        val last = lastActivityMs[k] ?: return true
        return nowMs - last > staleAfterMs
    }

    fun clearRelay(relayUrl: String) {
        activeMuxState.remove(key(relayUrl))
    }

    fun clearAll() {
        activeMuxState.clear()
    }
}
