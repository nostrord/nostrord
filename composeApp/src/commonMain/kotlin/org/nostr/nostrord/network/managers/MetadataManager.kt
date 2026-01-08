package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.utils.LruCache

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
    }

    // LRU caches for bounded memory usage
    private val metadataCache = LruCache<String, UserMetadata>(MAX_METADATA_CACHE_SIZE)
    private val eventsCache = LruCache<String, CachedEvent>(MAX_EVENTS_CACHE_SIZE)

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
                try {
                    // First, get their relay list
                    val relays = outboxManager.getRelayList(pubkey)
                    val writeRelays = if (relays.isNotEmpty()) {
                        relays.filter { it.write }.map { it.url }
                    } else {
                        outboxManager.bootstrapRelays
                    }

                    // Fetch metadata from their WRITE relays
                    writeRelays.take(3).forEach { relayUrl ->
                        try {
                            val client = connectionManager.getOrConnectRelay(relayUrl, messageHandler)
                            client?.requestMetadata(listOf(pubkey))
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Request an event by ID using outbox model
     */
    suspend fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
        messageHandler: (String, NostrGroupClient) -> Unit
    ) {
        // Skip if already cached
        if (_cachedEvents.value.containsKey(eventId)) {
            return
        }

        // If we have an author, try to get their relay list first
        if (author != null && outboxManager.getCachedRelayList(author).isEmpty()) {
            outboxManager.requestRelayLists(setOf(author), messageHandler)
            kotlinx.coroutines.delay(200)
        }

        // Use outbox model for relay selection
        val relaysToTry = outboxManager.selectOutboxRelays(
            authors = if (author != null) listOf(author) else emptyList(),
            explicitRelays = relayHints
        )

        // Request from all available relays
        for (relayUrl in relaysToTry) {
            try {
                val client = connectionManager.getOrConnectRelay(relayUrl, messageHandler)
                client?.requestEventById(eventId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Request an addressable event (naddr) by its coordinates.
     * Addressable events use kind:pubkey:d-tag as their identifier.
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

        // Skip if already cached
        if (_cachedEvents.value.containsKey(addressKey)) {
            return
        }

        // Try to get author's relay list first
        if (outboxManager.getCachedRelayList(pubkey).isEmpty()) {
            outboxManager.requestRelayLists(setOf(pubkey), messageHandler)
            kotlinx.coroutines.delay(200)
        }

        // Use outbox model for relay selection
        val relaysToTry = outboxManager.selectOutboxRelays(
            authors = listOf(pubkey),
            explicitRelays = relayHints
        )

        // Request from all available relays
        for (relayUrl in relaysToTry) {
            try {
                val client = connectionManager.getOrConnectRelay(relayUrl, messageHandler)
                client?.requestAddressableEvent(kind, pubkey, identifier)
            } catch (_: Exception) {}
        }
    }

    /**
     * Handle incoming metadata message
     */
    fun handleMetadataEvent(pubkey: String, metadata: UserMetadata) {
        metadataCache.put(pubkey, metadata)
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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if we have metadata for a pubkey
     */
    fun hasMetadata(pubkey: String): Boolean = metadataCache.containsKey(pubkey)

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
        _userMetadata.value = emptyMap()
        _cachedEvents.value = emptyMap()
    }
}
