package org.nostr.nostrord.network.managers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.epochMillis

/**
 * Tracks the most recent event timestamp seen per group per relay.
 *
 * Used to produce a reliable `since` value for live subscriptions:
 *   - First connect (no cursor): fetch last [INITIAL_WINDOW_S] seconds
 *   - Reconnect (cursor exists): fetch from [lastEventAt - RECONNECT_OVERLAP_S]
 *   - Cap at [MAX_SINCE_AGE_S] old to avoid hammering relays with stale history requests
 *
 * Persisted to [SecureStorage] per relay so cursors survive app restarts.
 * Persistence is lazy (written periodically, never on every event).
 */
class LiveCursorStore {

    companion object {
        /** Overlap subtracted from cursor on reconnect — covers clock skew and late deliveries. */
        const val RECONNECT_OVERLAP_S = 30L

        /** How far back to subscribe when a group has no cursor yet. */
        const val INITIAL_WINDOW_S = 3_600L   // 1 hour

        /** Hard cap: never ask for events older than this to avoid overwhelming relays. */
        const val MAX_SINCE_AGE_S = 24 * 3_600L
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    // relayUrl → (groupId → lastEventAt in Unix seconds)
    private val cursors = mutableMapOf<String, MutableMap<String, Long>>()

    /**
     * Record that [eventTimestamp] (Unix seconds) was the most recent event seen
     * for [groupId] on [relayUrl].  Only advances forward — never overwrites a newer cursor.
     */
    suspend fun update(relayUrl: String, groupId: String, eventTimestamp: Long) {
        mutex.withLock {
            val relay = cursors.getOrPut(relayUrl) { mutableMapOf() }
            val current = relay[groupId] ?: 0L
            if (eventTimestamp > current) {
                relay[groupId] = eventTimestamp
            }
        }
    }

    /**
     * Returns the `since` Unix-seconds value to use when opening (or refreshing) the
     * live subscription for [groupId] on [relayUrl].
     *
     * Subtracts [RECONNECT_OVERLAP_S] from the cursor so events that arrived just before
     * the disconnect are not missed due to clock skew.  The [EventDeduplicator] removes
     * any genuine duplicates that come back through the overlap window.
     */
    suspend fun getSince(relayUrl: String, groupId: String): Long {
        val nowSeconds = epochMillis() / 1000
        val cursor = mutex.withLock { cursors[relayUrl]?.get(groupId) }
        return if (cursor != null && cursor > 0L) {
            val raw = cursor - RECONNECT_OVERLAP_S
            maxOf(raw, nowSeconds - MAX_SINCE_AGE_S)
        } else {
            nowSeconds - INITIAL_WINDOW_S
        }
    }

    /**
     * Returns the minimum `since` across all [groupIds] on [relayUrl].
     *
     * Used for the multiplexed subscription: the mux REQ uses the oldest cursor so no
     * group misses events after a reconnect.  Groups with no cursor use [INITIAL_WINDOW_S].
     */
    suspend fun getMinSince(relayUrl: String, groupIds: List<String>): Long {
        if (groupIds.isEmpty()) return epochMillis() / 1000 - INITIAL_WINDOW_S
        return groupIds.minOf { getSince(relayUrl, it) }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    /**
     * Write all cursors for [relayUrl] to storage as a compact JSON map.
     * Key: groupId, Value: lastEventAt (Unix seconds).
     */
    suspend fun persist(relayUrl: String) {
        val snapshot = mutex.withLock { cursors[relayUrl]?.toMap() } ?: return
        if (snapshot.isEmpty()) return
        try {
            val encoded = json.encodeToString(snapshot)
            withContext(Dispatchers.Default) { SecureStorage.saveLiveCursors(relayUrl, encoded) }
        } catch (_: Exception) {}
    }

    /** Persist cursors for every relay currently in memory. */
    suspend fun persistAll() {
        val relayUrls = mutex.withLock { cursors.keys.toList() }
        relayUrls.forEach { persist(it) }
    }

    /**
     * Load cursors for [relayUrl] from storage into memory.
     * In-memory values always win — a stale on-disk value never overwrites a live cursor.
     */
    suspend fun load(relayUrl: String) {
        val raw = withContext(Dispatchers.Default) { SecureStorage.getLiveCursors(relayUrl) } ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val map: Map<String, Long> = json.decodeFromString(raw)
            mutex.withLock {
                val relay = cursors.getOrPut(relayUrl) { mutableMapOf() }
                map.forEach { (groupId, ts) ->
                    // Only load if we don't already have a live (newer) cursor
                    if (!relay.containsKey(groupId)) {
                        relay[groupId] = ts
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /** Load cursors for all given relay URLs. Call once on startup after relay list is known. */
    suspend fun loadAll(relayUrls: List<String>) {
        relayUrls.forEach { load(it) }
    }

    /**
     * Clear all in-memory cursors and remove persisted cursors for every known relay.
     * Call on logout so a different account starts with no stale cursors.
     */
    suspend fun clear() {
        val relayUrls = mutex.withLock {
            val keys = cursors.keys.toList()
            cursors.clear()
            keys
        }
        withContext(Dispatchers.Default) { relayUrls.forEach { SecureStorage.clearLiveCursors(it) } }
    }
}
