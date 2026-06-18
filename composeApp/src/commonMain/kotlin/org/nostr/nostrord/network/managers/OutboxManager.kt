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
import org.nostr.nostrord.storage.loadKind10009Timestamp
import org.nostr.nostrord.storage.loadRelayListFor
import org.nostr.nostrord.storage.saveKind10009Timestamp
import org.nostr.nostrord.storage.saveRelayListFor
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Manages NIP-65 Outbox model operations.
 * Handles relay list fetching, caching, and relay selection for publishing/reading.
 */
class OutboxManager(
    private val connectionManager: ConnectionManager,
    private val relayListManager: RelayListManager,
    private val scope: CoroutineScope,
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

    /** Relay URLs that appear in "group" tags but NOT in "r" tags.
     *  These are implicit/temporary — shown in the rail but never persisted. */
    private val _groupTagRelays = MutableStateFlow<Set<String>>(emptySet())
    val groupTagRelays: StateFlow<Set<String>> = _groupTagRelays.asStateFlow()

    /** Recalculate implicit relays = allRelayGroups keys NOT in explicit "r" tags. */
    private fun refreshGroupTagRelays() {
        _groupTagRelays.value =
            allRelayGroups.keys
                .map { it.normalizeRelayUrl() }
                .filter { it.isNotBlank() && it !in _kind10009Relays.value }
                .toSet()
    }

    /**
     * Seed [kind10009Relays] from the locally-persisted relay list for [pubKey]
     * so the sidebar shows all joined relays instantly on startup, without
     * waiting for the kind:10009 network fetch. The network fetch still runs
     * and overwrites this with fresh data.
     *
     * Pubkey-scoped: a blank pubkey (no active session) starts with an empty
     * set so a freshly added account never inherits another account's relays.
     */
    fun seedFromCache(pubKey: String) {
        val saved =
            if (pubKey.isBlank()) {
                emptySet()
            } else {
                SecureStorage
                    .loadRelayListFor(pubKey)
                    .map { it.normalizeRelayUrl() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        _kind10009Relays.value = saved
        // Timestamp is pubkey-scoped (see initialize()). seedFromCache may run
        // before login so we don't always know the pubkey yet — just start at
        // 0 and let initialize() rehydrate the right scope when login completes.
        latestKind10009CreatedAt = 0
    }

    fun initialize(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit,
        onDiscoveryComplete: (() -> Unit)? = null,
    ) {
        // Rehydrate the freshness floor for THIS account. Without pubkey scoping,
        // a previous account's high timestamp would bleed in and reject the new
        // account's (legitimately older) kind:10009 as "stale", leaving the
        // sidebar empty until restart.
        latestKind10009CreatedAt = SecureStorage.loadKind10009Timestamp(pubKey)
        scope.launch {
            coroutineScope {
                bootstrapRelays.forEach { url ->
                    launch {
                        try {
                            connectionManager.getOrConnectRelay(url, messageHandler)
                        } catch (_: Exception) {
                        }
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

    private suspend fun loadUserRelayList(
        pubKey: String,
        messageHandler: (String, NostrGroupClient) -> Unit,
    ) {
        try {
            val relays = getRelayList(pubKey)
            if (relays.isNotEmpty()) {
                relayListManager.setMyRelayList(pubKey, relays)
            }
        } catch (_: Exception) {
        }
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
        messageHandler: (String, NostrGroupClient) -> Unit,
    ): Set<String> {
        val relaysToQuery = (relayListManager.selectPublishRelays() + bootstrapRelays).distinct()

        kind10009Received = false
        eoseReceived = false

        val subId = "joined-groups-${epochMillis()}"
        kind10009SubId = subId

        val reqMessage =
            buildJsonArray {
                add("REQ")
                add(subId)
                add(
                    buildJsonObject {
                        putJsonArray("kinds") { add(10009) }
                        putJsonArray("authors") { add(pubKey) }
                        put("limit", 1)
                    },
                )
            }.toString()

        val connectedClients = mutableListOf<NostrGroupClient>()
        for (relayUrl in relaysToQuery) {
            try {
                val client = connectionManager.getClientForRelay(relayUrl)
                if (client != null && client.isConnected()) {
                    client.send(reqMessage)
                    connectedClients.add(client)
                }
            } catch (_: Exception) {
            }
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

            val closeMsg =
                buildJsonArray {
                    add("CLOSE")
                    add(subId)
                }.toString()
            connectedClients.forEach { client ->
                try {
                    client.send(closeMsg)
                } catch (_: Exception) {
                }
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
        messageHandler: (String, NostrGroupClient) -> Unit,
    ): Result<Unit> {
        return try {
            val tags =
                groupsMutex.withLock {
                    // Normalize and deduplicate relay URLs before publishing
                    val normalizedGroups = mutableMapOf<String, MutableSet<String>>()
                    joinedGroupsByRelay.filterValues { it.isNotEmpty() }.forEach { (relayUrl, groupIds) ->
                        val normalized = relayUrl.normalizeRelayUrl()
                        normalizedGroups.getOrPut(normalized) { mutableSetOf() }.addAll(groupIds)
                    }
                    allRelayGroups = normalizedGroups.mapValues { it.value.toSet() }

                    val tagsList = mutableListOf<List<String>>()
                    allRelayGroups.forEach { (relayUrl, groupIds) ->
                        groupIds.forEach { groupId ->
                            tagsList.add(listOf("group", groupId, relayUrl))
                        }
                    }
                    val distinctRelays = nip29Relays.map { it.normalizeRelayUrl() }.filter { it.isNotBlank() }.distinct()
                    distinctRelays.forEach { relayUrl ->
                        tagsList.add(listOf("r", relayUrl))
                    }

                    tagsList
                }

            val event =
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 10009,
                    tags = tags,
                    content = "",
                )

            val signedEvent = signEvent(event)

            _kind10009Relays.value = nip29Relays.map { it.normalizeRelayUrl() }.filter { it.isNotBlank() }.toSet()
            refreshGroupTagRelays()
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))

            val message =
                buildJsonArray {
                    add("EVENT")
                    add(signedEvent.toJsonObject())
                }.toString()

            val targets = (relayListManager.selectPublishRelays() + bootstrapRelays).distinct()
            val published =
                targets.mapNotNull { relayUrl ->
                    connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                }
            val clients =
                if (published.isEmpty()) {
                    listOfNotNull(connectionManager.getPrimaryClient())
                } else {
                    published
                }
            clients.forEach { client ->
                scope.launch {
                    try {
                        val publishResult = client.sendAndAwaitOk(message, eventId)
                        // Advance the freshness guard only once a relay actually ACCEPTED
                        // the event. Persisting it unconditionally let a publish that no
                        // relay stored outrun reality, and the guard then dropped the
                        // (older but real) kind:10009 the relays still serve — the local
                        // list got stuck on localStorage forever.
                        if (publishResult is org.nostr.nostrord.network.PublishResult.Success) {
                            groupsMutex.withLock {
                                if (event.createdAt > latestKind10009CreatedAt) {
                                    latestKind10009CreatedAt = event.createdAt
                                }
                            }
                            SecureStorage.saveKind10009Timestamp(pubKey, event.createdAt)
                        }
                    } catch (_: Exception) {
                    }
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
        messageHandler: (String, NostrGroupClient) -> Unit = { _, _ -> },
        isGroupDropped: (String) -> Boolean = { false },
    ) {
        // Author guard: relays may deliver kind:10009 events for the *previous*
        // account if a subscription stayed open across an account switch. The
        // event's `r` tags would then be persisted under the new account's
        // slot, e.g. fresh accounts inheriting groups.0xchat.com from the
        // account that was active before the switch. Drop mismatches outright.
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
        if (pubKey.isBlank() || eventPubkey != pubKey) return

        kind10009Received = true
        val tags = event["tags"]?.jsonArray ?: return

        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        val isNewest =
            groupsMutex.withLock {
                if (createdAt > latestKind10009CreatedAt) {
                    latestKind10009CreatedAt = createdAt
                    true
                } else {
                    false
                }
            }

        val newRelayGroups = mutableMapOf<String, MutableSet<String>>()
        val explicitNip29Relays = mutableListOf<String>()

        tags.forEach { tag ->
            val tagArray = tag.jsonArray
            val tagName = tagArray.getOrNull(0)?.jsonPrimitive?.content ?: return@forEach
            when (tagName) {
                "group" -> {
                    val groupId = tagArray.getOrNull(1)?.jsonPrimitive?.content ?: return@forEach
                    val relayUrl = (tagArray.getOrNull(2)?.jsonPrimitive?.content ?: currentRelayUrl).normalizeRelayUrl()
                    newRelayGroups.getOrPut(relayUrl) { mutableSetOf() }.add(groupId)
                }
                "r" -> {
                    val relayUrl =
                        tagArray
                            .getOrNull(1)
                            ?.jsonPrimitive
                            ?.content
                            ?.normalizeRelayUrl() ?: return@forEach
                    if (relayUrl.isNotBlank()) explicitNip29Relays.add(relayUrl)
                }
            }
        }

        val immutableRelayGroups = newRelayGroups.mapValues { it.value.toSet() }

        if (!isNewest) {
            // A stale event never REPLACES local state, but groups the local list does
            // not know yet are merged in additively (minus locally-left/deleted ones).
            // The freshness guard can outrun what relays actually stored (a publish
            // accepted by only some relays, clock skew, another client's list); a
            // strict drop then hides the user's real groups behind localStorage
            // forever. Trade-off: a lagging relay can resurrect a group left on
            // another device until the next confirmed publish supersedes it.
            val known = groupsMutex.withLock { allRelayGroups }
            val additions =
                immutableRelayGroups
                    .mapValues { (relay, ids) ->
                        ids.filterNot { it in known[relay].orEmpty() || isGroupDropped(it) }.toSet()
                    }.filterValues { it.isNotEmpty() }
            if (additions.isEmpty()) return
            groupsMutex.withLock {
                val merged = allRelayGroups.toMutableMap()
                additions.forEach { (relay, ids) -> merged[relay] = merged[relay].orEmpty() + ids }
                allRelayGroups = merged.toMap()
            }
            val mergedSlots =
                additions.mapValues { (relay, ids) ->
                    val slot = SecureStorage.getJoinedGroupsForRelay(pubKey, relay) + ids
                    SecureStorage.saveJoinedGroupsForRelay(pubKey, relay, slot)
                    slot
                }
            onRelayGroupsUpdated(mergedSlots)
            val previousList = SecureStorage.loadRelayListFor(pubKey).map { it.normalizeRelayUrl() }
            val addedRelays = additions.keys.filter { it !in previousList }
            if (addedRelays.isNotEmpty()) {
                SecureStorage.saveRelayListFor(pubKey, previousList + addedRelays)
                _kind10009Relays.value = _kind10009Relays.value + addedRelays
                refreshGroupTagRelays()
                onRelaysRestored(addedRelays)
            }
            return
        }

        // The newest kind:10009 is the complete, authoritative list. A relay we knew
        // locally that it no longer lists means every group there was left (possibly on
        // another device) — so its slot must be emptied, not kept. Without this, leaving
        // the LAST group on a relay (its tag drops out of the event entirely) left the
        // group lingering in memory + storage, and the next publish (which unions storage
        // and memory) resurrected it. Capture those relays before swapping the map.
        val droppedRelays: Set<String>
        groupsMutex.withLock {
            droppedRelays = allRelayGroups.keys - immutableRelayGroups.keys
            allRelayGroups = immutableRelayGroups
        }

        // Persisted relay list must include EVERY relay the kind:10009 event
        // references — both explicit "r" tags AND relays implied by "group" tags
        // (where the user has joined groups). Saving only "r" tags clobbers
        // group-bearing relays from persistence; on next launch the rail loses
        // them until kind:10009 is refetched.
        val groupBearingRelays = newRelayGroups.keys.toList()
        val rOnlyRelays = explicitNip29Relays.distinct().filter { it !in groupBearingRelays }
        // Group-bearing relays go first so autoConnectFirstRelay picks something
        // useful — a "r"-only relay that's offline shouldn't strand the user
        // when another relay has their actual groups.
        val allNip29Relays = (groupBearingRelays + rOnlyRelays).distinct()
        val allNip29RelaysSet = allNip29Relays.toSet()
        _kind10009Relays.value = allNip29RelaysSet
        refreshGroupTagRelays()

        val previouslySaved = SecureStorage.loadRelayListFor(pubKey).map { it.normalizeRelayUrl() }.toSet()
        if (allNip29RelaysSet != previouslySaved) {
            SecureStorage.saveRelayListFor(pubKey, allNip29Relays)
        }

        val newlyRestoredRelays = allNip29Relays.filter { it !in previouslySaved }

        immutableRelayGroups.forEach { (relayUrl, groups) ->
            SecureStorage.saveJoinedGroupsForRelay(pubKey, relayUrl, groups)
        }
        // Clear the persisted slots of relays dropped from the authoritative list (and any
        // stored relay it no longer covers) so a group left on another device can't be
        // resurrected by the next publish or re-added by the additive storage restore.
        val staleStoredRelays =
            (droppedRelays + previouslySaved) - immutableRelayGroups.keys
        staleStoredRelays.forEach { relayUrl ->
            SecureStorage.saveJoinedGroupsForRelay(pubKey, relayUrl, emptySet())
        }

        // Include the emptied relays so the in-memory joined map clears them too (the
        // merge `it + relayGroups` overwrites each key, so an empty set removes the group).
        onRelayGroupsUpdated(immutableRelayGroups + droppedRelays.associateWith { emptySet() })

        val normalizedCurrentRelay = currentRelayUrl.normalizeRelayUrl()
        val currentRelayGroups = immutableRelayGroups[normalizedCurrentRelay]
        if (currentRelayGroups != null) {
            onGroupsUpdated(currentRelayGroups)
        }

        if (newlyRestoredRelays.isNotEmpty()) {
            onRelaysRestored(newlyRestoredRelays)
        }
    }

    fun handleKind10002Event(
        event: JsonObject,
        currentUserPubkey: String?,
    ) {
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

                val relay =
                    when (marker) {
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

    suspend fun getRelayList(pubkey: String): List<Nip65Relay> = relayListManager.getRelayList(pubkey)

    fun getCachedRelayList(pubkey: String): List<Nip65Relay> = relayListManager.getCachedRelayList(pubkey)

    fun requestRelayLists(
        pubkeys: Set<String>,
        messageHandler: (String, NostrGroupClient) -> Unit,
    ) {
        pubkeys.forEach { pubkey ->
            scope.launch {
                try {
                    relayListManager.getRelayList(pubkey)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList(),
        currentNip29Relay: String? = null,
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
        currentNip29Relay: String? = null,
    ): List<String> = selectOutboxRelays(authors, taggedPubkeys, explicitRelays, currentNip29Relay)
        .filter { url ->
            val client = connectionManager.getClientForRelay(url)
            client != null && client.isConnected()
        }

    fun getWriteRelays(): List<String> = relayListManager.selectPublishRelays()

    fun updateMyRelayList(
        pubkey: String,
        relays: List<Nip65Relay>,
    ) {
        relayListManager.setMyRelayList(pubkey, relays)
    }

    suspend fun getJoinedGroupsForRelay(relayUrl: String): Set<String> {
        val normalized = relayUrl.normalizeRelayUrl()
        return groupsMutex.withLock {
            allRelayGroups[normalized] ?: emptySet()
        }
    }

    suspend fun removeRelayFromCache(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        groupsMutex.withLock {
            allRelayGroups = allRelayGroups - normalized
        }
        _kind10009Relays.value = _kind10009Relays.value - normalized
        refreshGroupTagRelays()
    }

    suspend fun hasJoinedGroupsData(): Boolean = groupsMutex.withLock { allRelayGroups.isNotEmpty() }

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
        _kind10009Relays.value = emptySet()
        _groupTagRelays.value = emptySet()
        kind10009SubId = null
        kind10009Received = false
        eoseReceived = false
    }
}
