package org.nostr.nostrord.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.Result

/**
 * In-memory fake of [NostrRepositoryApi] for ViewModel unit tests.
 *
 * Defaults every state to a sensible empty/false value.
 * Individual tests can override the `Mutable*` fields or provide lambdas to control behavior.
 */
class FakeNostrRepository : NostrRepositoryApi {

    // -------------------------------------------------------------------------
    // Mutable state exposed for test setup
    // -------------------------------------------------------------------------

    val _isInitialized = MutableStateFlow(false)
    val _isLoggedIn = MutableStateFlow(false)
    val _isBunkerVerifying = MutableStateFlow(false)
    val _isBunkerConnected = MutableStateFlow(false)
    val _authUrl = MutableStateFlow<String?>(null)
    val _currentRelayUrl = MutableStateFlow("wss://relay.example.com")
    val _connectionState = MutableStateFlow<ConnectionManager.ConnectionState>(ConnectionManager.ConnectionState.Disconnected)
    val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val _isLoadingMore = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val _hasMoreMessages = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val _reactions = MutableStateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>(emptyMap())
    val _groupMembers = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val _groupAdmins = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val _userRelayList = MutableStateFlow<List<Nip65Relay>>(emptyList())
    val _relayMetadata = MutableStateFlow<Map<String, Nip11RelayInfo>>(emptyMap())
    val _loadingMembers = MutableStateFlow<Set<String>>(emptySet())

    // Configurable behaviour
    var initializeAction: suspend () -> Unit = { _isInitialized.value = true }
    var loginSuspendAction: suspend (String, String) -> Result<Unit> = { _, _ -> _isLoggedIn.value = true; Result.Success(Unit) }
    var loginWithNip07Action: suspend (String) -> Result<Unit> = { _isLoggedIn.value = true; Result.Success(Unit) }
    var loginWithBunkerAction: suspend (String) -> Result<String> = { Result.Success("pubkey") }
    var leaveGroupAction: suspend (String, String?) -> Result<Unit> = { _, _ -> Result.Success(Unit) }
    var sendMessageAction: suspend (String, String, String?, Map<String, String>, String?) -> Result<Unit> =
        { _, _, _, _, _ -> Result.Success(Unit) }
    var updateProfileMetadataAction: suspend (String?, String?, String?, String?, String?) -> Result<Unit> =
        { _, _, _, _, _ -> Result.Success(Unit) }
    var fakePublicKey: String? = null
    var fakePrivateKey: String? = null

    // Call log — tests can assert which methods were called
    val calls = mutableListOf<String>()

    // -------------------------------------------------------------------------
    // NostrRepositoryApi
    // -------------------------------------------------------------------------

    override val isInitialized: StateFlow<Boolean> = _isInitialized
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn
    override val isBunkerVerifying: StateFlow<Boolean> = _isBunkerVerifying
    override val isBunkerConnected: StateFlow<Boolean> = _isBunkerConnected
    override val authUrl: StateFlow<String?> = _authUrl
    override val currentRelayUrl: StateFlow<String> = _currentRelayUrl
    override val connectionState: StateFlow<ConnectionManager.ConnectionState> = _connectionState
    override val groups: StateFlow<List<GroupMetadata>> = _groups
    override val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = MutableStateFlow(emptyMap())
    override val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages
    override val joinedGroups: StateFlow<Set<String>> = _joinedGroups
    override val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = MutableStateFlow(emptyMap())
    override val isLoadingMore: StateFlow<Map<String, Boolean>> = _isLoadingMore
    override val hasMoreMessages: StateFlow<Map<String, Boolean>> = _hasMoreMessages
    override val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = _reactions
    override val groupMembers: StateFlow<Map<String, List<String>>> = _groupMembers
    override val groupAdmins: StateFlow<Map<String, List<String>>> = _groupAdmins
    override val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata
    override val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents
    override val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts
    override val userRelayList: StateFlow<List<Nip65Relay>> = _userRelayList
    override val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadata
    override val loadingMembers: StateFlow<Set<String>> = _loadingMembers

    override fun forceInitialized() { _isInitialized.value = true }
    override suspend fun initialize() { calls += "initialize"; initializeAction() }

    override fun clearAuthUrl() { _authUrl.value = null }
    override fun getPublicKey(): String? = fakePublicKey
    override fun getPrivateKey(): String? = fakePrivateKey
    override fun isUsingBunker(): Boolean = false
    override fun isBunkerReady(): Boolean = false
    override suspend fun ensureBunkerConnected(): Boolean = true
    override fun forgetBunkerConnection() {}

    override suspend fun loginSuspend(privKey: String, pubKey: String): Result<Unit> {
        calls += "loginSuspend"
        return loginSuspendAction(privKey, pubKey)
    }

