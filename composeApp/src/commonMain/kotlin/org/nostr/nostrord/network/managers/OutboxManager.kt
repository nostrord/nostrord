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
    private var kind10002Received = false

    // All groups across all relays (for kind:10009) - protected by groupsMutex
    private val groupsMutex = Mutex()
    private var allRelayGroups: Map<String, Set<String>> = emptyMap()

    init {
        // Share relay pool with RelayListManager
        relayListManager.setConnectionProvider { relayUrl ->
            // This will be connected via ConnectionManager when needed
            null // Placeholder - actual connection happens through requestRelayLists
        }
    }

    /**
     * Initialize outbox model for the current user
     */
    suspend fun initialize(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit
    ) {
        // Connect to bootstrap relays immediately
        val bootstrapToConnect = bootstrapRelays.take(3)
        connectionManager.connectToRelaysParallel(bootstrapToConnect, messageHandler)

        // Fetch NIP-65 relay list in background
        scope.launch {
            loadUserRelayList(pubKey, messageHandler)
        }

        // Load joined groups in background
        scope.launch {
            delay(300)
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
     * Load joined groups from Nostr (kind:10009)
     */
    suspend fun loadJoinedGroupsFromNostr(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit
    ): Set<String> {
        val writeRelays = relayListManager.selectPublishRelays()
        if (writeRelays.isEmpty()) {
            return emptySet()
        }

        val relayUrl = writeRelays.first()
        val currentClient = connectionManager.getOrConnectRelay(relayUrl, messageHandler) ?: return emptySet()

        try {
            kind10009Received = false
            eoseReceived = false

            val filter = buildJsonObject {
                putJsonArray("kinds") { add(10009) }
                putJsonArray("authors") { add(pubKey) }
                put("limit", 1)
            }

            val subId = "joined-groups-${epochMillis()}"
            kind10009SubId = subId

            val message = buildJsonArray {
                add("REQ")
                add(subId)
                add(filter)
            }.toString()

            currentClient.send(message)

            var waitTime = 0
            while (!eoseReceived && waitTime < 3000) {
                delay(100)
                waitTime += 100
            }

            val closeMsg = buildJsonArray {
                add("CLOSE")
                add(subId)
            }.toString()
            currentClient.send(closeMsg)

            return groupsMutex.withLock {
                allRelayGroups[connectionManager.currentRelayUrl.value] ?: emptySet()
            }
        } catch (_: Exception) {
            return emptySet()
        }
    }

    /**
     * Publish joined groups list (kind:10009)
     */
    suspend fun publishJoinedGroupsList(
        pubKey: String,
        joinedGroups: Set<String>,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        messageHandler: (String, NostrGroupClient) -> Unit
    ): Result<Unit> {
        return try {
            val tags = groupsMutex.withLock {
                allRelayGroups = allRelayGroups + (currentRelayUrl to joinedGroups)

                val tagsList = mutableListOf<List<String>>()
                allRelayGroups.forEach { (relayUrl, groupIds) ->
                    groupIds.forEach { groupId ->
                        tagsList.add(listOf("group", groupId, relayUrl))
                    }
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

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to user's WRITE relays
            val writeRelays = relayListManager.selectPublishRelays()
            connectionManager.sendToRelays(writeRelays, message, messageHandler)
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
        onGroupsUpdated: (Set<String>) -> Unit
    ) {
        kind10009Received = true
        val tags = event["tags"]?.jsonArray ?: return

        val currentRelayGroups = mutableSetOf<String>()
        val newRelayGroups = mutableMapOf<String, MutableSet<String>>()

        tags.forEach { tag ->
            val tagArray = tag.jsonArray
            if (tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "group") {
                val groupId = tagArray[1].jsonPrimitive.content
                val relayUrl = tagArray.getOrNull(2)?.jsonPrimitive?.content

                if (relayUrl != null) {
                    newRelayGroups.getOrPut(relayUrl) { mutableSetOf() }.add(groupId)
                    if (relayUrl == currentRelayUrl) {
                        currentRelayGroups.add(groupId)
                    }
                } else {
                    currentRelayGroups.add(groupId)
                    newRelayGroups.getOrPut(currentRelayUrl) { mutableSetOf() }.add(groupId)
                }
            }
        }

        // Update allRelayGroups atomically
        groupsMutex.withLock {
            allRelayGroups = newRelayGroups.mapValues { it.value.toSet() }
        }

        SecureStorage.saveJoinedGroupsForRelay(pubKey, currentRelayUrl, currentRelayGroups)
        onGroupsUpdated(currentRelayGroups)

        // Connect to relays from kind:10009
        connectToKind10009Relays { _, _ -> }
    }

    /**
     * Handle kind:10002 event (NIP-65 relay list)
     */
    fun handleKind10002Event(event: JsonObject, currentUserPubkey: String?) {
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
        val isCurrentUser = eventPubkey == currentUserPubkey

        if (isCurrentUser) {
            kind10002Received = true
        }

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
     * Get all relay groups (for kind:10009)
     */
    fun getAllRelayGroups(): Map<String, Set<String>> = allRelayGroups

    /**
     * Update groups for a specific relay
     */
    suspend fun updateRelayGroups(relayUrl: String, groups: Set<String>) {
        groupsMutex.withLock {
            allRelayGroups = allRelayGroups + (relayUrl to groups)
        }
    }

    /**
     * Check if kind:10009 was received
     */
    fun wasKind10009Received(): Boolean = kind10009Received

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
        }
        kind10009Received = false
        eoseReceived = false
        kind10002Received = false
    }
}
