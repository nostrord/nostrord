package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 */
class GroupManager(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventDeduplicator = EventDeduplicator()

    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()

    private val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val joinedGroups: StateFlow<Set<String>> = _joinedGroups.asStateFlow()

    // Pagination state per group
    private val _isLoadingMore = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isLoadingMore: StateFlow<Map<String, Boolean>> = _isLoadingMore.asStateFlow()

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

    // Track pagination subscriptions: subscriptionId -> groupId
    private val paginationSubscriptions = mutableMapOf<String, String>()
    // Track message count per subscription for determining hasMore
    private val subscriptionMessageCounts = mutableMapOf<String, Int>()
    // Track timeout jobs for each subscription to cancel if EOSE arrives
    private val loadingTimeoutJobs = mutableMapOf<String, Job>()

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
            _messages.value = _messages.value - groupId

            // Clear pagination state for this group
            _isLoadingMore.value = _isLoadingMore.value - groupId
            _hasMoreMessages.value = _hasMoreMessages.value - groupId

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
     * Request group messages (initial load)
     */
    suspend fun requestGroupMessages(groupId: String, channel: String? = null): Boolean {
        val currentClient = connectionManager.getPrimaryClient() ?: return false

        // Generate subscription ID and set up tracking BEFORE sending request
        // This avoids race condition where events arrive before tracking is set up
        val subId = "msg_${epochMillis()}"
        paginationSubscriptions[subId] = groupId
        subscriptionMessageCounts[subId] = 0
        _hasMoreMessages.value = _hasMoreMessages.value + (groupId to true)

        currentClient.requestGroupMessages(groupId, channel, until = null, limit = PAGE_SIZE, subscriptionId = subId)
        return true
    }

    /**
     * Load older messages for pagination (infinite scroll)
     */
    suspend fun loadMoreMessages(groupId: String, channel: String? = null): Boolean {
        // Don't load if already loading
        if (_isLoadingMore.value[groupId] == true) return false

        // Don't load if no more messages
        if (_hasMoreMessages.value[groupId] == false) return false

        val currentClient = connectionManager.getPrimaryClient() ?: return false

        val currentMessages = _messages.value[groupId] ?: emptyList()
        if (currentMessages.isEmpty()) return false

        // Get the oldest message timestamp
        val oldestTimestamp = currentMessages.minOfOrNull { it.createdAt } ?: return false

        // Generate subscription ID and set up tracking BEFORE sending request
        // This avoids race condition where events arrive before tracking is set up
        val subId = "msg_${epochMillis()}"
        paginationSubscriptions[subId] = groupId
        subscriptionMessageCounts[subId] = 0
        _isLoadingMore.value = _isLoadingMore.value + (groupId to true)

        // Set up a timeout to clear loading state if EOSE never arrives
        loadingTimeoutJobs[subId]?.cancel()
        loadingTimeoutJobs[subId] = scope.launch {
            delay(LOADING_TIMEOUT_MS)
            // If still in paginationSubscriptions after timeout, clean up
            if (paginationSubscriptions.containsKey(subId)) {
                paginationSubscriptions.remove(subId)
                subscriptionMessageCounts.remove(subId)
                _isLoadingMore.value = _isLoadingMore.value + (groupId to false)
            }
            loadingTimeoutJobs.remove(subId)
        }

        currentClient.requestGroupMessages(
            groupId = groupId,
            channel = channel,
            until = oldestTimestamp,
            limit = PAGE_SIZE,
            subscriptionId = subId
        )

        return true
    }

    /**
     * Called when EOSE is received for a subscription
     * Returns true if this was a pagination subscription
     */
    fun handleEose(subscriptionId: String): Boolean {
        // Cancel timeout job since EOSE arrived
        loadingTimeoutJobs.remove(subscriptionId)?.cancel()

        val groupId = paginationSubscriptions.remove(subscriptionId) ?: return false
        val messageCount = subscriptionMessageCounts.remove(subscriptionId) ?: 0

        _isLoadingMore.value = _isLoadingMore.value + (groupId to false)
        // If we received fewer messages than requested, there are no more
        if (messageCount < PAGE_SIZE) {
            _hasMoreMessages.value = _hasMoreMessages.value + (groupId to false)
        }
        return true
    }

    /**
     * Track message received for a subscription (for pagination counting)
     */
    fun trackMessageForSubscription(subscriptionId: String) {
        subscriptionMessageCounts[subscriptionId] = (subscriptionMessageCounts[subscriptionId] ?: 0) + 1
    }

    /**
     * Check if a subscription is a pagination subscription
     */
    fun isPaginationSubscription(subscriptionId: String): Boolean {
        return paginationSubscriptions.containsKey(subscriptionId)
    }

    /**
     * Send a message to a group
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

            // Add reply tag if replying to a message
            if (replyToMessageId != null) {
                tags.add(listOf("e", replyToMessageId))
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

            currentClient.send(message)
            Result.Success(Unit)
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
     * Handle incoming message
     * Returns the pubkey of the message sender if metadata should be fetched
     */
    fun handleMessage(message: NostrGroupClient.NostrMessage, rawMsg: String): String? {
        if (message.kind != 9 && message.kind != 9021 && message.kind != 9022) {
            return null
        }

        val messageId = message.id
        if (messageId.isBlank() || !eventDeduplicator.tryAddSync(messageId)) {
            return null // Duplicate message
        }

        val groupId = extractGroupIdFromMessage(rawMsg) ?: return null

        val currentMessages = _messages.value[groupId] ?: emptyList()
        // Check if message already exists in the list to prevent duplicate keys in LazyColumn
        if (currentMessages.none { it.id == message.id }) {
            _messages.value = _messages.value + (groupId to (currentMessages + message).sortedBy { it.createdAt })
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
        _reactions.value = emptyMap()
        _groupMembers.value = emptyMap()
        paginationSubscriptions.clear()
        subscriptionMessageCounts.clear()
        // Cancel all pending timeout jobs
        loadingTimeoutJobs.values.forEach { it.cancel() }
        loadingTimeoutJobs.clear()
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
