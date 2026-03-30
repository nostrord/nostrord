package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages NIP-65 relay list discovery and caching.
 *
 * ## Responsibilities:
 * - Discover relay lists from bootstrap relays
 * - Cache relay lists with TTL
 * - Provide fallbacks when relay lists unavailable
 * - Handle concurrent requests for same pubkey
 *
 * Note: Does not manage its own connections - uses connectionProvider from NostrRepository
 */
class RelayListManager(
    override val bootstrapRelays: List<String> = DEFAULT_BOOTSTRAP_RELAYS,
    private val connectionManager: ConnectionManager? = null
) : OutboxModel {

    companion object {
        // Discovery relays (serve kind:0, kind:10002, kind:10009)
        // Multiple relays for resilience — if one is down, others still serve metadata.
        val DEFAULT_BOOTSTRAP_RELAYS = listOf(
            "wss://purplepag.es",       // specialized NIP-65 index
            "wss://relay.primal.net",   // general-purpose, high availability
            "wss://relay.damus.io",     // large relay, high retention
            "wss://nos.lol"             // well-known, serves kind:0
        )

        // Used when a user has no NIP-65 relay list: content-rich relays with high retention
        val DEFAULT_FALLBACK_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://relay.snort.social",
            "wss://www.nostr.ltd"
        )

        const val CACHE_TTL_MS = 3600_000L  // 1 hour
        const val MAX_CACHE_SIZE = 1000
        const val FETCH_TIMEOUT_MS = 5_000L  // 5 seconds (faster)
    }

    // Current user's relay list
    private val _myRelayList = MutableStateFlow<List<Nip65Relay>>(emptyList())
    override val myRelayList: StateFlow<List<Nip65Relay>> = _myRelayList.asStateFlow()

    // Cache of relay lists by pubkey
    private val relayListCache = mutableMapOf<String, CachedRelayList>()
    private val cacheMutex = Mutex()

    // Pending requests to avoid duplicate fetches
    private val pendingRequests = mutableSetOf<String>()
    private val pendingMutex = Mutex()

    // Current user's pubkey (set after login)
    private var myPubkey: String? = null

    /**
     * Set the current user's pubkey and relay list
     */
    fun setMyRelayList(pubkey: String, relays: List<Nip65Relay>, eventCreatedAt: Long = 0) {
        myPubkey = pubkey
        _myRelayList.value = relays
        // Also cache it
        cacheRelayList(pubkey, relays, eventCreatedAt)
    }

    /**
     * Cache relay list for any user (called when receiving kind:10002 events).
     * Only updates if [eventCreatedAt] is newer than the existing cached entry.
     */
    fun cacheRelayListForUser(pubkey: String, relays: List<Nip65Relay>, eventCreatedAt: Long = 0) {
        cacheRelayList(pubkey, relays, eventCreatedAt)
    }

    /**
     * Get relay list for a pubkey.
     * Returns cached version if valid, otherwise fetches from bootstrap relays.
     */
    override suspend fun getRelayList(pubkey: String): List<Nip65Relay> {
        // Check cache first
        cacheMutex.withLock {
            relayListCache[pubkey]?.let { cached ->
                if (!cached.isExpired(epochMillis())) {
                    return cached.relays
                }
            }
        }

        // Check if already fetching
        val shouldFetch = pendingMutex.withLock {
            if (pendingRequests.contains(pubkey)) {
                false
            } else {
                pendingRequests.add(pubkey)
                true
            }
        }

        if (!shouldFetch) {
            // Wait for existing fetch to complete
            kotlinx.coroutines.delay(500)
            return cacheMutex.withLock {
                relayListCache[pubkey]?.relays ?: emptyList()
            }
        }

        try {
            // Fetch from bootstrap relays
            val relays = fetchRelayListFromBootstrap(pubkey)
            if (relays.isNotEmpty()) {
                cacheRelayList(pubkey, relays)
            }
            return relays
        } finally {
            pendingMutex.withLock {
                pendingRequests.remove(pubkey)
            }
        }
    }

    /**
     * Fetch relay list from all discovery relays in parallel.
     * Sends REQs to all bootstrap relays simultaneously; responses arrive via the global
     * message handler → handleKind10002Event → cacheRelayListForUser which keeps the
     * latest event (by created_at). Returns as soon as the cache is populated or timeout.
     */
    private suspend fun fetchRelayListFromBootstrap(pubkey: String): List<Nip65Relay> {
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(10002) }
            putJsonArray("authors") { add(pubkey) }
            put("limit", 1)
        }

        // Send REQs to all bootstrap relays in parallel
        val activeSubs = bootstrapRelays.mapNotNull { relayUrl ->
            val client = connectionManager?.getClientForRelay(relayUrl) ?: return@mapNotNull null
            if (!client.isConnected()) return@mapNotNull null
            val subId = "nip65-${pubkey.take(8)}-${relayUrl.substringAfterLast("/").take(6)}"
            try {
                client.send(buildJsonArray { add("CLOSE"); add(subId) }.toString())
                client.send(buildJsonArray { add("REQ"); add(subId); add(filter) }.toString())
                subId to client
            } catch (_: Exception) { null }
        }
        if (activeSubs.isEmpty()) return emptyList()

        // Poll cache — both relays feed it via handleKind10002Event, latest wins
        val startTime = epochMillis()
        var result: List<Nip65Relay> = emptyList()
        while (epochMillis() - startTime < FETCH_TIMEOUT_MS) {
            val cached = cacheMutex.withLock { relayListCache[pubkey] }
            if (cached != null && !cached.isExpired(epochMillis())) {
                result = cached.relays
                break
            }
            delay(100)
        }

        // Close all subscriptions
        activeSubs.forEach { (subId, client) ->
            try { client.send(buildJsonArray { add("CLOSE"); add(subId) }.toString()) } catch (_: Exception) {}
        }
        return result
    }

    /**
     * Cache a relay list. Only overwrites if [eventCreatedAt] is newer than
     * the existing entry (or if no entry exists), ensuring the latest
     * kind:10002 event always wins when multiple relays respond.
     */
    private fun cacheRelayList(pubkey: String, relays: List<Nip65Relay>, eventCreatedAt: Long = 0) {
        val existing = relayListCache[pubkey]
        if (existing != null && eventCreatedAt > 0 && existing.eventCreatedAt >= eventCreatedAt) {
            return // existing is same or newer, skip
        }

        val now = epochMillis()
        val cached = CachedRelayList(
            pubkey = pubkey,
            relays = relays,
            eventCreatedAt = eventCreatedAt,
            fetchedAt = now,
            expiresAt = now + CACHE_TTL_MS
        )

        // Evict oldest entries if cache is full
        if (relayListCache.size >= MAX_CACHE_SIZE) {
            val oldest = relayListCache.entries.minByOrNull { it.value.fetchedAt }
            oldest?.let { relayListCache.remove(it.key) }
        }

        relayListCache[pubkey] = cached
    }

    /**
     * Select relays for publishing events.
     * Uses current user's WRITE relays.
     */
    override fun selectPublishRelays(): List<String> {
        val writeRelays = _myRelayList.value.filter { it.write }.map { it.url }

        return if (writeRelays.isNotEmpty()) {
            writeRelays
        } else {
            DEFAULT_FALLBACK_RELAYS
        }
    }

    /**
     * Get cached relay list for a pubkey (synchronous, doesn't fetch)
     */
    fun getCachedRelayList(pubkey: String): List<Nip65Relay> {
        return if (pubkey == myPubkey) {
            _myRelayList.value
        } else {
            relayListCache[pubkey]?.relays ?: emptyList()
        }
    }

    /**
     * Clear cache for a specific pubkey
     */
    fun invalidateCache(pubkey: String) {
        relayListCache.remove(pubkey)
    }

    /**
     * Clear all cached relay lists
     */
    fun clearCache() {
        relayListCache.clear()
    }

    /**
     * Clear state (connections are managed by NostrRepository)
     */
    fun clear() {
        relayListCache.clear()
        _myRelayList.value = emptyList()
        myPubkey = null
    }
}
