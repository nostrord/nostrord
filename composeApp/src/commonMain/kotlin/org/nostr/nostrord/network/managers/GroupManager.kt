package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.GroupAdmins
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages group operations: join, leave, messages, and group metadata.
 * Handles NIP-29 group protocol operations.
 *
 * Uses a formal state machine (GroupLoadingController) for reliable pagination:
 * - Per-group locks prevent blocking unrelated groups
 * - Explicit state transitions prevent race conditions
 * - Immutable cursors prevent pagination corruption
 */
class GroupManager(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
    private val pendingEventManager: PendingEventManager? = null,
    private val liveCursorStore: LiveCursorStore = LiveCursorStore()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventDeduplicator = EventDeduplicator()

    /**
     * Collects incoming messages per group for 300 ms, then applies them to [_messages]
     * in a single sorted batch — reducing N StateFlow emissions to 1 during burst loads.
     */
    private val eventOrderingBuffer = EventOrderingBuffer(scope) { groupId, messages ->
        flushBatchToState(groupId, messages)
    }

    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()

    // Per-relay group cache — persists across relay switches.
    private val _groupsByRelay = MutableStateFlow<Map<String, List<GroupMetadata>>>(emptyMap())
    val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = _groupsByRelay.asStateFlow()

    // Tracks which relay is currently active and which relays have fully loaded
    // (i.e. received EOSE for the "group-list" subscription).
    private var currentRelayUrl: String? = null
    private val completeGroupLoadRelays = mutableSetOf<String>()

    // The group currently being viewed by the user.
    // Mux chat/reactions subscriptions are scoped to this group only.
    private var _activeGroupId: String? = null

    // Groups that have been opened (clicked) by the user during this session.
    // Chat/reactions mux covers these groups so they keep receiving live messages.
    // Only cleared on full disconnect/logout, NOT on relay switch.
    // Backed by StateFlow for thread-safe reads/writes from any dispatcher.
    private val _openedGroupIds = MutableStateFlow<Set<String>>(emptySet())

    // Debounce mux refresh: coalesces rapid calls (auth + EOSE + CLOSED) into one.
    private val muxRefreshJobs = mutableMapOf<String, Job>()

    // Relays for which requestGroups() was sent but EOSE hasn't arrived yet.
    // Used by the UI to show skeleton loaders while groups are being fetched.
    private val _loadingRelays = MutableStateFlow<Set<String>>(emptySet())
    val loadingRelays: StateFlow<Set<String>> = _loadingRelays.asStateFlow()

    fun markRelayLoading(relayUrl: String) {
        _loadingRelays.update { it + relayUrl }
    }

    fun markRelayLoaded(relayUrl: String) {
        _loadingRelays.update { it - relayUrl }
    }

    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()

    private val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val joinedGroups: StateFlow<Set<String>> = _joinedGroups.asStateFlow()

    // Per-relay joined groups cache — persists across relay view switches
    private val _joinedGroupsByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = _joinedGroupsByRelay.asStateFlow()

    // ==========================================================================
    // STATE MACHINE: Per-group loading controller with formal state transitions
    // ==========================================================================
    private val loadingRegistry = GroupLoadingRegistry(scope, PAGE_SIZE, LOADING_TIMEOUT_MS)

    // Track active group states for UI binding
    private val _groupStates = MutableStateFlow<Map<String, GroupLoadingState>>(emptyMap())
    val groupStates: StateFlow<Map<String, GroupLoadingState>> = _groupStates.asStateFlow()

    // Legacy API compatibility: derive isLoadingMore from state machine
    private val _isLoadingMore = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isLoadingMore: StateFlow<Map<String, Boolean>> = _isLoadingMore.asStateFlow()

    // Legacy API compatibility: derive hasMoreMessages from state machine
    private val _hasMoreMessages = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hasMoreMessages: StateFlow<Map<String, Boolean>> = _hasMoreMessages.asStateFlow()

    /**
     * Info about reactions for a specific emoji on a message.
     */
    data class ReactionInfo(
        val emojiUrl: String?, // URL for custom emoji (NIP-30), null for standard emojis
        val reactors: List<String> // List of pubkeys who reacted with this emoji
    )

    // Reactions: messageId -> (emoji -> ReactionInfo)
    private val _reactions = MutableStateFlow<Map<String, Map<String, ReactionInfo>>>(emptyMap())
    val reactions: StateFlow<Map<String, Map<String, ReactionInfo>>> = _reactions.asStateFlow()

    // Group members from kind 39002: groupId -> list of member pubkeys
    private val _groupMembers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val groupMembers: StateFlow<Map<String, List<String>>> = _groupMembers.asStateFlow()

    // Groups whose member list request has been sent but no kind:39002 response yet.
    // Cleared when handleGroupMembers stores the response, or after a timeout.
    private val _loadingMembers = MutableStateFlow<Set<String>>(emptySet())
    val loadingMembers: StateFlow<Set<String>> = _loadingMembers.asStateFlow()

    // Group admins from kind 39001: groupId -> list of admin pubkeys
    private val _groupAdmins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val groupAdmins: StateFlow<Map<String, List<String>>> = _groupAdmins.asStateFlow()

    companion object {
        const val PAGE_SIZE = 50
        const val LOADING_TIMEOUT_MS = 10_000L // 10 seconds timeout for loading
        const val MAX_PERSISTED_MESSAGES = 100 // Limit messages per group for storage
        const val MEMBER_LOAD_TIMEOUT_MS = 8_000L // Safety timeout for member loading state
    }

    // Current user pubkey for storage scoping
    private var currentPubkey: String? = null

    /**
     * Set the current user pubkey for storage scoping.
     * Should be called after login.
     */
    fun setCurrentPubkey(pubkey: String?) {
        currentPubkey = pubkey
    }

    // Mutex for message list updates (separate from loading state)
    private val messageMutex = Mutex()

    // Track which groups have observation jobs to prevent memory leaks
    private val observedGroups = mutableSetOf<String>()
    private val observedGroupsMutex = Mutex()

    /**
     * Returns the relay URL that hosts the given group, by scanning the per-relay cache.
     * Uses an immutable snapshot of _groupsByRelay so no lock is needed.
     */
    fun getRelayForGroup(groupId: String): String? =
        _groupsByRelay.value.entries.firstOrNull { (_, groups) -> groups.any { it.id == groupId } }?.key

    /**
     * Returns the WebSocket client for the relay that hosts [groupId].
     * If the group's relay client exists but is disconnected, returns null — callers must
     * not send subscriptions to dead sockets (send() silently no-ops on a closed session).
     * Falls back to the primary only when the group's relay is completely unknown.
     */
    private suspend fun clientForGroup(groupId: String): NostrGroupClient? {
        val relayUrl = getRelayForGroup(groupId)
        return if (relayUrl != null) {
            val client = connectionManager.getClientForRelay(relayUrl)
            when {
                client == null -> connectionManager.getPrimaryClient() // relay not in pool yet
                client.isConnected() -> client                         // healthy pool client ✓
                else -> {
                    null  // dead pool client — reconnect is pending, don't send to it
                }
            }
        } else {
            connectionManager.getPrimaryClient()
        }
    }

    /** Returns all cached groups for the given relay URL. */
    fun getGroupsForRelay(relayUrl: String): List<GroupMetadata> =
        _groupsByRelay.value[relayUrl] ?: emptyList()

    /**
     * Reverse-lookup: find a group ID from its first-8-chars prefix.
     * Used to identify which group a `live_<prefix>` or `msg_<prefix>_...` subscription belongs to.
     */
    fun getGroupIdByPrefix(prefix: String): String? {
        val allGroups = (_groups.value + _groupsByRelay.value.values.flatten()).distinctBy { it.id }
        return allGroups.firstOrNull { it.id.take(8) == prefix }?.id
    }

    /**
     * Re-send live subscriptions for all currently-loaded groups.
     *
     * Called every ~5 minutes to prevent relays from dropping idle subscriptions.
     * Also called from resubscribeAllGroups / resubscribePoolRelay implicitly because
     * requestGroupMessages already calls sendLiveSubscription on initial load.
     */
    /**
     * Returns the set of group IDs that should be covered by the mux subscription for [relayUrl].
     *
     * Union of: joined groups on that relay + groups with loaded messages on that relay.
     * This is the canonical input to [sendMuxSubscriptions].
     */
    fun getGroupIdsForMux(relayUrl: String): List<String> {
        val joined = _joinedGroupsByRelay.value[relayUrl] ?: emptySet()
        val loaded = _messages.value.keys.filter { groupId -> getRelayForGroup(groupId) == relayUrl }
        return (joined + loaded).distinct()
    }

    /**
     * Set the group currently being viewed by the user.
     * If this group hasn't been opened before, triggers on-demand subscriptions
     * (messages, members, admins, metadata). Refreshes the mux so chat/reactions
     * include this group.
     */
    fun setActiveGroupId(groupId: String?) {
        _activeGroupId = groupId
        val relayUrl = if (groupId != null) getRelayForGroup(groupId) else currentRelayUrl

        // On-demand: first time opening this group, fetch its data.
        val isNew = if (groupId != null) {
            val current = _openedGroupIds.value
            if (groupId !in current) {
                _openedGroupIds.value = current + groupId
                true
            } else false
        } else false

        if (isNew) {
            scope.launch {
                val url = relayUrl ?: return@launch
                // Wait briefly for the client to become connected (up to 3s).
                // Avoids losing member/admin requests when the pool client exists
                // but the WebSocket handshake hasn't completed yet.
                var client = connectionManager.getClientForRelay(url)
                if (client != null && !client.isConnected()) {
                    repeat(6) {
                        delay(500)
                        if (client!!.isConnected()) return@repeat
                    }
                }
                client = connectionManager.getClientForRelay(url)
                if (client != null && client.isConnected()) {
                    requestGroupMessages(groupId!!)
                    requestGroupMembers(groupId)
                    requestGroupAdmins(groupId)
                    client.requestGroupMetadata(groupId)
                }
                refreshMuxSubscriptionsForRelay(url)
            }
        } else if (relayUrl != null) {
            scope.launch { refreshMuxSubscriptionsForRelay(relayUrl) }
        }
    }

    /**
     * Get the set of groups opened by the user this session.
     */
    fun getOpenedGroupIds(): Set<String> = _openedGroupIds.value

    /**
     * Send (or refresh) the relay-level multiplexed subscriptions for [relayUrl].
     *
     * Metadata mux covers ALL joined groups on this relay (lightweight, addressable).
     * Chat + reactions mux covers only the active group (on-demand).
     *
     * Uses [LiveCursorStore.getMinSince] to pick the oldest per-group cursor — guarantees
     * no group misses events during the offline window.
     * No-ops if there are no groups for that relay or the client is not connected.
     */
    suspend fun refreshMuxSubscriptionsForRelay(relayUrl: String) {
        refreshMuxSubscriptionsForRelayImpl(relayUrl)
    }

    /**
     * Debounced version: coalesces rapid calls within 300ms into a single mux refresh.
     * Use this from callers that may fire in quick succession (auth, EOSE, CLOSED handlers).
     */
    fun refreshMuxDebounced(relayUrl: String) {
        val wasCoalesced = muxRefreshJobs[relayUrl]?.isActive == true
        muxRefreshJobs[relayUrl]?.cancel()
        muxRefreshJobs[relayUrl] = scope.launch {
            delay(300)
            refreshMuxSubscriptionsForRelayImpl(relayUrl)
            muxRefreshJobs.remove(relayUrl)
        }
    }

    private suspend fun refreshMuxSubscriptionsForRelayImpl(relayUrl: String) {
        val allGroupIds = getGroupIdsForMux(relayUrl)
        if (allGroupIds.isEmpty()) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return

        // Chat/reactions mux covers all groups the user has opened this session
        // (not just the active one), so previously opened groups keep receiving live messages.
        val chatGroupIds = _openedGroupIds.value
            .filter { it in allGroupIds }
            .ifEmpty {
                val active = _activeGroupId
                if (active != null && active in allGroupIds) listOf(active) else emptyList()
            }

        val chatSince = if (chatGroupIds.isNotEmpty()) {
            liveCursorStore.getMinSince(relayUrl, chatGroupIds)
        } else {
            0L
        }

        try {
            client.sendMuxSubscriptions(allGroupIds, chatGroupIds, chatSince)
        } catch (_: Exception) {}
    }

    /**
     * Refresh mux subscriptions for all relays that currently have joined or loaded groups.
     * Replaces the old per-group `live_` refresh loop.
     * Called from the 5-minute periodic timer in NostrRepository.
     */
    suspend fun refreshLiveSubscriptions() {
        val relayUrls = (_joinedGroupsByRelay.value.keys +
            _messages.value.keys.mapNotNull { getRelayForGroup(it) }).distinct()
        for (relayUrl in relayUrls) {
            try { refreshMuxSubscriptionsForRelay(relayUrl) } catch (_: Exception) {}
        }
    }

    /**
     * Load joined groups from storage
     */
    fun loadJoinedGroupsFromStorage(pubKey: String, relayUrl: String) {
        val groups = SecureStorage.getJoinedGroupsForRelay(pubKey, relayUrl)
        _joinedGroups.value = groups
        _joinedGroupsByRelay.update { it + (relayUrl to groups) }
    }

    /**
     * Load joined groups for all given relays into the per-relay cache without
     * touching the live _joinedGroups state (which belongs to the active relay).
     */
    fun loadAllJoinedGroupsFromStorage(pubKey: String, relayUrls: List<String>) {
        val updates = relayUrls.associateWith { url ->
            SecureStorage.getJoinedGroupsForRelay(pubKey, url)
        }.filter { (_, groups) -> groups.isNotEmpty() }
        if (updates.isNotEmpty()) {
            _joinedGroupsByRelay.update { it + updates }
        }
    }

    /**
     * Pre-populate _groupsByRelay with empty entries so relays appear in the
     * rail before their first connection this session.
     */
    fun prePopulateRelayList(relayUrls: List<String>) {
        _groupsByRelay.update { current ->
            val additions = relayUrls.filter { !current.containsKey(it) }
                .associateWith { emptyList<GroupMetadata>() }
            current + additions
        }
    }

    /**
     * Set joined groups (from kind:10009)
     */
    fun setJoinedGroups(groups: Set<String>) {
        _joinedGroups.value = groups
        currentRelayUrl?.let { url ->
            _joinedGroupsByRelay.update { it + (url to groups) }
        }
    }

    /**
     * Update joined groups for all relays at once from a kind:10009 event.
     * Called when a kind:10009 arrives so every relay's membership is reflected
     * in the UI immediately — not just the currently active relay.
     */
    fun updateAllRelayJoinedGroups(relayGroups: Map<String, Set<String>>) {
        if (relayGroups.isEmpty()) return
        _joinedGroupsByRelay.update { it + relayGroups }
        // Also sync _joinedGroups if the active relay is in the event
        currentRelayUrl?.let { url ->
            relayGroups[url]?.let { groups -> _joinedGroups.value = groups }
        }
    }

    /**
     * Join a group
     */
    suspend fun joinGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9021,
                tags = listOf(listOf("h", groupId)),
                content = "/join"
            )

            val signedEvent = signEvent(event)

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            currentClient.send(message)

            val updated = _joinedGroups.value + groupId
            _joinedGroups.value = updated
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updated)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updated) }

            publishJoinedGroups()

            // Request group messages
            currentClient.requestGroupMessages(groupId)

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.JoinFailed(groupId, e))
        }
    }

    /**
     * Create a new NIP-29 group
     */
    suspend fun createGroup(
        name: String,
        about: String?,
        picture: String? = null,
        isPrivate: Boolean,
        isClosed: Boolean,
        customGroupId: String? = null,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit
    ): Result<String> {
        val currentClient = connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(currentRelayUrl))

        return try {
            // Use custom ID if provided, otherwise generate a random one
            val suggestedId = if (!customGroupId.isNullOrBlank()) {
                customGroupId.trim().lowercase()
            } else {
                buildString {
                    repeat(32) { append("0123456789abcdef"[kotlin.random.Random.nextInt(16)]) }
                }
            }

            // kind 9007: create-group — sign and build the full message first so
            // awaitGroupCreated can send it and track the OK response.
            // NIP-29 relays expect ["h", groupId] (the group tag), not ["d", groupId].
            val createEvent = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9007,
                tags = listOf(listOf("h", suggestedId)),
                content = ""
            )
            val signedCreate = signEvent(createEvent)
            val create9007Json = buildJsonArray {
                add("EVENT")
                add(signedCreate.toJsonObject())
            }.toString()

            // Send 9007, await relay OK, then subscribe for kind:39000 to get the
            // relay-confirmed group ID (relay may have used a different ID than suggested)
            val confirmedGroupId = currentClient.awaitGroupCreated(
                create9007EventJson = create9007Json,
                create9007EventId = signedCreate.id ?: suggestedId,
                suggestedGroupId = suggestedId
            )

            // kind 9002: edit-metadata — sets name, about, picture, and access in one event
            val metaTags = mutableListOf(
                listOf("h", confirmedGroupId),
                listOf("name", name),
                if (isPrivate) listOf("private") else listOf("public"),
                if (isClosed) listOf("closed") else listOf("open")
            )
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
            if (!picture.isNullOrBlank()) metaTags.add(listOf("picture", picture))
            val signedMeta = signEvent(Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9002,
                tags = metaTags,
                content = ""
            ))
            currentClient.send(buildJsonArray {
                add("EVENT")
                add(signedMeta.toJsonObject())
            }.toString())

            // Auto-join with the confirmed ID
            val updatedAfterCreate = _joinedGroups.value + confirmedGroupId
            _joinedGroups.value = updatedAfterCreate
            SecureStorage.saveJoinedGroupsForRelay(pubKey, currentRelayUrl, updatedAfterCreate)
            _joinedGroupsByRelay.update { it + (currentRelayUrl to updatedAfterCreate) }
            publishJoinedGroups()
            currentClient.requestGroupMessages(confirmedGroupId)

            Result.Success(confirmedGroupId)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.CreateFailed(e))
        }
    }

    /**
     * Edit a group's metadata and/or status (admin only).
     * Sends kind:9004 (edit-metadata) and kind:9008 (edit-status).
     */
    suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        picture: String? = null,
        isPrivate: Boolean,
        isClosed: Boolean,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            // kind 9002: edit-metadata — name, about, picture, visibility, access all in one event
            val metaTags = mutableListOf(
                listOf("h", groupId),
                listOf("name", name),
                if (isPrivate) listOf("private") else listOf("public"),
                if (isClosed) listOf("closed") else listOf("open")
            )
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
            if (!picture.isNullOrBlank()) metaTags.add(listOf("picture", picture))
            val signedMeta = signEvent(Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9002,
                tags = metaTags,
                content = ""
            ))
            val eventJson = buildJsonArray {
                add("EVENT")
                add(signedMeta.toJsonObject())
            }.toString()
            val eventId = signedMeta.id
                ?: return Result.Error(AppError.Group.CreateFailed(Exception("Event ID not generated")))

            when (val result = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.CreateFailed(Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.CreateFailed(Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.CreateFailed(result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.CreateFailed(e))
        }
    }

    /**
     * Delete a group (admin only).
     * Sends kind:9006 (delete-group).
     */
    suspend fun deleteGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9008, // delete-group (NIP-29)
                tags = listOf(listOf("h", groupId)),
                content = ""
            )
            val signedEvent = signEvent(event)
            currentClient.send(buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString())

            // Remove from joined groups
            val updatedAfterLeave = _joinedGroups.value - groupId
            _joinedGroups.value = updatedAfterLeave
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updatedAfterLeave)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updatedAfterLeave) }
            publishJoinedGroups()

            // Remove group from live list and per-relay cache
            _groups.value = _groups.value.filter { it.id != groupId }
            _groupsByRelay.update { current ->
                val updated = (current[groupRelayUrl] ?: emptyList()).filter { it.id != groupId }
                current + (groupRelayUrl to updated)
            }
            // Persist the updated relay group list so the group doesn't reappear on restart
            try {
                val updatedRelayGroups = _groupsByRelay.value[groupRelayUrl] ?: emptyList()
                SecureStorage.saveGroupsForRelay(groupRelayUrl, json.encodeToString(updatedRelayGroups))
            } catch (_: Exception) {}

            _messages.update { it - groupId }
            _isLoadingMore.update { it - groupId }
            _hasMoreMessages.update { it - groupId }
            _groupStates.update { it - groupId }
            _groupAdmins.value = _groupAdmins.value - groupId
            _groupMembers.update { it - groupId }
            loadingRegistry.remove(groupId)

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.LeaveFailed(groupId, e))
        }
    }

    /**
     * Leave a group
     */
    suspend fun leaveGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        reason: String? = null,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9022,
                tags = listOf(listOf("h", groupId)),
                content = reason.orEmpty()
            )

            val signedEvent = signEvent(event)

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            currentClient.send(message)

            val updatedAfterLeave2 = _joinedGroups.value - groupId
            _joinedGroups.value = updatedAfterLeave2
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updatedAfterLeave2)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updatedAfterLeave2) }

            publishJoinedGroups()

            // Clear messages for this group
            _messages.update { it - groupId }

            // Clear pagination state for this group (use atomic updates)
            _isLoadingMore.update { it - groupId }
            _hasMoreMessages.update { it - groupId }
            _groupStates.update { it - groupId }

            // Remove from observed groups tracking
            observedGroupsMutex.withLock {
                observedGroups.remove(groupId)
            }

            // Clear state machine for this group
            loadingRegistry.remove(groupId)

            // Do NOT clear the full deduplicator here — that would wipe the seen-event
            // history for every other loaded group and allow their events to be re-processed
            // as duplicates on the next delivery.  The message list for this group is already
            // removed above; when the user rejoins and messages are re-fetched the deduplicator
            // will correctly admit them as new (they will have been evicted by LRU/TTL by then,
            // or they are genuinely the same events and should not appear twice).

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.LeaveFailed(groupId, e))
        }
    }

    /**
     * Check if a group is joined
     */
    fun isGroupJoined(groupId: String): Boolean {
        return _joinedGroups.value.contains(groupId)
    }

    /**
     * Request group messages (initial load).
     * Uses the state machine for reliable loading with proper state transitions.
     */
    suspend fun requestGroupMessages(groupId: String, channel: String? = null): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false

        // Get controller and attempt to start initial load
        val controller = loadingRegistry.getController(groupId)
        val subscriptionId = controller.startInitialLoad() ?: return false

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        // Update legacy flags for UI compatibility
        updateLegacyFlags(groupId, controller.state.value)

        // Observe state changes to update legacy flags (only once per group)
        observeStateChanges(groupId, controller)

        // If messages have already been delivered by mux, request only events older than
        // the oldest one we have to avoid deduplication causing messageCount=0 → Exhausted.
        val existingMessages = _messages.value[groupId]
        val until = existingMessages?.minOfOrNull { it.createdAt }?.let { oldest ->
            if (oldest < Long.MAX_VALUE) oldest - 1L else null
        }

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = until,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId
            )
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    /**
     * Observe state changes and update legacy flags.
     * Only creates one observation job per group to prevent memory leaks.
     */
    private suspend fun observeStateChanges(groupId: String, controller: GroupLoadingController) {
        // Check if already observing this group
        val shouldObserve = observedGroupsMutex.withLock {
            if (groupId in observedGroups) {
                false
            } else {
                observedGroups.add(groupId)
                true
            }
        }

        if (!shouldObserve) return

        scope.launch {
            controller.state.collect { state ->
                updateLegacyFlags(groupId, state)
                _groupStates.update { it + (groupId to state) }
            }
        }
    }

    /**
     * Update legacy boolean flags from state machine state.
     * Maintains backward compatibility with existing UI code.
     */
    private fun updateLegacyFlags(groupId: String, state: GroupLoadingState) {
        _isLoadingMore.update { it + (groupId to state.isLoading) }
        _hasMoreMessages.update { it + (groupId to state.hasMore) }
    }

    /**
     * Load older messages for pagination (infinite scroll).
     * Uses the state machine with per-group locks - doesn't block other groups.
     */
    suspend fun loadMoreMessages(groupId: String, channel: String? = null): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false

        // Get controller and attempt to start pagination
        val controller = loadingRegistry.getController(groupId)
        val result = controller.startPagination() ?: return false
        val (subscriptionId, cursor) = result

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        // Update legacy flags
        updateLegacyFlags(groupId, controller.state.value)

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = cursor.untilTimestamp,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId
            )
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    /**
     * Called when EOSE is received for a subscription.
     * Delegates to the state machine for proper state transitions.
     * CRITICAL: This must be called from a coroutine context to ensure
     * proper ordering with message tracking.
     */
    suspend fun handleEoseSuspend(subscriptionId: String): Boolean {
        // Handle both the legacy "group-list" ID and new relay-specific IDs
        // ("group-list-<hashCode>"). Map the sub ID back to the relay URL by
        // scanning pool and primary clients so we mark the *correct* relay as
        // having completed its initial load.
        if (subscriptionId == "group-list" || subscriptionId.startsWith("group-list-")) {
            val relay = if (subscriptionId == "group-list") {
                currentRelayUrl
            } else {
                findRelayForGroupListSubId(subscriptionId) ?: currentRelayUrl
            }
            if (relay != null) {
                completeGroupLoadRelays.add(relay)
                _loadingRelays.update { it - relay }
                val count = _groupsByRelay.value[relay]?.size ?: 0
                // Send mux subscriptions now that we know the group list.
                // For auth-required relays, resubscribeAfterAuth will send/refresh them later.
                refreshMuxSubscriptionsForRelay(relay)
            } else {
            }
            return true
        }
        return loadingRegistry.handleEose(subscriptionId)
    }

    /**
     * Find the relay URL whose group-list subscription ID matches the given subId.
     * The subscription ID format is "group-list-<unsignedHashCode>" where the hash
     * is derived from the relay URL.
     */
    private fun findRelayForGroupListSubId(subscriptionId: String): String? {
        // Check all known relay URLs from _groupsByRelay
        return _groupsByRelay.value.keys.firstOrNull { relayUrl ->
            "group-list-${relayUrl.hashCode().toUInt()}" == subscriptionId
        }
    }

    /**
     * Non-suspend version for backward compatibility.
     * Launches in scope - use handleEoseSuspend when possible.
     */
    fun handleEose(subscriptionId: String): Boolean {
        scope.launch {
            loadingRegistry.handleEose(subscriptionId)
        }
        return true
    }

    /**
     * Track message received for a subscription (for pagination counting).
     * CRITICAL: This is a suspend function to ensure proper ordering
     * with EOSE handling - messages must be tracked before EOSE is processed.
     */
    suspend fun trackMessageForSubscriptionSuspend(subscriptionId: String, timestamp: Long, eventId: String) {
        loadingRegistry.trackMessage(subscriptionId, timestamp, eventId)
    }

    /**
     * Non-suspend version for contexts where suspend is not available.
     * Note: This may cause race conditions if EOSE arrives quickly.
     */
    fun trackMessageForSubscription(subscriptionId: String, timestamp: Long, eventId: String) {
        scope.launch {
            loadingRegistry.trackMessage(subscriptionId, timestamp, eventId)
        }
    }

    /**
     * Check if a subscription is managed by the loading registry.
     */
    suspend fun isPaginationSubscription(subscriptionId: String): Boolean {
        return loadingRegistry.findBySubscription(subscriptionId) != null
    }

    /**
     * Retry loading after an error.
     */
    suspend fun retryLoading(groupId: String, channel: String? = null): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false
        val controller = loadingRegistry.getController(groupId)

        val subscriptionId = controller.retry() ?: return false

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        updateLegacyFlags(groupId, controller.state.value)

        val state = controller.state.value
        val cursor = when (state) {
            is GroupLoadingState.Retrying -> state.cursor
            else -> null
        }

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = cursor?.untilTimestamp,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId
            )
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    /**
     * Get the current loading state for a group.
     */
    suspend fun getLoadingState(groupId: String): GroupLoadingState {
        return loadingRegistry.getController(groupId).state.value
    }

    /**
     * Handle connection lost - notify all active loaders.
     */
    suspend fun handleConnectionLost() {
        loadingRegistry.handleDisconnectAll()
    }

    /**
     * Handle connection lost for a specific set of groups (e.g. a pool relay dropped).
     * Resets their loading states to Idle so re-subscription can proceed after reconnect.
     */
    suspend fun handleConnectionLostForGroups(groupIds: List<String>) {
        loadingRegistry.handleDisconnectForGroups(groupIds)
    }

    /**
     * Send a message to a group.
     * Uses sendAndAwaitOk() to properly wait for relay confirmation.
     */
    suspend fun sendMessage(
        groupId: String,
        content: String,
        pubKey: String,
        channel: String? = null,
        mentions: Map<String, String> = emptyMap(),
        replyToMessageId: String? = null,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val currentClient = clientForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected(""))

        return try {
            val tags = mutableListOf(listOf("h", groupId))
            if (channel != null && channel != "general") {
                tags.add(listOf("channel", channel))
            }

            // Add reply tag if replying to a message (NIP-29: use "q" tag)
            if (replyToMessageId != null) {
                tags.add(listOf("q", replyToMessageId))
            }

            // Replace @displayName with nostr:npub... in content
            var processedContent = content
            mentions.forEach { (displayName, pubkeyHex) ->
                val npub = org.nostr.nostrord.nostr.Nip19.encodeNpub(pubkeyHex)
                processedContent = processedContent.replace("@$displayName", "nostr:$npub")
                tags.add(listOf("p", pubkeyHex))
            }

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9,
                tags = tags,
                content = processedContent
            )

            val signedEvent = signEvent(event)

            val eventJson = signedEvent.toJsonObject()
            val message = buildJsonArray {
                add("EVENT")
                add(eventJson)
            }.toString()

            // Get event ID for OK tracking
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))

            val publishResult = currentClient.sendAndAwaitOk(message, eventId)

            when (publishResult) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.MessageRejected(groupId, publishResult.reason))
                is org.nostr.nostrord.network.PublishResult.Timeout -> {
                    // Queue for retry on timeout
                    pendingEventManager?.queueEvent(message, eventId, groupId)
                    Result.Error(AppError.Group.SendTimeout(groupId))
                }
                is org.nostr.nostrord.network.PublishResult.Error -> {
                    // Queue for retry on network error
                    pendingEventManager?.queueEvent(message, eventId, groupId)
                    Result.Error(AppError.Group.SendFailed(groupId, publishResult.exception))
                }
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }


    /**
     * Send a reaction to a message (NIP-25: kind 7 reaction event).
     * Uses optimistic update: shows the reaction immediately, rolls back on rejection.
     */
    suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
        pubKey: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val currentClient = clientForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected(""))

        return try {
            val groupRelayUrl = getRelayForGroup(groupId) ?: ""
            val tags = listOf(
                listOf("h", groupId, groupRelayUrl),
                listOf("e", targetEventId, "", "", targetPubkey),
                listOf("p", targetPubkey)
            )

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 7,
                tags = tags,
                content = emoji
            )

            val signedEvent = signEvent(event)
            val eventJson = signedEvent.toJsonObject()
            val message = buildJsonArray {
                add("EVENT")
                add(eventJson)
            }.toString()

            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))


            // Optimistic update: show reaction in UI immediately
            handleReaction(NostrGroupClient.NostrReaction(
                id = eventId,
                pubkey = pubKey,
                emoji = emoji,
                emojiUrl = null,
                targetEventId = targetEventId,
                createdAt = event.createdAt
            ))

            // Send to group relay
            val publishResult = currentClient.sendAndAwaitOk(message, eventId)

            when (publishResult) {
                is org.nostr.nostrord.network.PublishResult.Success -> {
                    Result.Success(Unit)
                }
                is org.nostr.nostrord.network.PublishResult.Rejected -> {
                    // Rollback optimistic update
                    removeReaction(targetEventId, emoji, pubKey)
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(publishResult.reason)))
                }
                is org.nostr.nostrord.network.PublishResult.Timeout -> {
                    Result.Error(AppError.Group.SendFailed(groupId, Exception("timeout")))
                }
                is org.nostr.nostrord.network.PublishResult.Error -> {
                    // Rollback optimistic update
                    removeReaction(targetEventId, emoji, pubKey)
                    Result.Error(AppError.Group.SendFailed(groupId, publishResult.exception))
                }
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Delete a message from a group (NIP-09: kind 5 deletion event).
     * Sends the deletion event to the relay; the relay will broadcast it and
     * handleDeletion() will remove it from local state when received back.
     */
    suspend fun deleteMessage(
        groupId: String,
        messageId: String,
        pubKey: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val currentClient = clientForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected(""))
        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 5,
                tags = listOf(
                    listOf("e", messageId),
                    listOf("h", groupId)
                ),
                content = ""
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.SendFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            when (val result = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.SendFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.SendTimeout(groupId))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.SendFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Handle incoming group metadata.
     * Updates the live flow for the current relay AND stores in the per-relay cache
     * so returning to this relay later is instant without a network re-fetch.
     */
    fun handleGroupMetadata(metadata: GroupMetadata, relayUrl: String) {
        // ALL relays contribute to the unified group list regardless of which relay
        // is currently active. _groupsByRelay handles per-relay filtering for the UI.
        val wasNew = _groups.value.none { it.id == metadata.id }
        // Replace existing entry (update) or append if new — distinctBy keeps the first,
        // so we must map-replace instead to propagate edits in realtime.
        _groups.value = if (wasNew) {
            _groups.value + metadata
        } else {
            _groups.value.map { if (it.id == metadata.id) metadata else it }
        }
        _groupsByRelay.update { current ->
            val relayGroups = current[relayUrl] ?: emptyList()
            val isNewForRelay = relayGroups.none { it.id == metadata.id }
            val updated = if (isNewForRelay) {
                relayGroups + metadata
            } else {
                relayGroups.map { if (it.id == metadata.id) metadata else it }
            }
            current + (relayUrl to updated)
        }
        // Individual group additions are not logged — count is reported at EOSE.
        // Persist the updated group list for this relay so it survives app restarts
        val relayGroups = _groupsByRelay.value[relayUrl] ?: emptyList()
        try {
            SecureStorage.saveGroupsForRelay(relayUrl, json.encodeToString(relayGroups))
        } catch (_: Exception) {}
    }

    /**
     * Restore the groups list from the per-relay cache.
     * Called on relay switch to show previously loaded groups instantly.
     */
    fun restoreGroupsForRelay(relayUrl: String) {
        currentRelayUrl = relayUrl
        // Merge cached groups for the new relay into the unified live list.
        // State is additive: previously loaded groups from other relays are kept.
        val cached = _groupsByRelay.value[relayUrl] ?: emptyList()
        if (cached.isNotEmpty()) {
            _groups.value = (_groups.value + cached).distinctBy { it.id }
        }
    }

    /**
     * Restore group metadata for all known relays from SecureStorage.
     * Called on startup before any WebSocket connects so relay switching is instant.
     */
    fun restoreAllGroupsFromStorage(relayUrls: List<String>) {
        _groupsByRelay.update { current ->
            val updates = relayUrls.mapNotNull { url ->
                val jsonStr = SecureStorage.getGroupsForRelay(url) ?: return@mapNotNull null
                try {
                    val groups = json.decodeFromString<List<GroupMetadata>>(jsonStr)
                    if (groups.isNotEmpty()) url to groups else null
                } catch (_: Exception) { null }
            }.toMap()
            current + updates
        }
    }

    /**
     * Returns true if we have a non-empty cached group list for the given relay.
     */
    fun hasCachedGroupsForRelay(relayUrl: String): Boolean {
        // Only treat as cached if the initial load finished (EOSE received).
        // A partial cache from an interrupted load must trigger a re-fetch.
        return relayUrl in completeGroupLoadRelays && _groupsByRelay.value[relayUrl]?.isNotEmpty() == true
    }

    /**
     * Handle incoming group members (kind 39002)
     * Returns list of member pubkeys that need metadata fetching
     */
    fun handleGroupMembers(members: GroupMembers): List<String> {
        _groupMembers.value = _groupMembers.value + (members.groupId to members.members)
        _loadingMembers.value = _loadingMembers.value - members.groupId
        return members.members
    }

    /**
     * Request group members (kind 39002).
     * Marks the group as loading so the UI can show skeletons.
     * Auto-clears after [MEMBER_LOAD_TIMEOUT_MS] if no response arrives.
     */
    suspend fun requestGroupMembers(groupId: String): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false
        _loadingMembers.value = _loadingMembers.value + groupId
        currentClient.requestGroupMembers(groupId)
        // Safety timeout: clear loading flag if relay never responds.
        scope.launch {
            delay(MEMBER_LOAD_TIMEOUT_MS)
            _loadingMembers.value = _loadingMembers.value - groupId
        }
        return true
    }

    /**
     * Handle incoming group admins (kind 39001)
     */
    fun handleGroupAdmins(admins: GroupAdmins) {
        _groupAdmins.value = _groupAdmins.value + (admins.groupId to admins.admins)
    }

    /**
     * Request group admins (kind 39001)
     */
    suspend fun requestGroupAdmins(groupId: String): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestGroupAdmins(groupId)
        return true
    }

    /**
     * Check if a pubkey is an admin of a group
     */
    fun isGroupAdmin(groupId: String, pubkey: String): Boolean {
        return pubkey in (_groupAdmins.value[groupId] ?: emptyList())
    }

    /**
     * Get members for a specific group
     */
    fun getMembersForGroup(groupId: String): List<String> {
        return _groupMembers.value[groupId] ?: emptyList()
    }

    // Valid message kinds for group events (NIP-29 and related)
    private val validMessageKinds = setOf(
        9,      // Chat messages (NIP-29)
        9000,   // Group admin: add user
        9001,   // Group admin: remove user
        9002,   // Group admin: edit metadata (NIP-29)
        9021,   // Join request
        9022,   // Leave request
        9321    // Zap request (NIP-57)
    )

    // Deletion kinds that remove other events
    private val deletionKinds = setOf(
        5,      // Deletion request (NIP-09)
        9003    // Group admin: delete event (NIP-29)
    )

    /**
     * Handle incoming message.
     * Returns the pubkey of the message sender if metadata should be fetched.
     * Also tracks message for pagination cursor calculation.
     */
    fun handleMessage(
        message: NostrGroupClient.NostrMessage,
        rawMsg: String,
        subscriptionId: String? = null,
        relayUrl: String? = null
    ): String? {
        // Handle deletion events separately
        if (message.kind in deletionKinds) {
            handleDeletion(message, rawMsg)
            return null
        }

        // Only process valid message kinds
        if (message.kind !in validMessageKinds) {
            return null
        }

        val messageId = message.id
        if (messageId.isBlank() || !eventDeduplicator.tryAddSync(messageId)) {
            return null // Duplicate message
        }

        val groupId = extractGroupIdFromMessage(rawMsg) ?: return null

        // Update live cursor so reconnects resume from the right timestamp.
        // Fire-and-forget: cursor updates are non-critical and should not block message processing.
        if (relayUrl != null && message.createdAt > 0L) {
            scope.launch { liveCursorStore.update(relayUrl, groupId, message.createdAt) }
        }

        // Track message in state machine for cursor calculation
        if (subscriptionId != null) {
            trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
        }

        // Enqueue to the ordering buffer; the buffer flushes after 300 ms of inactivity
        // for this group, applying the entire batch in one sorted StateFlow update.
        eventOrderingBuffer.enqueue(groupId, message)

        return message.pubkey
    }

    /**
     * Apply a pre-sorted batch of messages for [groupId] to [_messages] in one atomic update.
     * Called by [EventOrderingBuffer] after its debounce window expires.
     */
    private fun flushBatchToState(groupId: String, messages: List<NostrGroupClient.NostrMessage>) {
        _messages.update { currentMap ->
            val current = (currentMap[groupId] ?: emptyList()).toMutableList()
            var changed = false
            for (msg in messages) {
                if (current.none { it.id == msg.id }) {
                    current.add(msg)
                    changed = true
                }
            }
            if (changed) currentMap + (groupId to current.sortedBy { it.createdAt })
            else currentMap
        }
    }

    /**
     * Handle deletion events (kind 5 and 9005).
     * Removes the referenced events from the message list.
     */
    private fun handleDeletion(deletion: NostrGroupClient.NostrMessage, rawMsg: String) {
        val groupId = extractGroupIdFromMessage(rawMsg) ?: return

        // Extract event IDs to delete from "e" tags
        val eventIdsToDelete = deletion.tags
            .filter { it.size >= 2 && it[0] == "e" }
            .map { it[1] }
            .toSet()

        if (eventIdsToDelete.isEmpty()) return

        // Remove deleted messages from the list
        _messages.update { currentMap ->
            val currentMessages = currentMap[groupId] ?: return@update currentMap
            val filteredMessages = currentMessages.filterNot { it.id in eventIdsToDelete }
            if (filteredMessages.size == currentMessages.size) {
                currentMap // No changes
            } else {
                currentMap + (groupId to filteredMessages)
            }
        }

        // Also remove deleted reactions
        _reactions.update { currentReactions ->
            var updated = currentReactions
            eventIdsToDelete.forEach { eventId ->
                if (eventId in updated) {
                    updated = updated - eventId
                }
            }
            updated
        }
    }

    /**
     * Get messages for a specific group
     */
    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> {
        return _messages.value[groupId] ?: emptyList()
    }

    /**
     * Returns the [NostrGroupClient.NostrMessage.createdAt] of the newest message in the
     * in-memory list for [groupId], or null if the group has no messages loaded yet.
     * Used by gap detection to check whether events near the cursor arrived after reconnect.
     */
    fun getLatestMessageTimestamp(groupId: String): Long? =
        _messages.value[groupId]?.maxOfOrNull { it.createdAt }

    /**
     * Handle incoming reaction (kind 7)
     * Returns the pubkey of the reactor if metadata should be fetched
     */
    fun handleReaction(reaction: NostrGroupClient.NostrReaction): String? {
        val messageId = reaction.targetEventId
        if (messageId.isBlank()) return null

        // Deduplicate reactions by id
        if (!eventDeduplicator.tryAddSync(reaction.id)) {
            return null
        }

        val currentReactions = _reactions.value[messageId] ?: emptyMap()
        val emoji = reaction.emoji
        val reactorPubkey = reaction.pubkey

        // Get current reaction info for this emoji
        val currentInfo = currentReactions[emoji]
        val currentReactors = currentInfo?.reactors ?: emptyList()

        if (reactorPubkey in currentReactors) return null // Already reacted with this emoji

        // Update with new reactor, preserving or updating the emoji URL
        val updatedInfo = ReactionInfo(
            emojiUrl = reaction.emojiUrl ?: currentInfo?.emojiUrl, // Keep existing URL if new one is null
            reactors = currentReactors + reactorPubkey
        )
        val updatedEmojiMap = currentReactions + (emoji to updatedInfo)

        _reactions.value = _reactions.value + (messageId to updatedEmojiMap)

        return reactorPubkey
    }

    /**
     * Remove a reaction from local state (used for rollback on relay rejection).
     */
    private fun removeReaction(messageId: String, emoji: String, pubkey: String) {
        val currentReactions = _reactions.value[messageId] ?: return
        val info = currentReactions[emoji] ?: return
        val updatedReactors = info.reactors.filter { it != pubkey }
        val updatedEmojiMap = if (updatedReactors.isEmpty()) {
            currentReactions - emoji
        } else {
            currentReactions + (emoji to info.copy(reactors = updatedReactors))
        }
        _reactions.value = if (updatedEmojiMap.isEmpty()) {
            _reactions.value - messageId
        } else {
            _reactions.value + (messageId to updatedEmojiMap)
        }
    }

    /**
     * Extract group ID from a raw message
     */
    private fun extractGroupIdFromMessage(msg: String): String? {
        return try {
            val arr = json.parseToJsonElement(msg).jsonArray
            if (arr.size < 3) return null
            val event = arr[2].jsonObject
            val tags = event["tags"]?.jsonArray ?: return null

            tags.firstOrNull { tag ->
                val tagArray = tag.jsonArray
                tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "h"
            }?.jsonArray?.get(1)?.jsonPrimitive?.content
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Clear session state only — messages, joined groups, loading state, reactions.
     * Does NOT clear the per-relay group metadata cache so switching back to a relay
     * that was previously visited shows groups immediately without a network re-fetch.
     *
     * Use this on relay switch. Use [clear] only on logout.
     */
    suspend fun clearForRelaySwitch() {
        // Reset the current relay pointer so stragglers from the old connection
        // go to the per-relay cache only, not the live _groups list.
        currentRelayUrl = null

        // Clear the live group list so the new relay starts from a clean slate.
        // restoreGroupsForRelay() will repopulate from _groupsByRelay if cached data exists.
        _groups.value = emptyList()

        // Reset observation tracking so subscriptions are re-established on the
        // new primary connection without leaking the old group set.
        observedGroups.clear()

        // MUST be synchronous (not scope.launch). If this is async, requestGroupMessages
        // called immediately after switchRelay sees stale HasMore/Exhausted state from the
        // previous relay and startInitialLoad() returns null — group silently never loads.
        loadingRegistry.clear()
        eventDeduplicator.clear()
    }

    /**
     * Remove a single relay entry from the in-memory cache so the rail updates immediately.
     */
    fun removeRelayEntry(url: String) {
        completeGroupLoadRelays.remove(url)
        _groupsByRelay.update { current -> current - url }
    }

    /**
     * Clear all state including the per-relay group metadata cache.
     * Use on logout or full account reset.
     */
    suspend fun clear() {
        eventOrderingBuffer.flushAll()
        completeGroupLoadRelays.clear()
        _loadingRelays.value = emptySet()
        _groupsByRelay.value = emptyMap()
        _openedGroupIds.value = emptySet()
        clearForRelaySwitch()
    }

    /**
     * Clear joined groups for an account
     */
    fun clearJoinedGroupsForAccount(pubKey: String) {
        SecureStorage.clearAllJoinedGroupsForAccount(pubKey)
        _joinedGroups.value = emptySet()
    }

    // ==========================================================================
    // MESSAGE PERSISTENCE
    // ==========================================================================

    /**
     * Load persisted messages for a group from storage.
     * Called when entering a group to show cached messages while fetching new ones.
     */
    fun loadMessagesFromStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        val messagesJson = SecureStorage.getMessagesForGroup(pubkey, groupId) ?: return

        try {
            val messagesArray = json.parseToJsonElement(messagesJson).jsonArray
            val messages = messagesArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    NostrGroupClient.NostrMessage(
                        id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        content = obj["content"]?.jsonPrimitive?.content ?: "",
                        createdAt = obj["createdAt"]?.jsonPrimitive?.long ?: return@mapNotNull null,
                        kind = obj["kind"]?.jsonPrimitive?.int ?: 9,
                        tags = obj["tags"]?.jsonArray?.map { tagArray ->
                            tagArray.jsonArray.map { it.jsonPrimitive.content }
                        } ?: emptyList()
                    )
                } catch (e: Throwable) {
                    null
                }
            }

            if (messages.isNotEmpty()) {
                // Add to deduplicator and messages
                messages.forEach { msg -> eventDeduplicator.tryAddSync(msg.id) }
                _messages.update { current ->
                    val existing = current[groupId] ?: emptyList()
                    val merged = (existing + messages).distinctBy { it.id }.sortedBy { it.createdAt }
                    current + (groupId to merged)
                }
            }
        } catch (e: Throwable) {
            // Ignore parsing errors - storage may be corrupted
        }
    }

    /**
     * Persist messages for a group to storage.
     * Called periodically and when leaving a group.
     */
    fun saveMessagesToStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        val messages = _messages.value[groupId] ?: return
        if (messages.isEmpty()) return

        try {
            // Keep only the most recent messages
            val toSave = messages.takeLast(MAX_PERSISTED_MESSAGES)
            val messagesJson = buildJsonArray {
                toSave.forEach { msg ->
                    add(buildJsonObject {
                        put("id", msg.id)
                        put("pubkey", msg.pubkey)
                        put("content", msg.content)
                        put("createdAt", msg.createdAt)
                        put("kind", msg.kind)
                        put("tags", buildJsonArray {
                            msg.tags.forEach { tag ->
                                add(buildJsonArray {
                                    tag.forEach { add(it) }
                                })
                            }
                        })
                    })
                }
            }.toString()

            SecureStorage.saveMessagesForGroup(pubkey, groupId, messagesJson)
        } catch (e: Throwable) {
            // Ignore save errors
        }
    }

    /**
     * Save all messages for all groups.
     * Called on app backgrounding/closing.
     */
    fun saveAllMessagesToStorage() {
        _messages.value.keys.forEach { groupId ->
            saveMessagesToStorage(groupId)
        }
    }

    /**
     * Clear persisted messages for a group.
     */
    fun clearMessagesFromStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        SecureStorage.clearMessagesForGroup(pubkey, groupId)
    }
}
