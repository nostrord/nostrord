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
    private val pendingEventManager: org.nostr.nostrord.network.managers.PendingEventManager? = null,
    private val scope: CoroutineScope
) : NostrRepositoryApi {
    private val json = Json { ignoreUnknownKeys = true }

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Expose connection state
    override val currentRelayUrl: StateFlow<String> = connectionManager.currentRelayUrl
    override val connectionState: StateFlow<ConnectionManager.ConnectionState> = connectionManager.connectionState

    // Expose group state
    override val groups: StateFlow<List<GroupMetadata>> = groupManager.groups
    override val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = groupManager.messages
    override val joinedGroups: StateFlow<Set<String>> = groupManager.joinedGroups
    override val isLoadingMore: StateFlow<Map<String, Boolean>> = groupManager.isLoadingMore
    override val hasMoreMessages: StateFlow<Map<String, Boolean>> = groupManager.hasMoreMessages
    override val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = groupManager.reactions
    override val groupMembers: StateFlow<Map<String, List<String>>> = groupManager.groupMembers
    override val groupAdmins: StateFlow<Map<String, List<String>>> = groupManager.groupAdmins

    // Expose auth state
    override val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn
    override val isBunkerConnected: StateFlow<Boolean> = sessionManager.isBunkerConnected
    override val isBunkerVerifying: StateFlow<Boolean> = sessionManager.isBunkerVerifying
    override val authUrl: StateFlow<String?> = sessionManager.authUrl

    // Expose metadata state
    override val userMetadata: StateFlow<Map<String, UserMetadata>> = metadataManager.userMetadata
    override val cachedEvents: StateFlow<Map<String, CachedEvent>> = metadataManager.cachedEvents

    // Expose NIP-65 state
    override val userRelayList: StateFlow<List<Nip65Relay>> = outboxManager.userRelayList

    // Expose unread state
    override val unreadCounts: StateFlow<Map<String, Int>> = unreadManager.unreadCounts

    override fun forceInitialized() {
        _isInitialized.value = true
    }

    override suspend fun initialize() {
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

    override fun clearAuthUrl() {
        sessionManager.clearAuthUrl()
    }

    override suspend fun loginWithBunker(bunkerUrl: String): Result<String> {
        return try {
            val userPubkey = sessionManager.loginWithBunker(bunkerUrl)
            unreadManager.initialize(userPubkey)
            initializeOutboxModel()
            connect()
            sessionManager.setLoggedIn(true)
            requestUserMetadata(setOf(userPubkey))
            Result.Success(userPubkey)
        } catch (e: Exception) {
            Result.Error(AppError.Auth.BunkerError(e.message ?: "Bunker connection failed", e))
        }
    }

    override suspend fun createNostrConnectSession(relays: List<String>): Pair<String, org.nostr.nostrord.nostr.Nip46Client> {
        return sessionManager.createNostrConnectSession(relays)
    }

    override suspend fun completeNostrConnectLogin(
        client: org.nostr.nostrord.nostr.Nip46Client,
        relays: List<String>
    ): String {
        val userPubkey = sessionManager.completeNostrConnectLogin(client, relays)
        unreadManager.initialize(userPubkey)
        // Connect to NIP-29 relay first (what the user sees), outbox model in background
        scope.launch { initializeOutboxModel() }
        connect()
        sessionManager.setLoggedIn(true)
        scope.launch { requestUserMetadata(setOf(userPubkey)) }
        return userPubkey
    }

    override suspend fun loginSuspend(privKey: String, pubKey: String): Result<Unit> {
        return try {
            sessionManager.loginWithPrivateKey(privKey, pubKey)
            unreadManager.initialize(pubKey)
            initializeOutboxModel()
            connect()
            sessionManager.setLoggedIn(true)
            requestUserMetadata(setOf(pubKey))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
        }
    }

    override suspend fun loginWithNip07(pubkey: String): Result<Unit> {
        return try {
            sessionManager.loginWithNip07(pubkey)
            unreadManager.initialize(pubkey)
            initializeOutboxModel()
            connect()
            sessionManager.setLoggedIn(true)
            requestUserMetadata(setOf(pubkey))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
        }
    }

    override suspend fun logout() {
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

    override fun forgetBunkerConnection() {
        sessionManager.forgetBunkerConnection()
    }

    private suspend fun initializeOutboxModel() {
        val pubKey = sessionManager.getPublicKey()
        if (pubKey == null) return
        outboxManager.initialize(pubKey) { msg, client -> handleRelayMessage(msg, client) }
    }

    override suspend fun connect() {
        connect(connectionManager.currentRelayUrl.value)
    }

    /**
     * Manually trigger reconnection to the relay.
     * Use this when auto-reconnection fails or user wants to retry.
     */
    override suspend fun reconnect(): Boolean {
        val connected = connectionManager.reconnect()
        if (connected) {
            val client = connectionManager.getPrimaryClient()
            if (client != null) {
                sessionManager.sendAuthIfNeeded(client)
                groupManager.restoreGroupsForRelay(connectionManager.currentRelayUrl.value)
                client.requestGroups()

                // Re-request messages for current group if any
                val pubKey = sessionManager.getPublicKey() ?: ""
                if (pubKey.isNotEmpty()) {
                    groupManager.loadJoinedGroupsFromStorage(pubKey, connectionManager.currentRelayUrl.value)
                }

                // Retry any pending events that were queued while offline
                pendingEventManager?.onConnectionRestored()
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
                // Wire up connection lost handler to notify group manager
                client.onConnectionLost = {
                    scope.launch {
                        groupManager.handleConnectionLost()
                    }
                }

                sessionManager.sendAuthIfNeeded(client)
                groupManager.restoreGroupsForRelay(relayUrl)
                if (!groupManager.hasCachedGroupsForRelay(relayUrl)) {
                    client.requestGroups()
                }
            }
        } else {
        }
    }

    override suspend fun switchRelay(newRelayUrl: String) {
        // Clears messages/state but NOT the group metadata cache (_groupsByRelay).
        groupManager.clearForRelaySwitch()

        connectionManager.switchRelay(newRelayUrl) { msg, client ->
            handleMessage(msg, client)
        }

        val pubKey = sessionManager.getPublicKey() ?: ""

        groupManager.restoreGroupsForRelay(newRelayUrl)

        val cachedJoined = outboxManager.getJoinedGroupsForRelay(newRelayUrl)
        if (cachedJoined.isNotEmpty()) {
            groupManager.setJoinedGroups(cachedJoined)
        } else {
            groupManager.loadJoinedGroupsFromStorage(pubKey, newRelayUrl)
        }

        val client = connectionManager.getPrimaryClient()
        if (client != null) {
            sessionManager.sendAuthIfNeeded(client)
            // Skip re-fetch if cached; re-fetching races against restored state.
            if (!groupManager.hasCachedGroupsForRelay(newRelayUrl)) {
                client.requestGroups()
            }
        }

        outboxManager.resetKind10009State()

        if (!connectionManager.hasPoolConnections()) {
            initializeOutboxModel()
        }

        // Fetch only on first load; in-memory cache is source of truth after that.
        if (!outboxManager.hasJoinedGroupsData()) {
            scope.launch {
                outboxManager.loadJoinedGroupsFromNostr(pubKey) { msg, c -> handleRelayMessage(msg, c) }
            }
        }
    }

    override suspend fun disconnect() {
        connectionManager.disconnectPrimary()
        groupManager.clear()
    }

    // Auth delegation
    override fun getPublicKey(): String? = sessionManager.getPublicKey()
    override fun getPrivateKey(): String? = sessionManager.getPrivateKey()
    override fun isUsingBunker(): Boolean = sessionManager.isUsingBunker()
    override fun isBunkerReady(): Boolean = sessionManager.isBunkerReady()
    override suspend fun ensureBunkerConnected(): Boolean = sessionManager.ensureBunkerConnected()

    // Group operations
    override suspend fun joinGroup(groupId: String): Result<Unit> {
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

    override suspend fun createGroup(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean
    ): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        if (relayUrl != connectionManager.currentRelayUrl.value) {
            switchRelay(relayUrl)
        }
        return groupManager.createGroup(
            name = name,
            about = about,
            isPrivate = isPrivate,
            isClosed = isClosed,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() }
        )
    }

    override suspend fun leaveGroup(groupId: String, reason: String?): Result<Unit> {
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

    override fun isGroupJoined(groupId: String): Boolean = groupManager.isGroupJoined(groupId)

    override suspend fun requestGroupMessages(groupId: String, channel: String?) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupMessages(groupId, channel)
        // Also request group metadata (kind 39000), members (kind 39002) and admins (kind 39001)
        connectionManager.getPrimaryClient()?.requestGroupMetadata(groupId)
        groupManager.requestGroupMembers(groupId)
        groupManager.requestGroupAdmins(groupId)
    }

    /**
     * Request group members (kind 39002) for a specific group.
     */
    override suspend fun requestGroupMembers(groupId: String) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupMembers(groupId)
    }

    /**
     * Request group admins (kind 39001) for a specific group.
     */
    override suspend fun requestGroupAdmins(groupId: String) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupAdmins(groupId)
    }

    override suspend fun refreshGroupMetadata(groupId: String) {
        val client = connectionManager.getPrimaryClient() ?: return
        client.requestGroupMetadata(groupId)
        client.requestGroupAdmins(groupId)
    }

    override suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.editGroup(
            groupId = groupId,
            name = name,
            about = about,
            isPrivate = isPrivate,
            isClosed = isClosed,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) }
        )
    }

    override suspend fun deleteGroup(groupId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.deleteGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() }
        )
    }

    override suspend fun loadMoreMessages(groupId: String, channel: String?): Boolean {
        return groupManager.loadMoreMessages(groupId, channel)
    }

    override suspend fun sendMessage(groupId: String, content: String, channel: String?, mentions: Map<String, String>, replyToMessageId: String?): Result<Unit> {
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

    override suspend fun deleteMessage(groupId: String, messageId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.deleteMessage(
            groupId = groupId,
            messageId = messageId,
            pubKey = pubKey,
            signEvent = { sessionManager.signEvent(it) }
        )
    }

    override fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> {
        return groupManager.getMessagesForGroup(groupId)
    }

    // Unread message operations
    override fun markGroupAsRead(groupId: String) {
        unreadManager.markAsRead(groupId)
    }

    override fun getUnreadCount(groupId: String): Int {
        return unreadManager.getUnreadCount(groupId)
    }

    override fun updateUnreadCount(groupId: String, messages: List<NostrGroupClient.NostrMessage>) {
        unreadManager.updateUnreadCount(groupId, messages)
    }

    override fun getLastReadTimestamp(groupId: String): Long? {
        return unreadManager.getLastReadTimestamp(groupId)
    }

    // Metadata operations
    override suspend fun requestUserMetadata(pubkeys: Set<String>) {
        metadataManager.requestUserMetadata(pubkeys) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Update the current user's profile metadata (kind 0 event).
     */
    override suspend fun updateProfileMetadata(
        displayName: String?,
        name: String?,
        about: String?,
        picture: String?,
        nip05: String?
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)

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
                    return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
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

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to update profile", e))
        }
    }

    override suspend fun requestEventById(eventId: String, relayHints: List<String>, author: String?) {
        metadataManager.requestEventById(eventId, relayHints, author) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Request an addressable event (naddr) by its coordinates.
     * Addressable events are identified by kind, pubkey, and identifier (d-tag).
     */
    override suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relays: List<String>
    ) {
        metadataManager.requestAddressableEvent(kind, pubkey, identifier, relays) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    /**
     * Request a quoted event from the primary relay.
     * Used for q tags in group messages where the quoted event is on the same relay.
     */
    override suspend fun requestQuotedEvent(eventId: String) {
        // Skip if already cached
        if (metadataManager.hasCachedEvent(eventId)) return

        val client = connectionManager.getPrimaryClient() ?: return
        client.requestEventById(eventId)
    }

    // Outbox operations
    override suspend fun requestRelayLists(pubkeys: Set<String>) {
        outboxManager.requestRelayLists(pubkeys) { msg, client ->
            handleRelayMessage(msg, client)
        }
    }

    override fun getRelayListForPubkey(pubkey: String): List<Nip65Relay> {
        return outboxManager.getCachedRelayList(pubkey)
    }

    override fun selectOutboxRelays(
        authors: List<String>,
        taggedPubkeys: List<String>,
        explicitRelays: List<String>
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
    // Groups whose subscriptions were closed by the relay (e.g. auth-required)
    private val closedGroupSubscriptions = mutableSetOf<String>()

    private fun handleMessage(msg: String, client: NostrGroupClient) {
        // Handle NIP-42 AUTH challenge first
        val authChallenge = client.parseAuthChallenge(msg)
        if (authChallenge != null) {
            scope.launch {
                sessionManager.handleAuthChallenge(client, authChallenge)
                // After AUTH, re-subscribe for any groups that were blocked or are loaded
                resubscribeAfterAuth(client)
            }
            return
        }

        // Handle OK messages (NIP-01 relay response to published events)
        val okResponse = client.parseOkMessage(msg)
        if (okResponse != null) {
            val (eventId, success, message) = okResponse
            scope.launch {
                client.handleOkResponse(eventId, success, message)
            }
            return
        }

        // Handle EOSE for pagination
        // CRITICAL: EOSE handling must be async to allow pending message tracking to complete
        try {
            val arr = json.parseToJsonElement(msg).jsonArray
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
                val subId = arr[1].jsonPrimitive.content
                // Use scope.launch to ensure this runs after any pending message tracking
                scope.launch {
                    groupManager.handleEoseSuspend(subId)
                }
                return
            }

            // Handle CLOSED (relay closed the subscription, e.g. auth-required for private group)
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "CLOSED") {
                val subId = arr[1].jsonPrimitive.content
                val reason = if (arr.size >= 3) arr[2].jsonPrimitive.contentOrNull ?: "" else ""
                if (reason.contains("auth-required") || reason.contains("restricted")) {
                    // Extract groupId from subId if it's a message subscription, otherwise
                    // mark all currently loaded groups for re-subscription after auth
                    val activeGroupIds = groupManager.messages.value.keys +
                        groupManager.joinedGroups.value
                    closedGroupSubscriptions.addAll(activeGroupIds)
                }
                scope.launch { groupManager.handleEoseSuspend(subId) } // unblock any pending load
                return
            }

            // Handle event subscriptions (event_* or e_*) for quotes
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                if (subId.startsWith("event_") || subId.startsWith("e_")) {
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

        // Handle group metadata (kind:39000) — store under the source relay for per-relay caching.
        val groupMetadataWithSub = client.parseGroupMetadataWithSubId(msg)
        if (groupMetadataWithSub != null) {
            val (subId, groupMetadata) = groupMetadataWithSub
            groupManager.handleGroupMetadata(groupMetadata, client.getRelayUrl())
            // Notify any pending group creation waiting for this subscription
            scope.launch { client.handleGroupCreationEvent(subId, groupMetadata) }
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

        // Handle group admins (kind 39001)
        val groupAdmins = client.parseGroupAdmins(msg)
        if (groupAdmins != null) {
            groupManager.handleGroupAdmins(groupAdmins)
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
            // Extract subscription ID for state machine tracking
            val subscriptionId = try {
                val arr = json.parseToJsonElement(msg).jsonArray
                if (arr.size >= 2) arr[1].jsonPrimitive.content else null
            } catch (_: Exception) { null }

            // Track message in state machine BEFORE adding to message list
            // This ensures cursor calculation includes this message before EOSE processing
            if (subscriptionId != null) {
                scope.launch {
                    groupManager.trackMessageForSubscriptionSuspend(
                        subscriptionId,
                        message.createdAt,
                        message.id
                    )
                }
            }

            // Pass subscription ID to handleMessage for cursor tracking
            val senderPubkey = groupManager.handleMessage(message, msg, subscriptionId)
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

                // Handle event subscriptions (event_* or e_*)
                if (subId.startsWith("event_") || subId.startsWith("e_")) {
                    metadataManager.parseAndCacheEvent(event)?.let { cachedEvent ->
                        if (!metadataManager.hasMetadata(cachedEvent.pubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(cachedEvent.pubkey))
                            }
                        }
                    }
                    return
                }

                // Handle addressable event subscriptions (addr_* or a_*)
                if (subId.startsWith("addr_") || subId.startsWith("a_")) {
                    metadataManager.parseAndCacheAddressableEvent(event)?.let { cachedEvent ->
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

    /**
     * Re-subscribe for group messages after a successful NIP-42 AUTH.
     * Covers groups whose subscriptions were closed with auth-required,
     * as well as all currently loaded groups.
     */
    private suspend fun resubscribeAfterAuth(client: NostrGroupClient) {
        // Re-fetch the full group list (includes private groups now that we're authed)
        client.requestGroups()

        // Re-subscribe for all active/joined groups
        val groupIds = closedGroupSubscriptions +
            groupManager.messages.value.keys +
            groupManager.joinedGroups.value
        closedGroupSubscriptions.clear()
        for (groupId in groupIds) {
            client.requestGroupMessages(groupId)
            client.requestGroupMetadata(groupId)
            client.requestGroupAdmins(groupId)
            client.requestGroupMembers(groupId)
        }
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
