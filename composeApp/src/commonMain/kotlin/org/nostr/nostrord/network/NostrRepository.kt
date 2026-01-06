package org.nostr.nostrord.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.urlDecode

/**
 * Repository for Nostr operations.
 * Coordinates between specialized managers for different concerns.
 *
 * Dependencies are injected via constructor for testability.
 * Use [NostrRepository.instance] for the default singleton.
 */
class NostrRepository(
    private val connectionManager: ConnectionManager,
    private val sessionManager: SessionManager,
    private val groupManager: GroupManager,
    private val metadataManager: MetadataManager,
    private val outboxManager: OutboxManager,
    private val unreadManager: UnreadManager,
    private val scope: CoroutineScope
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Expose connection state
    val currentRelayUrl: StateFlow<String> = connectionManager.currentRelayUrl
    val connectionState: StateFlow<ConnectionManager.ConnectionState> = connectionManager.connectionState

    // Expose group state
    val groups: StateFlow<List<GroupMetadata>> = groupManager.groups
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = groupManager.messages
    val joinedGroups: StateFlow<Set<String>> = groupManager.joinedGroups
    val isLoadingMore: StateFlow<Map<String, Boolean>> = groupManager.isLoadingMore
    val hasMoreMessages: StateFlow<Map<String, Boolean>> = groupManager.hasMoreMessages
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = groupManager.reactions
    val groupMembers: StateFlow<Map<String, List<String>>> = groupManager.groupMembers

    // Expose auth state
    val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
    val isBunkerConnected: StateFlow<Boolean> = sessionManager.isBunkerConnected
    val authUrl: StateFlow<String?> = sessionManager.authUrl

    // Expose metadata state
    val userMetadata: StateFlow<Map<String, UserMetadata>> = metadataManager.userMetadata
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = metadataManager.cachedEvents

    // Expose NIP-65 state
    val userRelayList: StateFlow<List<Nip65Relay>> = outboxManager.userRelayList

    // Expose unread state
    val unreadCounts: StateFlow<Map<String, Int>> = unreadManager.unreadCounts

    fun forceInitialized() {
        _isInitialized.value = true
    }

    suspend fun initialize() {
        connectionManager.loadSavedRelay()

        val restored = sessionManager.restoreSession()
        if (restored) {
            val pubkey = sessionManager.getPublicKey()
            if (pubkey != null) {
                groupManager.loadJoinedGroupsFromStorage(pubkey, connectionManager.currentRelayUrl.value)
                unreadManager.initialize(pubkey)
            }
            initializeOutboxModel()
            connect()
            // Fetch current user's metadata after login
            if (pubkey != null) {
                requestUserMetadata(setOf(pubkey))
            }
        }

        _isInitialized.value = true
    }

    fun clearAuthUrl() {
        sessionManager.clearAuthUrl()
    }

    suspend fun loginWithBunker(bunkerUrl: String): String {
        val userPubkey = sessionManager.loginWithBunker(bunkerUrl)
        unreadManager.initialize(userPubkey)
        initializeOutboxModel()
        connect()
        sessionManager.setLoggedIn(true)
        // Fetch current user's metadata after login
        requestUserMetadata(setOf(userPubkey))
        return userPubkey
    }

    suspend fun loginSuspend(privKey: String, pubKey: String) {
        sessionManager.loginWithPrivateKey(privKey, pubKey)
        unreadManager.initialize(pubKey)
        initializeOutboxModel()
        connect()
        sessionManager.setLoggedIn(true)
        // Fetch current user's metadata after login
        requestUserMetadata(setOf(pubKey))
    }

    suspend fun logout() {
        scope.coroutineContext.cancelChildren()

        sessionManager.getPublicKey()?.let { pubKey ->
            groupManager.clearJoinedGroupsForAccount(pubKey)
        }

        disconnect()
        connectionManager.clearAll()
        outboxManager.clear()
        sessionManager.logout()
        groupManager.clear()
        unreadManager.clear()
    }

    fun forgetBunkerConnection() {
        sessionManager.forgetBunkerConnection()
    }

    private suspend fun initializeOutboxModel() {
        val pubKey = sessionManager.getPublicKey() ?: return
        outboxManager.initialize(pubKey) { msg, client -> handleRelayMessage(msg, client) }
    }

    suspend fun connect() {
        connect(connectionManager.currentRelayUrl.value)
    }

    /**
     * Manually trigger reconnection to the relay.
     * Use this when auto-reconnection fails or user wants to retry.
     */
    suspend fun reconnect(): Boolean {
        val connected = connectionManager.reconnect()
        if (connected) {
            val client = connectionManager.getPrimaryClient()
            if (client != null) {
                sessionManager.sendAuthIfNeeded(client)
                client.requestGroups()

                // Re-request messages for current group if any
                val pubKey = sessionManager.getPublicKey() ?: ""
                if (pubKey.isNotEmpty()) {
                    groupManager.loadJoinedGroupsFromStorage(pubKey, connectionManager.currentRelayUrl.value)
                }
            }
        }
        return connected
    }

    private suspend fun connect(relayUrl: String) {
        val connected = connectionManager.connectPrimary(relayUrl) { msg, client ->
            handleMessage(msg, client)
        }

        if (connected) {
            val client = connectionManager.getPrimaryClient()
            if (client != null) {
                sessionManager.sendAuthIfNeeded(client)
                client.requestGroups()
            }
        }
    }

    suspend fun switchRelay(newRelayUrl: String) {
        disconnect()

        connectionManager.switchRelay(newRelayUrl) { msg, client ->
            handleMessage(msg, client)
        }

        val pubKey = sessionManager.getPublicKey() ?: ""
        groupManager.loadJoinedGroupsFromStorage(pubKey, newRelayUrl)

        val client = connectionManager.getPrimaryClient()
        if (client != null) {
            sessionManager.sendAuthIfNeeded(client)
            client.requestGroups()
        }

        outboxManager.resetKind10009State()

        if (!connectionManager.hasPoolConnections()) {
            initializeOutboxModel()
        }

        outboxManager.loadJoinedGroupsFromNostr(pubKey) { msg, c -> handleRelayMessage(msg, c) }
    }

    suspend fun disconnect() {
        connectionManager.disconnectPrimary()
        groupManager.clear()
    }

    // Auth delegation
    fun getPublicKey(): String? = sessionManager.getPublicKey()
    fun getPrivateKey(): String? = sessionManager.getPrivateKey()
    fun isUsingBunker(): Boolean = sessionManager.isUsingBunker()
    fun isBunkerReady(): Boolean = sessionManager.isBunkerReady()
    suspend fun ensureBunkerConnected(): Boolean = sessionManager.ensureBunkerConnected()

    // Group operations
    suspend fun joinGroup(groupId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.joinGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() }
        )
    }

    suspend fun leaveGroup(groupId: String, reason: String? = null): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.leaveGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            reason = reason,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() }
        )
    }

    fun isGroupJoined(groupId: String): Boolean = groupManager.isGroupJoined(groupId)

    suspend fun requestGroupMessages(groupId: String, channel: String? = null) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupMessages(groupId, channel)
        // Also request group members (kind 39002) when loading a group
        groupManager.requestGroupMembers(groupId)
    }

    /**
     * Request group members (kind 39002) for a specific group.
     */
    suspend fun requestGroupMembers(groupId: String) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupMembers(groupId)
    }

    suspend fun loadMoreMessages(groupId: String, channel: String? = null): Boolean {
        return groupManager.loadMoreMessages(groupId, channel)
    }

    suspend fun sendMessage(groupId: String, content: String, channel: String? = null, mentions: Map<String, String> = emptyMap(), replyToMessageId: String? = null): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendMessage(
            groupId = groupId,
            content = content,
            pubKey = pubKey,
            channel = channel,
            mentions = mentions,
            replyToMessageId = replyToMessageId,
            signEvent = { sessionManager.signEvent(it) }
        )
    }

    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> {
        return groupManager.getMessagesForGroup(groupId)
    }

    // Unread message operations
    fun markGroupAsRead(groupId: String) {
        unreadManager.markAsRead(groupId)
    }

    fun getUnreadCount(groupId: String): Int {
        return unreadManager.getUnreadCount(groupId)
    }

    fun updateUnreadCount(groupId: String, messages: List<NostrGroupClient.NostrMessage>) {
        unreadManager.updateUnreadCount(groupId, messages)
    }

    fun getLastReadTimestamp(groupId: String): Long? {
        return unreadManager.getLastReadTimestamp(groupId)
    }

    // Metadata operations
    suspend fun requestUserMetadata(pubkeys: Set<String>) {
        metadataManager.requestUserMetadata(pubkeys) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Update the current user's profile metadata (kind 0 event).
     */
    suspend fun updateProfileMetadata(
        displayName: String? = null,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        nip05: String? = null
    ): kotlin.Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return kotlin.Result.failure(Exception("Not logged in"))

        return try {
            // Build metadata content JSON
            val content = buildJsonObject {
                displayName?.let { put("display_name", it) }
                name?.let { put("name", it) }
                about?.let { put("about", it) }
                picture?.let { put("picture", it) }
                nip05?.let { put("nip05", it) }
            }.toString()

            // Create kind 0 event
            val event = org.nostr.nostrord.nostr.Event(
                pubkey = pubKey,
                createdAt = org.nostr.nostrord.utils.epochSeconds(),
                kind = 0,
                tags = emptyList(),
                content = content
            )

            // Sign the event
            val signedEvent = sessionManager.signEvent(event)

            // Build event message in correct format
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to user's write relays
            val writeRelays = outboxManager.getWriteRelays()

            if (writeRelays.isEmpty()) {
                // Fallback to current relay if no write relays configured
                val client = connectionManager.getPrimaryClient()
                if (client != null) {
                    client.send(message)
                } else {
                    return kotlin.Result.failure(Exception("No relay connection"))
                }
            } else {
                // Publish to all write relays using connection manager
                connectionManager.sendToRelays(writeRelays, message) { _, _ -> /* ignore responses */ }
            }

            // Update local cache
            val newMetadata = UserMetadata(
                pubkey = pubKey,
                name = name,
                displayName = displayName,
                picture = picture,
                about = about,
                nip05 = nip05
            )
            metadataManager.updateLocalMetadata(pubKey, newMetadata)

            kotlin.Result.success(Unit)
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    suspend fun requestEventById(eventId: String, relayHints: List<String> = emptyList(), author: String? = null) {
        metadataManager.requestEventById(eventId, relayHints, author) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Request a quoted event from the primary relay.
     * Used for q tags in group messages where the quoted event is on the same relay.
     */
    suspend fun requestQuotedEvent(eventId: String) {
        // Skip if already cached
        if (metadataManager.hasCachedEvent(eventId)) return

        val client = connectionManager.getPrimaryClient() ?: return
        client.requestEventById(eventId)
    }

    // Outbox operations
    suspend fun requestRelayLists(pubkeys: Set<String>) {
        outboxManager.requestRelayLists(pubkeys) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    fun getRelayListForPubkey(pubkey: String): List<Nip65Relay> {
        return outboxManager.getCachedRelayList(pubkey)
    }

    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList()
    ): List<String> {
        return outboxManager.selectOutboxRelays(
            authors = authors,
            taggedPubkeys = taggedPubkeys,
            explicitRelays = explicitRelays,
            currentNip29Relay = connectionManager.currentRelayUrl.value
        )
    }

    private suspend fun publishJoinedGroupsList() {
        val pubKey = sessionManager.getPublicKey() ?: return
        outboxManager.publishJoinedGroupsList(
            pubKey = pubKey,
            joinedGroups = groupManager.joinedGroups.value,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            messageHandler = { msg, client -> handleRelayMessage(msg, client) }
        )
    }

    // Message handling
    private fun handleMessage(msg: String, client: NostrGroupClient) {
        // Handle NIP-42 AUTH challenge first
        val authChallenge = client.parseAuthChallenge(msg)
        if (authChallenge != null) {
            scope.launch {
                sessionManager.handleAuthChallenge(client, authChallenge)
            }
            return
        }

        // Handle EOSE for pagination
        try {
            val arr = json.parseToJsonElement(msg).jsonArray
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
                val subId = arr[1].jsonPrimitive.content
                groupManager.handleEose(subId)
                return
            }

            // Handle event_* subscriptions (fetched events by ID for quotes)
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                if (subId.startsWith("event_")) {
                    val event = arr[2].jsonObject
                    metadataManager.parseAndCacheEvent(event)?.let { cachedEvent ->
                        if (!metadataManager.hasMetadata(cachedEvent.pubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(cachedEvent.pubkey))
                            }
                        }
                    }
                    return
                }
            }
        } catch (_: Exception) {}

        // Handle group metadata
        val groupMetadata = client.parseGroupMetadata(msg)
        if (groupMetadata != null) {
            groupManager.handleGroupMetadata(groupMetadata)
            return
        }

        // Handle group members (kind 39002)
        val groupMembers = client.parseGroupMembers(msg)
        if (groupMembers != null) {
            val memberPubkeys = groupManager.handleGroupMembers(groupMembers)
            // Fetch metadata for all members
            val pubkeysNeedingMetadata = memberPubkeys.filter { !metadataManager.hasMetadata(it) }
            if (pubkeysNeedingMetadata.isNotEmpty()) {
                scope.launch {
                    requestUserMetadata(pubkeysNeedingMetadata.toSet())
                }
            }
            return
        }

        // Handle user metadata
        val userMetadata = client.parseUserMetadata(msg)
        if (userMetadata != null) {
            val (pubkey, metadata) = userMetadata
            metadataManager.handleMetadataEvent(pubkey, metadata)
            return
        }

        // Handle reactions (kind 7)
        val reaction = client.parseReaction(msg)
        if (reaction != null) {
            val reactorPubkey = groupManager.handleReaction(reaction)
            if (reactorPubkey != null && !metadataManager.hasMetadata(reactorPubkey)) {
                scope.launch {
                    requestUserMetadata(setOf(reactorPubkey))
                }
            }
            return
        }

        // Handle group messages
        val message = client.parseMessage(msg)
        if (message != null) {
            // Track message for pagination counting
            try {
                val arr = json.parseToJsonElement(msg).jsonArray
                if (arr.size >= 2) {
                    val subId = arr[1].jsonPrimitive.content
                    if (groupManager.isPaginationSubscription(subId)) {
                        groupManager.trackMessageForSubscription(subId)
                    }
                }
            } catch (_: Exception) {}

            val senderPubkey = groupManager.handleMessage(message, msg)
            if (senderPubkey != null && !metadataManager.hasMetadata(senderPubkey)) {
                scope.launch {
                    requestUserMetadata(setOf(senderPubkey))
                    requestRelayLists(setOf(senderPubkey))
                }
            }
        }
    }

    private fun handleRelayMessage(msg: String, client: NostrGroupClient) {
        try {
            val arr = json.parseToJsonElement(msg).jsonArray

            // Handle EOSE
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
                val subId = arr[1].jsonPrimitive.content
                outboxManager.handleEose(subId)
                return
            }

            // Handle EVENT
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int

                // Handle event_* subscriptions (fetched events by ID)
                if (subId.startsWith("event_")) {
                    metadataManager.parseAndCacheEvent(event)?.let { cachedEvent ->
                        if (!metadataManager.hasMetadata(cachedEvent.pubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(cachedEvent.pubkey))
                            }
                        }
                    }
                    return
                }

                // Handle kind:10009 (joined groups)
                if (kind == 10009) {
                    val pubKey = sessionManager.getPublicKey() ?: ""
                    scope.launch {
                        outboxManager.handleKind10009Event(
                            event = event,
                            currentRelayUrl = connectionManager.currentRelayUrl.value,
                            pubKey = pubKey,
                            onGroupsUpdated = { groups -> groupManager.setJoinedGroups(groups) }
                        )
                    }
                    return
                }

                // Handle kind:10002 (NIP-65 relay list)
                if (kind == 10002) {
                    outboxManager.handleKind10002Event(event, sessionManager.getPublicKey())
                    return
                }
            }
        } catch (_: Exception) {}

        // Handle user metadata
        val userMetadata = client.parseUserMetadata(msg)
        if (userMetadata != null) {
            val (pubkey, metadata) = userMetadata
            metadataManager.handleMetadataEvent(pubkey, metadata)
        }
    }

    companion object {
        /**
         * Default singleton instance using AppModule.
         * Provides backward compatibility with code that uses NostrRepository directly.
         */
        private val instance: NostrRepository by lazy {
            org.nostr.nostrord.di.AppModule.nostrRepository
        }

        // Static accessors for backward compatibility
        val isInitialized get() = instance.isInitialized
        val currentRelayUrl get() = instance.currentRelayUrl
        val connectionState get() = instance.connectionState
        val groups get() = instance.groups
        val messages get() = instance.messages
        val joinedGroups get() = instance.joinedGroups
        val isLoadingMore get() = instance.isLoadingMore
        val hasMoreMessages get() = instance.hasMoreMessages
        val reactions get() = instance.reactions
        val groupMembers get() = instance.groupMembers
        val isLoggedIn get() = instance.isLoggedIn
        val isBunkerConnected get() = instance.isBunkerConnected
        val authUrl get() = instance.authUrl
        val userMetadata get() = instance.userMetadata
        val cachedEvents get() = instance.cachedEvents
        val userRelayList get() = instance.userRelayList
        val unreadCounts get() = instance.unreadCounts

        fun forceInitialized() = instance.forceInitialized()
        suspend fun initialize() = instance.initialize()
        fun clearAuthUrl() = instance.clearAuthUrl()
        suspend fun loginWithBunker(bunkerUrl: String) = instance.loginWithBunker(bunkerUrl)
        suspend fun loginSuspend(privKey: String, pubKey: String) = instance.loginSuspend(privKey, pubKey)
        suspend fun logout() = instance.logout()
        fun forgetBunkerConnection() = instance.forgetBunkerConnection()
        suspend fun connect() = instance.connect()
        suspend fun reconnect() = instance.reconnect()
        suspend fun switchRelay(newRelayUrl: String) = instance.switchRelay(newRelayUrl)
        suspend fun disconnect() = instance.disconnect()
        fun getPublicKey() = instance.getPublicKey()
        fun getPrivateKey() = instance.getPrivateKey()
        fun isUsingBunker() = instance.isUsingBunker()
        fun isBunkerReady() = instance.isBunkerReady()
        suspend fun ensureBunkerConnected() = instance.ensureBunkerConnected()
        suspend fun joinGroup(groupId: String) = instance.joinGroup(groupId)
        suspend fun leaveGroup(groupId: String, reason: String? = null) = instance.leaveGroup(groupId, reason)
        fun isGroupJoined(groupId: String) = instance.isGroupJoined(groupId)
        suspend fun requestGroupMessages(groupId: String, channel: String? = null) = instance.requestGroupMessages(groupId, channel)
        suspend fun requestGroupMembers(groupId: String) = instance.requestGroupMembers(groupId)
        suspend fun loadMoreMessages(groupId: String, channel: String? = null) = instance.loadMoreMessages(groupId, channel)
        suspend fun sendMessage(groupId: String, content: String, channel: String? = null, mentions: Map<String, String> = emptyMap(), replyToMessageId: String? = null) =
            instance.sendMessage(groupId, content, channel, mentions, replyToMessageId)
        fun getMessagesForGroup(groupId: String) = instance.getMessagesForGroup(groupId)
        fun markGroupAsRead(groupId: String) = instance.markGroupAsRead(groupId)
        fun getUnreadCount(groupId: String) = instance.getUnreadCount(groupId)
        fun updateUnreadCount(groupId: String, messages: List<NostrGroupClient.NostrMessage>) = instance.updateUnreadCount(groupId, messages)
        fun getLastReadTimestamp(groupId: String) = instance.getLastReadTimestamp(groupId)
        suspend fun requestUserMetadata(pubkeys: Set<String>) = instance.requestUserMetadata(pubkeys)
        suspend fun updateProfileMetadata(
            displayName: String? = null,
            name: String? = null,
            about: String? = null,
            picture: String? = null,
            nip05: String? = null
        ) = instance.updateProfileMetadata(displayName, name, about, picture, nip05)
        suspend fun requestEventById(eventId: String, relayHints: List<String> = emptyList(), author: String? = null) =
            instance.requestEventById(eventId, relayHints, author)
        suspend fun requestQuotedEvent(eventId: String) = instance.requestQuotedEvent(eventId)
        suspend fun requestRelayLists(pubkeys: Set<String>) = instance.requestRelayLists(pubkeys)
        fun getRelayListForPubkey(pubkey: String) = instance.getRelayListForPubkey(pubkey)
        fun selectOutboxRelays(authors: List<String> = emptyList(), taggedPubkeys: List<String> = emptyList(), explicitRelays: List<String> = emptyList()) =
            instance.selectOutboxRelays(authors, taggedPubkeys, explicitRelays)
    }
}

// Helper function for parsing bunker URLs
data class BunkerInfo(
    val pubkey: String,
    val relays: List<String>,
    val secret: String?
)

fun parseBunkerUrl(url: String): BunkerInfo {
    val trimmed = url.trim()

    require(trimmed.startsWith("bunker://")) {
        "Invalid bunker URL: must start with bunker://"
    }

    val withoutScheme = trimmed.removePrefix("bunker://")
    val parts = withoutScheme.split("?", limit = 2)

    val pubkey = parts[0]
    require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid pubkey in bunker URL"
    }

    val relays = mutableListOf<String>()
    var secret: String? = null

    if (parts.size > 1) {
        val queryParams = parts[1].split("&")
        for (param in queryParams) {
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                val key = kv[0]
                val value = kv[1].urlDecode()
                when (key) {
                    "relay" -> relays.add(value)
                    "secret" -> secret = value
                }
            }
        }
    }

    require(relays.isNotEmpty()) {
        "Bunker URL must contain at least one relay"
    }

    return BunkerInfo(pubkey, relays, secret)
}
