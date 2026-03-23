package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages NIP-65 Outbox model operations.
 * Handles relay list fetching, caching, and relay selection for publishing/reading.
 */
class OutboxManager(
    private val connectionManager: ConnectionManager,
    private val relayListManager: RelayListManager,
    private val scope: CoroutineScope
) {
    val bootstrapRelays: List<String> = relayListManager.bootstrapRelays

    // Expose user's relay list
    val userRelayList: StateFlow<List<Nip65Relay>> = relayListManager.myRelayList

    // Track kind:10009 subscription state - simple flags for coroutine coordination
    private var kind10009SubId: String? = null
    private var kind10009Received = false
    private var eoseReceived = false

    // All groups across all relays (for kind:10009) - protected by groupsMutex
    private val groupsMutex = Mutex()
    private var allRelayGroups: Map<String, Set<String>> = emptyMap()
    // Timestamp of the last accepted kind:10009; older arrivals are discarded.
    private var latestKind10009CreatedAt: Long = 0

    /**
     * Initialize outbox model for the current user
     */
    fun initialize(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit
    ) {
        // Connect to bootstrap relays in background — loadJoinedGroupsFromNostr connects
        // to these same relays itself via getOrConnectRelay, so no need to await them here.
        val bootstrapToConnect = bootstrapRelays.take(3)
        bootstrapToConnect.forEach { url ->
            scope.launch {
                try { connectionManager.getOrConnectRelay(url, messageHandler) } catch (_: Exception) {}
            }
        }

        // Fetch NIP-65 relay list in background
        scope.launch {
            loadUserRelayList(pubKey, messageHandler)
        }

        // Load joined groups in background
        scope.launch {
            loadJoinedGroupsFromNostr(pubKey, messageHandler)
        }
    }

    /**
     * Load user's NIP-65 relay list (kind:10002)
     */
    private suspend fun loadUserRelayList(pubKey: String, messageHandler: (String, NostrGroupClient) -> Unit) {
        try {
            val relays = getRelayList(pubKey)
            if (relays.isNotEmpty()) {
                relayListManager.setMyRelayList(pubKey, relays)
            }
        } catch (_: Exception) {}
    }

    /**
     * Load joined groups from Nostr (kind:10009).
     *
     * Queries all write relays AND all bootstrap relays in parallel so that
     * kind:10009 events published to any of them (e.g. purplepag.es as a
     * fallback write relay) are discovered correctly.
     */
    suspend fun loadJoinedGroupsFromNostr(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit
    ): Set<String> {
        val relaysToQuery = (relayListManager.selectPublishRelays() + bootstrapRelays).distinct()

        kind10009Received = false
        eoseReceived = false

        val subId = "joined-groups-${epochMillis()}"
        kind10009SubId = subId

        val reqMessage = buildJsonArray {
            add("REQ")
            add(subId)
            add(buildJsonObject {
                putJsonArray("kinds") { add(10009) }
                putJsonArray("authors") { add(pubKey) }
                put("limit", 1)
            })
        }.toString()

        val connectedClients = mutableListOf<NostrGroupClient>()
        for (relayUrl in relaysToQuery) {
            try {
                val client = connectionManager.getOrConnectRelay(relayUrl, messageHandler)
                if (client != null) {
                    client.send(reqMessage)
                    connectedClients.add(client)
                }
            } catch (_: Exception) {}
        }

        if (connectedClients.isNotEmpty()) {
            // Exit as soon as event arrives; 5s timeout for accounts with no kind:10009.
            var waitTime = 0
            while (!kind10009Received && waitTime < 5000) {
                delay(100)
                waitTime += 100
            }

            val closeMsg = buildJsonArray {
                add("CLOSE")
                add(subId)
            }.toString()
            connectedClients.forEach { client ->
                try { client.send(closeMsg) } catch (_: Exception) {}
            }
        }

        return groupsMutex.withLock {
            allRelayGroups.values.flatten().toSet()
        }
    }

    /**
     * Publish joined groups list (kind:10009)
     */
    suspend fun publishJoinedGroupsList(
        pubKey: String,
        joinedGroups: Set<String>,
        currentRelayUrl: String,
        nip29Relays: List<String>,
        signEvent: suspend (Event) -> Event,
        messageHandler: (String, NostrGroupClient) -> Unit
    ): Result<Unit> {
        return try {
            val tags = groupsMutex.withLock {
                allRelayGroups = allRelayGroups + (currentRelayUrl to joinedGroups)

                val tagsList = mutableListOf<List<String>>()
                // ["group", groupId, relayUrl] for each joined group
                allRelayGroups.forEach { (relayUrl, groupIds) ->
                    groupIds.forEach { groupId ->
                        tagsList.add(listOf("group", groupId, relayUrl))
                    }
                }
                // ["r", relayUrl] for each NIP-29 relay the user has added
                nip29Relays.filter { it.isNotBlank() }.distinct().forEach { relayUrl ->
                    tagsList.add(listOf("r", relayUrl))
                }
                tagsList
            }

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 10009,
                tags = tags,
                content = ""
            )

            val signedEvent = signEvent(event)
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            val targets = relayListManager.selectPublishRelays()
            targets.forEach { relayUrl ->
                scope.launch {
                    try {
                        val client = connectionManager.getOrConnectRelay(relayUrl, messageHandler)
                            ?: return@launch
                        client.sendAndAwaitOk(message, eventId)
                    } catch (_: Exception) {}
                }
            }

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown("Failed to publish joined groups", e))
        }
    }

    /**
     * Handle kind:10009 event (joined groups)
     */
    suspend fun handleKind10009Event(
        event: JsonObject,
        currentRelayUrl: String,
        pubKey: String,
        onGroupsUpdated: (Set<String>) -> Unit,
        onRelaysRestored: suspend (List<String>) -> Unit = {},
        onRelayGroupsUpdated: (Map<String, Set<String>>) -> Unit = {},
        messageHandler: (String, NostrGroupClient) -> Unit = { _, _ -> }
    ) {
        kind10009Received = true
        val tags = event["tags"]?.jsonArray ?: return

        // Discard if older than OR equal to the last accepted event (deduplicates the same
        // event arriving from multiple bootstrap relays, preventing redundant connection races).
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        groupsMutex.withLock {
            if (createdAt <= latestKind10009CreatedAt) return
            latestKind10009CreatedAt = createdAt
        }

        val newRelayGroups = mutableMapOf<String, MutableSet<String>>()
        val explicitNip29Relays = mutableListOf<String>()

        tags.forEach { tag ->
            val tagArray = tag.jsonArray
            val tagName = tagArray.getOrNull(0)?.jsonPrimitive?.content ?: return@forEach
            when (tagName) {
                "group" -> {
                    val groupId = tagArray.getOrNull(1)?.jsonPrimitive?.content ?: return@forEach
                    val relayUrl = tagArray.getOrNull(2)?.jsonPrimitive?.content ?: currentRelayUrl
                    newRelayGroups.getOrPut(relayUrl) { mutableSetOf() }.add(groupId)
                }
                "r" -> {
                    val relayUrl = tagArray.getOrNull(1)?.jsonPrimitive?.content ?: return@forEach
                    if (relayUrl.isNotBlank()) explicitNip29Relays.add(relayUrl)
                }
            }
        }

        val immutableRelayGroups = newRelayGroups.mapValues { it.value.toSet() }
        groupsMutex.withLock {
            allRelayGroups = immutableRelayGroups
        }

        // Restore NIP-29 relay list from "r" tags if present; fall back to extracting
        // from "group" tags for events published by older versions of the app.
        val restoredRelays = if (explicitNip29Relays.isNotEmpty()) {
            explicitNip29Relays.distinct()
        } else {
            newRelayGroups.keys.toList()
        }
        var newlyRestoredRelays = emptyList<String>()
        if (restoredRelays.isNotEmpty()) {
            val existing = SecureStorage.loadRelayList()
            val merged = (existing + restoredRelays).distinct()
            if (merged != existing) {
                SecureStorage.saveRelayList(merged)
                newlyRestoredRelays = merged.filter { it !in existing }
            }
        }

        // Save joined groups for ALL relays found in the event so that switching
        // relays later can load membership from storage without a network fetch.
        immutableRelayGroups.forEach { (relayUrl, groups) ->
            SecureStorage.saveJoinedGroupsForRelay(pubKey, relayUrl, groups)
        }

        // Update in-memory per-relay joined groups for ALL relays at once.
        onRelayGroupsUpdated(immutableRelayGroups)

        // Surface only current-relay groups to the active UI.
        // Only call onGroupsUpdated when the current relay is actually in the event;
        // avoid wiping the UI with an empty set when the event belongs to other relays.
        val currentRelayGroups = immutableRelayGroups[currentRelayUrl]
        if (currentRelayGroups != null) {
            onGroupsUpdated(currentRelayGroups)
        }

        // Notify caller about newly discovered relays so it can connect and request groups.
        if (newlyRestoredRelays.isNotEmpty()) {
            onRelaysRestored(newlyRestoredRelays)
        }

        // Connect to relays from kind:10009 using the caller's message handler so that
        // group events (kind 39000) are not silently dropped by a no-op handler.
        connectToKind10009Relays(messageHandler)
    }

    /**
     * Handle kind:10002 event (NIP-65 relay list)
     */
    fun handleKind10002Event(event: JsonObject, currentUserPubkey: String?) {
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
        val isCurrentUser = eventPubkey == currentUserPubkey

        val tags = event["tags"]?.jsonArray ?: return

        val relays = mutableListOf<Nip65Relay>()
        tags.forEach { tag ->
            val tagArray = tag.jsonArray
            if (tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "r") {
                val relayUrl = tagArray[1].jsonPrimitive.content
                val marker = tagArray.getOrNull(2)?.jsonPrimitive?.content

                val relay = when (marker) {
                    "read" -> Nip65Relay(relayUrl, read = true, write = false)
                    "write" -> Nip65Relay(relayUrl, read = false, write = true)
                    else -> Nip65Relay(relayUrl, read = true, write = true)
                }
                relays.add(relay)
            }
        }

        if (eventPubkey != null && relays.isNotEmpty()) {
            if (isCurrentUser) {
                relayListManager.setMyRelayList(eventPubkey, relays)
            } else {
                relayListManager.cacheRelayListForUser(eventPubkey, relays)
            }
        }
    }

    /**
     * Handle EOSE for kind:10009 subscription
     */
    fun handleEose(subId: String) {
        if (subId == kind10009SubId) {
            eoseReceived = true
        }
    }

    /**
     * Connect to relays discovered from kind:10009
     */
    private fun connectToKind10009Relays(messageHandler: (String, NostrGroupClient) -> Unit) {
        // Take a snapshot of relay URLs to avoid holding the lock during connection
        val relayUrls = allRelayGroups.keys.toList()
        if (relayUrls.isEmpty()) return

        relayUrls.forEach { relayUrl ->
            scope.launch {
                try {
                    connectionManager.getOrConnectRelay(relayUrl, messageHandler)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Get relay list for a pubkey (with caching)
     */
    suspend fun getRelayList(pubkey: String): List<Nip65Relay> {
        return relayListManager.getRelayList(pubkey)
    }

    /**
     * Get cached relay list for a pubkey
     */
    fun getCachedRelayList(pubkey: String): List<Nip65Relay> {
        return relayListManager.getCachedRelayList(pubkey)
    }

    /**
     * Request relay lists for given pubkeys
     */
    fun requestRelayLists(pubkeys: Set<String>, messageHandler: (String, NostrGroupClient) -> Unit) {
        pubkeys.forEach { pubkey ->
            scope.launch {
                try {
                    relayListManager.getRelayList(pubkey)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Select relays based on outbox model (NIP-65)
     */
    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList(),
        currentNip29Relay: String? = null
    ): List<String> {
        val relays = mutableListOf<String>()
        var hasAuthorRelays = false

        // 1. Explicit relays always come first
        explicitRelays.forEach { relay ->
            if (relay.isNotBlank() && relay !in relays) {
                relays.add(relay)
            }
        }

        // 2. If we have authors, use their WRITE relays (outbox)
        if (authors.isNotEmpty()) {
            authors.forEach { author ->
                val authorRelays = getCachedRelayList(author)
                if (authorRelays.isNotEmpty()) {
                    hasAuthorRelays = true
                }
                authorRelays
                    .filter { it.write }
                    .forEach { relay ->
                        if (relay.url !in relays) {
                            relays.add(relay.url)
                        }
                    }
            }
        }

        // 3. If we have tagged pubkeys, use their READ relays (inbox)
        if (taggedPubkeys.isNotEmpty()) {
            taggedPubkeys.forEach { pubkey ->
                val pubkeyRelays = getCachedRelayList(pubkey)
                pubkeyRelays
                    .filter { it.read }
                    .forEach { relay ->
                        if (relay.url !in relays) {
                            relays.add(relay.url)
                        }
                    }
            }
        }

        // 4. If no authors or tagged users, use current user's READ relays
        if (authors.isEmpty() && taggedPubkeys.isEmpty()) {
            val myRelays = relayListManager.myRelayList.value
            if (myRelays.isNotEmpty()) {
                hasAuthorRelays = true
            }
            myRelays
                .filter { it.read }
                .forEach { relay ->
                    if (relay.url !in relays) {
                        relays.add(relay.url)
                    }
                }
        }

        // 5. Add current NIP-29 relay as fallback
        if (currentNip29Relay != null && currentNip29Relay !in relays) {
            relays.add(currentNip29Relay)
        }

        // 6. Always add fallback bootstrap relays
        bootstrapRelays.forEach { relay ->
            if (relay !in relays) {
                relays.add(relay)
            }
        }

        return relays
    }

    /**
     * Get write relays for publishing events
     */
    fun getWriteRelays(): List<String> = relayListManager.selectPublishRelays()

    fun updateMyRelayList(pubkey: String, relays: List<Nip65Relay>) {
        relayListManager.setMyRelayList(pubkey, relays)
    }

    /**
     * Get joined groups for a specific relay from the in-memory cache.
     * Used when switching relays to instantly restore membership state without
     * waiting for a new kind:10009 network fetch.
     */
    suspend fun getJoinedGroupsForRelay(relayUrl: String): Set<String> {
        return groupsMutex.withLock {
            allRelayGroups[relayUrl] ?: emptySet()
        }
    }

    /**
     * Returns true if kind:10009 data has already been loaded from the network.
     * Used to skip redundant re-fetches on relay switch.
     */
    suspend fun hasJoinedGroupsData(): Boolean {
        return groupsMutex.withLock { allRelayGroups.isNotEmpty() }
    }

    /**
     * Reset kind:10009 state
     */
    fun resetKind10009State() {
        kind10009Received = false
        eoseReceived = false
    }

    /**
     * Clear all state
     */
    suspend fun clear() {
        relayListManager.clear()
        groupsMutex.withLock {
            allRelayGroups = emptyMap()
            latestKind10009CreatedAt = 0
        }
        kind10009Received = false
        eoseReceived = false
    }
}
