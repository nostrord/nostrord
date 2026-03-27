package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages user metadata (profiles) and cached events.
 * Handles fetching and caching of kind:0 metadata events.
 */
class MetadataManager(
    private val connectionManager: ConnectionManager,
    private val outboxManager: OutboxManager,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val MAX_METADATA_CACHE_SIZE = 500
        const val MAX_EVENTS_CACHE_SIZE = 500
        /** Metadata older than this is considered stale and will be re-fetched. */
        const val STALE_THRESHOLD_MS = 30 * 60 * 1000L
        /** Maximum concurrent outbox relay connections for metadata fetching. */
        const val MAX_FETCH_CONCURRENCY = 8
        /** Number of retry attempts before giving up on a metadata fetch. */
        const val MAX_FETCH_ATTEMPTS = 3
    }

    // LRU caches for bounded memory usage
    private val metadataCache = LruCache<String, UserMetadata>(MAX_METADATA_CACHE_SIZE)
    private val eventsCache = LruCache<String, CachedEvent>(MAX_EVENTS_CACHE_SIZE)

    /** Tracks when each pubkey's metadata was last successfully fetched (epoch ms). */
    private val metadataFetchedAt = LruCache<String, Long>(MAX_METADATA_CACHE_SIZE)

    /** Limits concurrent outbox relay connections to avoid opening too many sockets. */
    private val fetchConcurrency = Semaphore(MAX_FETCH_CONCURRENCY)

    /** Token-bucket rate limiter: at most [MAX_FETCH_CONCURRENCY] fetches/second burst,
     *  sustained at [MetadataRateLimiter.DEFAULT_REQUESTS_PER_SECOND] fetches/second. */
    private val rateLimiter = MetadataRateLimiter(scope = scope)

    // Prevents duplicate concurrent fetches for the same pubkey
    private val inFlightPubkeys = mutableSetOf<String>()
    private val inFlightMutex = Mutex()

    // Prevents duplicate concurrent fetches for the same event/addressable key
    private val inFlightEvents = mutableSetOf<String>()
    private val inFlightEventsMutex = Mutex()

    private val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata.asStateFlow()

    private val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents.asStateFlow()

    /**
     * Request user metadata from their WRITE relays (Outbox model)
     */
    fun requestUserMetadata(pubkeys: Set<String>, messageHandler: (String, NostrGroupClient) -> Unit) {
        if (pubkeys.isEmpty()) return

        pubkeys.forEach { pubkey ->
            scope.launch {
                // Skip if already fetching metadata for this pubkey
                val shouldFetch = inFlightMutex.withLock {
                    if (inFlightPubkeys.contains(pubkey)) false
                    else { inFlightPubkeys.add(pubkey); true }
                }
                if (!shouldFetch) return@launch

                try {
                    rateLimiter.acquire()
                    fetchConcurrency.withPermit {
                        fetchWithRetry(pubkey, messageHandler)
                    }
                } finally {
                    inFlightMutex.withLock { inFlightPubkeys.remove(pubkey) }
                }
            }
        }
    }

    private suspend fun fetchWithRetry(pubkey: String, messageHandler: (String, NostrGroupClient) -> Unit) {
        // NIP-29 relays do NOT serve kind:0 — exclude them from candidates.
        val nip29Relays = SecureStorage.loadRelayList().toSet() +
            connectionManager.currentRelayUrl.value

        // Use bootstrap relays directly for kind:0. NIP-65 write relays of the target user
        // are almost never connected in a NIP-29 client, so the outbox lookup adds latency
        // without changing the outcome. purplepag.es + relay.primal.net serve kind:0.
        val candidates = outboxManager.bootstrapRelays
            .filter { it !in nip29Relays }

        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            if (metadataFetchedAt.get(pubkey) != null) return

            // Try already-connected general-purpose relays only (no new WebSocket connections).
            val sent = candidates.count { relayUrl ->
                try {
                    val client = connectionManager.getClientForRelay(relayUrl)
                    if (client != null && client.isConnected()) {
                        client.requestMetadata(listOf(pubkey))
                        true
                    } else false
                } catch (_: Exception) { false }
            }

            println("[Meta] fetch  pubkey=${pubkey.take(8)}  attempt=${attempt + 1}  candidates=${candidates.size}  sent=$sent")
            if (sent > 0) return

            if (attempt < MAX_FETCH_ATTEMPTS - 1) {
                delay(if (attempt == 0) 2_000L else 4_000L)
            }
        }
    }

    /**
     * Request an event by ID using already-connected relays only.
     * Does NOT create ephemeral WebSocket connections — uses pool/primary clients.
     */
    suspend fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
        messageHandler: (String, NostrGroupClient) -> Unit
    ) {
        // Skip if already cached or already in-flight
        if (_cachedEvents.value.containsKey(eventId)) return
        val shouldFetch = inFlightEventsMutex.withLock {
            if (inFlightEvents.contains(eventId)) false
            else { inFlightEvents.add(eventId); true }
        }
        if (!shouldFetch) return

        try {
            // Use outbox model for relay selection
            val relaysToTry = outboxManager.selectConnectedOutboxRelays(
                authors = if (author != null) listOf(author) else emptyList(),
                explicitRelays = relayHints
            )

            // All relays in relaysToTry are already connected (pre-filtered).
            var sent = relaysToTry.count { relayUrl ->
                try {
                    connectionManager.getClientForRelay(relayUrl)!!.requestEventById(eventId)
                    true
                } catch (_: Exception) { false }
            }

            // Always also try primary NIP-29 relay — it hosts the events being quoted.
            val primary = connectionManager.getPrimaryClient()
            if (primary != null && primary.isConnected()) {
                try { primary.requestEventById(eventId); sent++ } catch (_: Exception) {}
            }
            println("[Event] requestById  id=${eventId.take(8)}  connected=${relaysToTry.size}  sent=$sent")
        } finally {
            // Remove after a delay to allow the response to arrive and be cached.
            // If not cached after 10s, subsequent requests can try again.
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(eventId) }
            }
        }
    }

    /**
     * Request an addressable event (naddr) by its coordinates.
     * Addressable events use kind:pubkey:d-tag as their identifier.
     * Does NOT create ephemeral WebSocket connections — uses pool/primary clients.
     */
    suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relayHints: List<String> = emptyList(),
        messageHandler: (String, NostrGroupClient) -> Unit
    ) {
        // Create composite key for caching
        val addressKey = "$kind:$pubkey:$identifier"

        // Skip if already cached or already in-flight
        if (_cachedEvents.value.containsKey(addressKey)) return
        val shouldFetch = inFlightEventsMutex.withLock {
            if (inFlightEvents.contains(addressKey)) false
            else { inFlightEvents.add(addressKey); true }
        }
        if (!shouldFetch) return

        try {
            // Use outbox model for relay selection
            val relaysToTry = outboxManager.selectConnectedOutboxRelays(
                authors = listOf(pubkey),
                explicitRelays = relayHints
            )

            // All relays in relaysToTry are already connected (pre-filtered).
            var sent = relaysToTry.count { relayUrl ->
                try {
                    connectionManager.getClientForRelay(relayUrl)!!.requestAddressableEvent(kind, pubkey, identifier)
                    true
                } catch (_: Exception) { false }
            }

            // Always also try primary NIP-29 relay — it hosts the addressable events.
            val primary = connectionManager.getPrimaryClient()
            if (primary != null && primary.isConnected()) {
                try { primary.requestAddressableEvent(kind, pubkey, identifier); sent++ } catch (_: Exception) {}
            }
            println("[Event] requestAddr  key=$addressKey  connected=${relaysToTry.size}  sent=$sent")
        } finally {
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(addressKey) }
            }
        }
    }

    /**
     * Handle incoming metadata message
     */
    fun handleMetadataEvent(pubkey: String, metadata: UserMetadata) {
        metadataCache.put(pubkey, metadata)
        metadataFetchedAt.put(pubkey, epochMillis())
        _userMetadata.value = metadataCache.toMap()
    }

    /**
     * Update local metadata cache directly (for optimistic UI updates)
     */
    fun updateLocalMetadata(pubkey: String, metadata: UserMetadata) {
        metadataCache.put(pubkey, metadata)
        _userMetadata.value = metadataCache.toMap()
    }

    /**
     * Handle incoming cached event
     */
    fun handleCachedEvent(event: CachedEvent) {
        eventsCache.put(event.id, event)
        _cachedEvents.value = eventsCache.toMap()
    }

    /**
     * Parse and cache event from JSON
     */
    fun parseAndCacheEvent(eventJson: JsonObject): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null

            // Skip if already cached
            eventsCache.get(eventId)?.let { return it }

            val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
            val createdAt = eventJson["created_at"]?.jsonPrimitive?.long ?: 0L
            val kind = eventJson["kind"]?.jsonPrimitive?.int ?: 1
            val tags = eventJson["tags"]?.jsonArray?.map { tagArray ->
                tagArray.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            val cachedEvent = CachedEvent(
                id = eventId,
                pubkey = pubkey,
                kind = kind,
                content = content,
                createdAt = createdAt,
                tags = tags
            )

            eventsCache.put(eventId, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            cachedEvent
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse and cache addressable event from JSON using composite key.
     * Addressable events are cached with key format: kind:pubkey:d-tag
     */
    fun parseAndCacheAddressableEvent(eventJson: JsonObject): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null
            val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
            val createdAt = eventJson["created_at"]?.jsonPrimitive?.long ?: 0L
            val kind = eventJson["kind"]?.jsonPrimitive?.int ?: 1
            val tags = eventJson["tags"]?.jsonArray?.map { tagArray ->
                tagArray.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            // Extract d-tag (identifier) for addressable events
            val identifier = tags.find { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""

            // Create composite key for addressable events
            val addressKey = "$kind:$pubkey:$identifier"

            // Skip if already cached (check by address key)
            eventsCache.get(addressKey)?.let { return it }

            val cachedEvent = CachedEvent(
                id = eventId,
                pubkey = pubkey,
                kind = kind,
                content = content,
                createdAt = createdAt,
                tags = tags
            )

            // Cache with both the event ID and the address key
            eventsCache.put(eventId, cachedEvent)
            eventsCache.put(addressKey, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            cachedEvent
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if we have metadata for a pubkey
     */
    fun hasMetadata(pubkey: String): Boolean = metadataCache.containsKey(pubkey)

    /** Returns true if metadata for [pubkey] was never fetched or is older than [STALE_THRESHOLD_MS]. */
    fun isStale(pubkey: String): Boolean {
        val fetchedAt = metadataFetchedAt.get(pubkey) ?: return true
        return (epochMillis() - fetchedAt) > STALE_THRESHOLD_MS
    }

    /**
     * Check if we have a cached event
     */
    fun hasCachedEvent(eventId: String): Boolean = eventsCache.containsKey(eventId)

    /**
     * Get metadata for a pubkey
     */
    fun getMetadata(pubkey: String): UserMetadata? = metadataCache.get(pubkey)

    /**
     * Get cached event by ID
     */
    fun getCachedEvent(eventId: String): CachedEvent? = eventsCache.get(eventId)

    /**
     * Clear all cached data
     */
    fun clear() {
        metadataCache.clear()
        eventsCache.clear()
        metadataFetchedAt.clear()
        _userMetadata.value = emptyMap()
        _cachedEvents.value = emptyMap()
    }
}
