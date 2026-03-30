package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    companion object {
        /** How long to wait after the first kind:10009 event for slower relays to respond with newer versions. */
        const val DISCOVERY_SETTLE_MS = 1_500L
    }

    val bootstrapRelays: List<String> = relayListManager.bootstrapRelays

    val userRelayList: StateFlow<List<Nip65Relay>> = relayListManager.myRelayList

    private var kind10009SubId: String? = null
    private var kind10009Received = false
    private var eoseReceived = false

    private val groupsMutex = Mutex()
    private var allRelayGroups: Map<String, Set<String>> = emptyMap()
    private var latestKind10009CreatedAt: Long = 0

    private val _kind10009Relays = MutableStateFlow<Set<String>>(emptySet())
    val kind10009Relays: StateFlow<Set<String>> = _kind10009Relays.asStateFlow()

    fun initialize(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit,
        onDiscoveryComplete: (() -> Unit)? = null
    ) {
        scope.launch {
            coroutineScope {
                bootstrapRelays.forEach { url ->
                    launch {
                        try { connectionManager.getOrConnectRelay(url, messageHandler) } catch (_: Exception) {}
                    }
                }
            }

            coroutineScope {
                launch { loadUserRelayList(pubKey, messageHandler) }
                launch { loadJoinedGroupsFromNostr(pubKey, messageHandler) }
            }
            onDiscoveryComplete?.invoke()
        }
    }

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
                val client = connectionManager.getClientForRelay(relayUrl)
                if (client != null && client.isConnected()) {
                    client.send(reqMessage)
                    connectedClients.add(client)
                }
            } catch (_: Exception) {}
        }

        if (connectedClients.isNotEmpty()) {
            // Wait for the first event, then keep listening briefly so slower relays
            // that may hold a NEWER version can still deliver it. The timestamp guard
            // in handleKind10009Event ensures only the latest event wins.
            var waitTime = 0
            while (!kind10009Received && waitTime < 5000) {
                delay(100)
                waitTime += 100
            }
            // After the first event, wait a short window for other relays to respond
            // with potentially newer versions before closing.
            if (kind10009Received) {
                delay(DISCOVERY_SETTLE_MS)
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

    suspend fun publishJoinedGroupsList(
        pubKey: String,
        joinedGroupsByRelay: Map<String, Set<String>>,
        nip29Relays: List<String>,
        signEvent: suspend (Event) -> Event,
        messageHandler: (String, NostrGroupClient) -> Unit
    ): Result<Unit> {
        return try {
            val tags = groupsMutex.withLock {
                allRelayGroups = joinedGroupsByRelay.filterValues { it.isNotEmpty() }

                val tagsList = mutableListOf<List<String>>()
                allRelayGroups.forEach { (relayUrl, groupIds) ->
                    groupIds.forEach { groupId ->
                        tagsList.add(listOf("group", groupId, relayUrl))
                    }
                }
                val distinctRelays = nip29Relays.filter { it.isNotBlank() }.distinct()
                distinctRelays.forEach { relayUrl ->
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

            groupsMutex.withLock {
                latestKind10009CreatedAt = event.createdAt
            }

            _kind10009Relays.value = nip29Relays.filter { it.isNotBlank() }.toSet()
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            val targets = (relayListManager.selectPublishRelays() + bootstrapRelays).distinct()
            val published = targets.mapNotNull { relayUrl ->
                connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
            }
            val clients = if (published.isEmpty()) {
                listOfNotNull(connectionManager.getPrimaryClient())
            } else published
            clients.forEach { client ->
                scope.launch {
                    try { client.sendAndAwaitOk(message, eventId) } catch (_: Exception) {}
                }
            }

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown("Failed to publish joined groups", e))
        }
    }

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

        _kind10009Relays.value = explicitNip29Relays.toSet()

        // Fall back to "group" tag relay URLs for events without "r" tags (older app versions).
        val restoredRelays = if (explicitNip29Relays.isNotEmpty()) {
            explicitNip29Relays.distinct()
        } else {
            newRelayGroups.keys.toList()
        }
        var newlyRestoredRelays = emptyList<String>()
        if (restoredRelays.isNotEmpty()) {
            val existing = SecureStorage.loadRelayList()
            if (restoredRelays.toSet() != existing.toSet()) {
                SecureStorage.saveRelayList(restoredRelays)
                newlyRestoredRelays = restoredRelays.filter { it !in existing }
            }
        }

        immutableRelayGroups.forEach { (relayUrl, groups) ->
            SecureStorage.saveJoinedGroupsForRelay(pubKey, relayUrl, groups)
        }

        onRelayGroupsUpdated(immutableRelayGroups)

        val currentRelayGroups = immutableRelayGroups[currentRelayUrl]
        if (currentRelayGroups != null) {
            onGroupsUpdated(currentRelayGroups)
        }

        if (newlyRestoredRelays.isNotEmpty()) {
            onRelaysRestored(newlyRestoredRelays)
        }
    }

    fun handleKind10002Event(event: JsonObject, currentUserPubkey: String?) {
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
        val isCurrentUser = eventPubkey == currentUserPubkey
        val eventCreatedAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L

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
                relayListManager.setMyRelayList(eventPubkey, relays, eventCreatedAt)
            } else {
                relayListManager.cacheRelayListForUser(eventPubkey, relays, eventCreatedAt)
            }
        }
    }

    fun handleEose(subId: String) {
        if (subId == kind10009SubId) {
            eoseReceived = true
        }
    }

    suspend fun getRelayList(pubkey: String): List<Nip65Relay> {
        return relayListManager.getRelayList(pubkey)
    }

    fun getCachedRelayList(pubkey: String): List<Nip65Relay> {
        return relayListManager.getCachedRelayList(pubkey)
    }

    fun requestRelayLists(pubkeys: Set<String>, messageHandler: (String, NostrGroupClient) -> Unit) {
        pubkeys.forEach { pubkey ->
            scope.launch {
                try {
                    relayListManager.getRelayList(pubkey)
                } catch (_: Exception) {}
            }
        }
    }

    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList(),
        currentNip29Relay: String? = null
    ): List<String> {
        val relays = mutableListOf<String>()

        // 1. Explicit relays always come first
        explicitRelays.forEach { relay ->
            if (relay.isNotBlank() && relay !in relays) {
                relays.add(relay)
            }
        }

        if (authors.isNotEmpty()) {
            authors.forEach { author ->
                val authorRelays = getCachedRelayList(author)
                authorRelays
                    .filter { it.write }
                    .forEach { relay ->
                        if (relay.url !in relays) {
                            relays.add(relay.url)
                        }
                    }
            }
        }

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

        if (authors.isEmpty() && taggedPubkeys.isEmpty()) {
            val myRelays = relayListManager.myRelayList.value
            myRelays
                .filter { it.read }
                .forEach { relay ->
                    if (relay.url !in relays) {
                        relays.add(relay.url)
                    }
                }
        }

        if (currentNip29Relay != null && currentNip29Relay !in relays) {
            relays.add(currentNip29Relay)
        }

        bootstrapRelays.forEach { relay ->
            if (relay !in relays) {
                relays.add(relay)
            }
        }

        return relays
    }

    suspend fun selectConnectedOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList(),
        currentNip29Relay: String? = null
    ): List<String> {
        return selectOutboxRelays(authors, taggedPubkeys, explicitRelays, currentNip29Relay)
            .filter { url ->
                val client = connectionManager.getClientForRelay(url)
                client != null && client.isConnected()
            }
    }

    fun getWriteRelays(): List<String> = relayListManager.selectPublishRelays()

    fun updateMyRelayList(pubkey: String, relays: List<Nip65Relay>) {
        relayListManager.setMyRelayList(pubkey, relays)
    }

    suspend fun getJoinedGroupsForRelay(relayUrl: String): Set<String> {
        return groupsMutex.withLock {
            allRelayGroups[relayUrl] ?: emptySet()
        }
    }

    suspend fun removeRelayFromCache(relayUrl: String) {
        groupsMutex.withLock {
            allRelayGroups = allRelayGroups - relayUrl
        }
        _kind10009Relays.value = _kind10009Relays.value - relayUrl
    }

    suspend fun hasJoinedGroupsData(): Boolean {
        return groupsMutex.withLock { allRelayGroups.isNotEmpty() }
    }

    fun resetKind10009State() {
        kind10009Received = false
        eoseReceived = false
    }

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
