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
    private val pendingEventManager: PendingEventManager? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventDeduplicator = EventDeduplicator()

    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()

    // Per-relay group cache — persists across relay switches.
    private val _groupsByRelay = MutableStateFlow<Map<String, List<GroupMetadata>>>(emptyMap())
    val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = _groupsByRelay.asStateFlow()

    // Tracks which relay is currently active and which relays have fully loaded
    // (i.e. received EOSE for the "group-list" subscription).
    private var currentRelayUrl: String? = null
    private val completeGroupLoadRelays = mutableSetOf<String>()

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

    // Group admins from kind 39001: groupId -> list of admin pubkeys
    private val _groupAdmins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val groupAdmins: StateFlow<Map<String, List<String>>> = _groupAdmins.asStateFlow()

    companion object {
        const val PAGE_SIZE = 50
        const val LOADING_TIMEOUT_MS = 10_000L // 10 seconds timeout for loading
        const val MAX_PERSISTED_MESSAGES = 100 // Limit messages per group for storage
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
     * Falls back to the primary client if the group's relay is not connected.
     */
    private suspend fun clientForGroup(groupId: String): NostrGroupClient? {
        val relayUrl = getRelayForGroup(groupId)
        return if (relayUrl != null) {
            connectionManager.getClientForRelay(relayUrl) ?: connectionManager.getPrimaryClient()
        } else {
            connectionManager.getPrimaryClient()
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
        isPrivate: Boolean,
        isClosed: Boolean,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit
    ): Result<String> {
        val currentClient = connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(currentRelayUrl))

        return try {
            // Generate a suggested group ID
            val suggestedId = buildString {
                repeat(32) { append("0123456789abcdef"[kotlin.random.Random.nextInt(16)]) }
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

            // kind 9002: edit-metadata — sets name, about, and access in one event
            val metaTags = mutableListOf(
                listOf("h", confirmedGroupId),
                listOf("name", name),
                if (isPrivate) listOf("private") else listOf("public"),
                if (isClosed) listOf("closed") else listOf("open")
            )
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
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
            // kind 9002: edit-metadata — name, about, visibility, access all in one event
            val metaTags = mutableListOf(
                listOf("h", groupId),
                listOf("name", name),
                if (isPrivate) listOf("private") else listOf("public"),
                if (isClosed) listOf("closed") else listOf("open")
            )
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
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

            // Clear event deduplicator so messages can be re-fetched on rejoin
            eventDeduplicator.clear()

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

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = null,
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
            if (subscriptionId == "group-list") {
                // Legacy path: mark the currently active relay
                val relay = currentRelayUrl
                if (relay != null) {
                    completeGroupLoadRelays.add(relay)
                    val count = _groupsByRelay.value[relay]?.size ?: 0
                    println("[Groups] EOSE group-list (legacy)  relay=$relay  groupsLoaded=$count")
                }
            } else {
                // New path: find the relay whose hash matches this subscription ID
                val matchedRelay = findRelayForGroupListSubId(subscriptionId)
                if (matchedRelay != null) {
                    completeGroupLoadRelays.add(matchedRelay)
                    val count = _groupsByRelay.value[matchedRelay]?.size ?: 0
                    println("[Groups] EOSE group-list  relay=$matchedRelay  groupsLoaded=$count")
                } else {
                    // Fallback: mark currently active relay
                    val relay = currentRelayUrl
                    if (relay != null) {
                        completeGroupLoadRelays.add(relay)
                        println("[Groups] EOSE group-list (fallback, no relay match)  subId=$subscriptionId  relay=$relay")
                    } else {
                        println("[Groups] EOSE group-list (unmatched, no active relay)  subId=$subscriptionId  knownRelays=${_groupsByRelay.value.keys}")
                    }
                }
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

            // Send and wait for OK response from relay
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
        _groups.value = (_groups.value + metadata).distinctBy { it.id }
        _groupsByRelay.update { current ->
            val relayGroups = current[relayUrl] ?: emptyList()
            val updated = (relayGroups + metadata).distinctBy { it.id }
            current + (relayUrl to updated)
        }
        if (wasNew) {
            val relayCount = _groupsByRelay.value[relayUrl]?.size ?: 0
            println("[Groups] +group  relay=$relayUrl  id=${metadata.id}  name=${metadata.name}  totalForRelay=$relayCount")
        }
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
        return members.members
    }

    /**
     * Request group members (kind 39002)
     */
    suspend fun requestGroupMembers(groupId: String): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestGroupMembers(groupId)
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
        subscriptionId: String? = null
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

        // Track message in state machine for cursor calculation
        if (subscriptionId != null) {
            trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
        }

        // Use atomic update for message list
        _messages.update { currentMap ->
            val currentMessages = currentMap[groupId] ?: emptyList()
            if (currentMessages.none { it.id == message.id }) {
                currentMap + (groupId to (currentMessages + message).sortedBy { it.createdAt })
            } else {
                currentMap
            }
        }

        return message.pubkey
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
    fun clearForRelaySwitch() {
        // Reset the current relay pointer so stragglers from the old connection
        // go to the per-relay cache only, not the live _groups list.
        // State is ADDITIVE — messages, groups, reactions, and members are
        // intentionally preserved across relay switches so no data is lost.
        currentRelayUrl = null

        // Reset observation tracking so subscriptions are re-established on the
        // new primary connection without leaking the old group set.
        observedGroups.clear()

        scope.launch {
            loadingRegistry.clear()
            eventDeduplicator.clear()
        }
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
    fun clear() {
        completeGroupLoadRelays.clear()
        _groupsByRelay.value = emptyMap()
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