    override suspend fun loginWithNip07(pubkey: String): Result<Unit> {
        calls += "loginWithNip07"
        return loginWithNip07Action(pubkey)
    }

    override suspend fun loginWithBunker(bunkerUrl: String): Result<String> {
        calls += "loginWithBunker"
        return loginWithBunkerAction(bunkerUrl)
    }

    override suspend fun createNostrConnectSession(relays: List<String>): Pair<String, Nip46Client> =
        error("createNostrConnectSession not implemented in fake")

    override suspend fun completeNostrConnectLogin(client: Nip46Client, relays: List<String>): String =
        error("completeNostrConnectLogin not implemented in fake")

    override suspend fun logout() { calls += "logout"; _isLoggedIn.value = false }

    override suspend fun connect() {}
    override suspend fun reconnect(): Boolean = true
    override suspend fun switchRelay(newRelayUrl: String) { _currentRelayUrl.value = newRelayUrl }
    override suspend fun removeRelay(url: String) {}
    override suspend fun disconnect() {}

    override suspend fun createGroup(name: String, about: String?, relayUrl: String, isPrivate: Boolean, isClosed: Boolean, picture: String?, customGroupId: String?): Result<String> =
        Result.Success(customGroupId ?: "fake-group-id")

    override suspend fun joinGroup(groupId: String): Result<Unit> = Result.Success(Unit)
    override suspend fun leaveGroup(groupId: String, reason: String?): Result<Unit> = leaveGroupAction(groupId, reason)
    override suspend fun editGroup(groupId: String, name: String, about: String?, isPrivate: Boolean, isClosed: Boolean, picture: String?): Result<Unit> = Result.Success(Unit)
    override suspend fun deleteGroup(groupId: String): Result<Unit> = Result.Success(Unit)
    override fun isGroupJoined(groupId: String): Boolean = joinedGroups.value.contains(groupId)
    override suspend fun requestGroupMessages(groupId: String, channel: String?) {}
    override suspend fun requestGroupMembers(groupId: String) {}
    override suspend fun requestGroupAdmins(groupId: String) {}
    override suspend fun refreshGroupMetadata(groupId: String) {}
    override suspend fun loadMoreMessages(groupId: String, channel: String?): Boolean = false
    override suspend fun sendMessage(groupId: String, content: String, channel: String?, mentions: Map<String, String>, replyToMessageId: String?): Result<Unit> =
        sendMessageAction(groupId, content, channel, mentions, replyToMessageId)

    override suspend fun deleteMessage(groupId: String, messageId: String): Result<Unit> = Result.Success(Unit)

    override fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> =
        messages.value[groupId] ?: emptyList()

    override fun markGroupAsRead(groupId: String) {}
    override fun getUnreadCount(groupId: String): Int = unreadCounts.value[groupId] ?: 0
    override fun updateUnreadCount(groupId: String, messages: List<NostrGroupClient.NostrMessage>) {}
    override fun getLastReadTimestamp(groupId: String): Long? = null

    override suspend fun requestUserMetadata(pubkeys: Set<String>) {}
    override suspend fun updateProfileMetadata(displayName: String?, name: String?, about: String?, picture: String?, nip05: String?): Result<Unit> =
        updateProfileMetadataAction(displayName, name, about, picture, nip05)

    override suspend fun requestEventById(eventId: String, relayHints: List<String>, author: String?) {}
    override suspend fun requestAddressableEvent(kind: Int, pubkey: String, identifier: String, relays: List<String>) {}
    override suspend fun requestQuotedEvent(eventId: String) {}
    override suspend fun requestRelayLists(pubkeys: Set<String>) {}
    override fun getRelayListForPubkey(pubkey: String): List<Nip65Relay>? = null
    override fun selectOutboxRelays(authors: List<String>, taggedPubkeys: List<String>, explicitRelays: List<String>): List<String> = emptyList()

    override suspend fun addRelay(url: String) {}
    override fun dismissDeepLinkRelay() {}
    override fun onForeground() {}
    override fun onBackground() {}
    override fun onDestroy() {}
    override fun setActiveGroup(groupId: String?) {}
    override suspend fun sendReaction(groupId: String, targetEventId: String, targetPubkey: String, emoji: String): Result<Unit> = Result.Success(Unit)
    override suspend fun publishRelayList(relays: List<Nip65Relay>): Result<Unit> = Result.Success(Unit)
    override val isDiscoveringRelays: StateFlow<Boolean> = MutableStateFlow(false)
    override val pendingDeepLinkRelay: StateFlow<String?> = MutableStateFlow(null)
    override val loadingRelays: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override val kind10009Relays: StateFlow<Set<String>> = MutableStateFlow(emptySet())
}
