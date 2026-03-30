package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.epochMillis

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
        /** Number of retry attempts before giving up on a metadata fetch. */
        const val MAX_FETCH_ATTEMPTS = 3
        /** Max authors per REQ filter — keeps relay filter sizes reasonable. */
        const val BATCH_SIZE = 50
        /** Coalesce window for metadata StateFlow emissions (ms). */
        const val METADATA_FLUSH_DELAY_MS = 100L
    }

    private val metadataCache = LruCache<String, UserMetadata>(MAX_METADATA_CACHE_SIZE)
    private val eventsCache = LruCache<String, CachedEvent>(MAX_EVENTS_CACHE_SIZE)
    private val metadataFetchedAt = LruCache<String, Long>(MAX_METADATA_CACHE_SIZE)

    private val inFlightPubkeys = mutableSetOf<String>()
    private val inFlightMutex = Mutex()
    private val inFlightEvents = mutableSetOf<String>()
    private val inFlightEventsMutex = Mutex()

    private val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata.asStateFlow()

    private val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents.asStateFlow()

    fun requestUserMetadata(pubkeys: Set<String>, messageHandler: (String, NostrGroupClient) -> Unit) {
        if (pubkeys.isEmpty()) return

        scope.launch {
            // Deduplicate: only fetch pubkeys not already cached or in-flight.
            val toFetch = inFlightMutex.withLock {
                pubkeys.filter { it !in inFlightPubkeys && metadataFetchedAt.get(it) == null }
                    .also { inFlightPubkeys.addAll(it) }
            }
            if (toFetch.isEmpty()) return@launch

            try {
                batchFetch(toFetch)
            } finally {
                inFlightMutex.withLock { inFlightPubkeys.removeAll(toFetch.toSet()) }
            }
        }
    }

    private suspend fun batchFetch(pubkeys: List<String>) {
        val nip29Relays = SecureStorage.loadRelayList().toSet() +
            connectionManager.currentRelayUrl.value

        val candidates = outboxManager.bootstrapRelays
            .filter { it !in nip29Relays }

        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            // Only request pubkeys we still don't have.
            val missing = pubkeys.filter { metadataFetchedAt.get(it) == null }
            if (missing.isEmpty()) return

            // Send one batched REQ per connected relay (all missing authors at once).
            val sent = candidates.count { relayUrl ->
                try {
                    val client = connectionManager.getClientForRelay(relayUrl)
                    if (client != null && client.isConnected()) {
                        // Batch into chunks of BATCH_SIZE to stay within relay filter limits.
                        missing.chunked(BATCH_SIZE).forEach { chunk ->
                            client.requestMetadata(chunk)
                        }
                        true
                    } else false
                } catch (_: Exception) { false }
            }

            if (sent > 0) {
                // Wait for responses — short on first attempt, longer on retry.
                delay(if (attempt == 0) 2_000L else 3_500L)
                if (pubkeys.all { metadataFetchedAt.get(it) != null }) return
            } else if (attempt < MAX_FETCH_ATTEMPTS - 1) {
                delay(if (attempt == 0) 2_000L else 4_000L)
            }
        }
    }

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
        } finally {
            // Remove after a delay to allow the response to arrive and be cached.
            // If not cached after 10s, subsequent requests can try again.
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(eventId) }
            }
        }
    }

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
        } finally {
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(addressKey) }
            }
        }
    }

    private var metadataFlushJob: Job? = null

    fun handleMetadataEvent(pubkey: String, metadata: UserMetadata) {
        metadataCache.put(pubkey, metadata)
        metadataFetchedAt.put(pubkey, epochMillis())
        scheduleMetadataFlush()
    }

    private fun scheduleMetadataFlush() {
        metadataFlushJob?.cancel()
        metadataFlushJob = scope.launch {
            delay(METADATA_FLUSH_DELAY_MS)
            _userMetadata.value = metadataCache.toMap()
        }
    }

    private fun flushMetadataNow() {
        metadataFlushJob?.cancel()
        _userMetadata.value = metadataCache.toMap()
    }

    fun updateLocalMetadata(pubkey: String, metadata: UserMetadata) {
        metadataCache.put(pubkey, metadata)
        flushMetadataNow()
    }

    fun handleCachedEvent(event: CachedEvent) {
        eventsCache.put(event.id, event)
        _cachedEvents.value = eventsCache.toMap()
    }

    fun parseAndCacheEvent(eventJson: JsonObject): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null

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

            val identifier = tags.find { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
            val addressKey = "$kind:$pubkey:$identifier"
            eventsCache.get(addressKey)?.let { return it }

            val cachedEvent = CachedEvent(
                id = eventId,
                pubkey = pubkey,
                kind = kind,
                content = content,
                createdAt = createdAt,
                tags = tags
            )

            eventsCache.put(eventId, cachedEvent)
            eventsCache.put(addressKey, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            cachedEvent
        } catch (_: Exception) {
            null
        }
    }

    fun hasMetadata(pubkey: String): Boolean = metadataCache.containsKey(pubkey)

    fun isStale(pubkey: String): Boolean {
        val fetchedAt = metadataFetchedAt.get(pubkey) ?: return true
        return (epochMillis() - fetchedAt) > STALE_THRESHOLD_MS
    }

    fun hasCachedEvent(eventId: String): Boolean = eventsCache.containsKey(eventId)

    fun getMetadata(pubkey: String): UserMetadata? = metadataCache.get(pubkey)

    fun getCachedEvent(eventId: String): CachedEvent? = eventsCache.get(eventId)

    fun clear() {
        metadataCache.clear()
        eventsCache.clear()
        metadataFetchedAt.clear()
        _userMetadata.value = emptyMap()
        _cachedEvents.value = emptyMap()
    }
}
