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
import kotlinx.serialization.json.*
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

    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()

    private val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val joinedGroups: StateFlow<Set<String>> = _joinedGroups.asStateFlow()

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

    companion object {
        const val PAGE_SIZE = 50
        const val LOADING_TIMEOUT_MS = 10_000L // 10 seconds timeout for loading
    }

    // Mutex for message list updates (separate from loading state)
    private val messageMutex = Mutex()

    // Track which groups have observation jobs to prevent memory leaks
    private val observedGroups = mutableSetOf<String>()
    private val observedGroupsLock = Any()

    /**
     * Load joined groups from storage
     */
    fun loadJoinedGroupsFromStorage(pubKey: String, relayUrl: String) {
        _joinedGroups.value = SecureStorage.getJoinedGroupsForRelay(pubKey, relayUrl)
    }

    /**
     * Set joined groups (from kind:10009)
     */
    fun setJoinedGroups(groups: Set<String>) {
        _joinedGroups.value = groups
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
        val currentClient = connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(currentRelayUrl))

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

            _joinedGroups.value = _joinedGroups.value + groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, currentRelayUrl, _joinedGroups.value)

            publishJoinedGroups()

            // Request group messages
            currentClient.requestGroupMessages(groupId)

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Group.JoinFailed(groupId, e))
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
        val currentClient = connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(currentRelayUrl))

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

            _joinedGroups.value = _joinedGroups.value - groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, currentRelayUrl, _joinedGroups.value)

            publishJoinedGroups()

            // Clear messages for this group
            _messages.update { it - groupId }

            // Clear pagination state for this group (use atomic updates)
            _isLoadingMore.update { it - groupId }
            _hasMoreMessages.update { it - groupId }
            _groupStates.update { it - groupId }

            // Remove from observed groups tracking
            synchronized(observedGroupsLock) {
                observedGroups.remove(groupId)
            }

            // Clear state machine for this group
            loadingRegistry.remove(groupId)

            // Clear event deduplicator so messages can be re-fetched on rejoin
            eventDeduplicator.clear()

            Result.Success(Unit)
        } catch (e: Exception) {
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
        val currentClient = connectionManager.getPrimaryClient() ?: return false

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
        } catch (e: Exception) {
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
    private fun observeStateChanges(groupId: String, controller: GroupLoadingController) {
        // Check if already observing this group
        synchronized(observedGroupsLock) {
            if (groupId in observedGroups) return
            observedGroups.add(groupId)
        }

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
        val currentClient = connectionManager.getPrimaryClient() ?: return false

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
        } catch (e: Exception) {
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
        return loadingRegistry.handleEose(subscriptionId)
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
     * Legacy overload for backward compatibility.
     */
    fun trackMessageForSubscription(subscriptionId: String) {
        // This overload can't track timestamp/eventId, but maintains API compatibility
        // Real tracking happens via the new overload
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
        val currentClient = connectionManager.getPrimaryClient() ?: return false
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
        } catch (e: Exception) {
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
        val currentClient = connectionManager.getPrimaryClient()
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
        } catch (e: Exception) {
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Handle incoming group metadata
     */
    fun handleGroupMetadata(metadata: GroupMetadata) {
        if (metadata.name != null) {
            _groups.value = (_groups.value + metadata).distinctBy { it.id }
        }
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
        val currentClient = connectionManager.getPrimaryClient() ?: return false
        currentClient.requestGroupMembers(groupId)
        return true
    }

    /**
     * Get members for a specific group
     */
    fun getMembersForGroup(groupId: String): List<String> {
        return _groupMembers.value[groupId] ?: emptyList()
    }

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
        if (message.kind != 9 && message.kind != 9021 && message.kind != 9022) {
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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clear all state
     */
    fun clear() {
        _groups.value = emptyList()
        _messages.value = emptyMap()
        _joinedGroups.value = emptySet()
        _isLoadingMore.value = emptyMap()
        _hasMoreMessages.value = emptyMap()
        _groupStates.value = emptyMap()
        _reactions.value = emptyMap()
        _groupMembers.value = emptyMap()

        // Clear observed groups tracking
        synchronized(observedGroupsLock) {
            observedGroups.clear()
        }

        // Clear state machine registry
        scope.launch {
            loadingRegistry.clear()
        }

        eventDeduplicator.clearSync()
    }

    /**
     * Clear joined groups for an account
     */
    fun clearJoinedGroupsForAccount(pubKey: String) {
        SecureStorage.clearAllJoinedGroupsForAccount(pubKey)
        _joinedGroups.value = emptySet()
    }
}
