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
     * Handle incoming metadata message
     */
    fun handleMetadataEvent(pubkey: String, metadata: UserMetadata) {
        _userMetadata.value = _userMetadata.value + (pubkey to metadata)
    }

    /**
     * Handle incoming cached event
     */
    fun handleCachedEvent(event: CachedEvent) {
        _cachedEvents.value = _cachedEvents.value + (event.id to event)
    }

    /**
     * Parse and cache event from JSON
     */
    fun parseAndCacheEvent(eventJson: JsonObject): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null

            // Skip if already cached
            if (_cachedEvents.value.containsKey(eventId)) {
                return _cachedEvents.value[eventId]
            }

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

            _cachedEvents.value = _cachedEvents.value + (eventId to cachedEvent)
            cachedEvent
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if we have metadata for a pubkey
     */
    fun hasMetadata(pubkey: String): Boolean = _userMetadata.value.containsKey(pubkey)

    /**
     * Check if we have a cached event
     */
    fun hasCachedEvent(eventId: String): Boolean = _cachedEvents.value.containsKey(eventId)

    /**
     * Get metadata for a pubkey
     */
    fun getMetadata(pubkey: String): UserMetadata? = _userMetadata.value[pubkey]

    /**
     * Get cached event by ID
     */
    fun getCachedEvent(eventId: String): CachedEvent? = _cachedEvents.value[eventId]

    /**
     * Clear all cached data
     */
    fun clear() {
        _userMetadata.value = emptyMap()
        _cachedEvents.value = emptyMap()
    }
}
