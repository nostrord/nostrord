package org.nostr.nostrord.network

import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.Result

/**
 * Public contract for NostrRepository.
 * Allows ViewModels to be tested with a fake implementation.
 */
interface NostrRepositoryApi {

    // --- Auth state ---
    val isInitialized: StateFlow<Boolean>
    val isLoggedIn: StateFlow<Boolean>
    val isBunkerVerifying: StateFlow<Boolean>
    val isBunkerConnected: StateFlow<Boolean>
    val authUrl: StateFlow<String?>

    // --- Connection state ---
    val currentRelayUrl: StateFlow<String>
    val connectionState: StateFlow<ConnectionManager.ConnectionState>
    val isDiscoveringRelays: StateFlow<Boolean>
    /** Non-null when a deep link opened a relay not in the user's saved list. */
    val pendingDeepLinkRelay: StateFlow<String?>

    // --- Group state ---
    val groups: StateFlow<List<GroupMetadata>>
    val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>>
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>
    val joinedGroups: StateFlow<Set<String>>
    val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>>
    val loadingRelays: StateFlow<Set<String>>
    /** Relays that returned CLOSED "restricted" — access permanently denied. */
    val restrictedRelays: StateFlow<Map<String, String>>
    val isLoadingMore: StateFlow<Map<String, Boolean>>
    val hasMoreMessages: StateFlow<Map<String, Boolean>>
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>
    val groupMembers: StateFlow<Map<String, List<String>>>
    val groupAdmins: StateFlow<Map<String, List<String>>>
    val loadingMembers: StateFlow<Set<String>>

    // --- Metadata state ---
    val userMetadata: StateFlow<Map<String, UserMetadata>>
    val cachedEvents: StateFlow<Map<String, CachedEvent>>
    val unreadCounts: StateFlow<Map<String, Int>>
    val userRelayList: StateFlow<List<Nip65Relay>>
    val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>>
    /** Relay URLs present as explicit "r" tags in the user's kind:10009 event. */
    val kind10009Relays: StateFlow<Set<String>>
    /** Relay URLs from "group" tags that have no "r" tag — implicit, never persisted. */
    val groupTagRelays: StateFlow<Set<String>>

    // --- Initialization ---
    fun forceInitialized()
    suspend fun initialize()

    // --- Auth operations ---
    fun clearAuthUrl()
    fun getPublicKey(): String?
    fun getPrivateKey(): String?
    fun isUsingBunker(): Boolean
    fun isBunkerReady(): Boolean
    suspend fun ensureBunkerConnected(): Boolean
    fun forgetBunkerConnection()
    suspend fun loginSuspend(privKey: String, pubKey: String): Result<Unit>
    suspend fun loginWithNip07(pubkey: String): Result<Unit>
    suspend fun loginWithBunker(bunkerUrl: String): Result<String>
    suspend fun createNostrConnectSession(relays: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol")): Pair<String, Nip46Client>
    suspend fun completeNostrConnectLogin(client: Nip46Client, relays: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol")): String
    suspend fun logout()

    // --- Connection operations ---
    suspend fun connect()
    suspend fun reconnect(): Boolean
    suspend fun switchRelay(newRelayUrl: String)
    suspend fun removeRelay(url: String)
    suspend fun disconnect()
    /** Add a relay to the user's saved list and publish kind:10009. */
    suspend fun addRelay(url: String)
    /** Dismiss the deep link relay prompt without saving. */
    fun dismissDeepLinkRelay()

    // --- Lifecycle ---
    /** Called when the app returns to the foreground. Re-establishes connections and refreshes subscriptions. */
    fun onForeground()
    /** Called when the app moves to the background. Persists live cursors to storage. */
    fun onBackground()
    /** Called when the app process is about to be destroyed. Persists all state and disconnects. */
    fun onDestroy()

    /**
     * Notify the repository which group the user is currently viewing.
     * The relay that hosts [groupId] is promoted to [RelayReconnectScheduler.Priority.ACTIVE]
     * so reconnect attempts for it use faster backoff. Pass null when leaving the group screen.
     */
    fun setActiveGroup(groupId: String?)

    // --- Group operations ---
    suspend fun createGroup(name: String, about: String?, relayUrl: String, isPrivate: Boolean, isClosed: Boolean, picture: String? = null, customGroupId: String? = null): Result<String>
    suspend fun joinGroup(groupId: String): Result<Unit>
    suspend fun leaveGroup(groupId: String, reason: String? = null): Result<Unit>
    suspend fun editGroup(groupId: String, name: String, about: String?, isPrivate: Boolean, isClosed: Boolean, picture: String? = null): Result<Unit>
    suspend fun deleteGroup(groupId: String): Result<Unit>
    fun isGroupJoined(groupId: String): Boolean
    suspend fun requestGroupMessages(groupId: String, channel: String? = null)
    suspend fun requestGroupMembers(groupId: String)
    suspend fun requestGroupAdmins(groupId: String)
    suspend fun refreshGroupMetadata(groupId: String)
    /** Connect to a relay in the background and fetch kind 39000 metadata for a group preview. */
    suspend fun fetchGroupPreview(groupId: String, relayUrl: String)
    suspend fun loadMoreMessages(groupId: String, channel: String? = null): Boolean
    suspend fun sendMessage(groupId: String, content: String, channel: String? = null, mentions: Map<String, String> = emptyMap(), replyToMessageId: String? = null, extraTags: List<List<String>> = emptyList()): Result<Unit>
    suspend fun deleteMessage(groupId: String, messageId: String): Result<Unit>
    suspend fun sendReaction(groupId: String, targetEventId: String, targetPubkey: String, emoji: String): Result<Unit>
    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage>
    fun markGroupAsRead(groupId: String)
    fun getUnreadCount(groupId: String): Int
    fun updateUnreadCount(groupId: String, messages: List<NostrGroupClient.NostrMessage>)
    fun getLastReadTimestamp(groupId: String): Long?

    // --- Metadata operations ---
    suspend fun requestUserMetadata(pubkeys: Set<String>)
    suspend fun updateProfileMetadata(displayName: String? = null, name: String? = null, about: String? = null, picture: String? = null, banner: String? = null, nip05: String? = null, lud16: String? = null, website: String? = null): Result<Unit>
    suspend fun publishRelayList(relays: List<Nip65Relay>): Result<Unit>

    // --- Event operations ---
    suspend fun requestEventById(eventId: String, relayHints: List<String> = emptyList(), author: String? = null)
    suspend fun requestAddressableEvent(kind: Int, pubkey: String, identifier: String, relays: List<String> = emptyList())
    suspend fun requestQuotedEvent(eventId: String)
    suspend fun requestRelayLists(pubkeys: Set<String>)
    fun getRelayListForPubkey(pubkey: String): List<Nip65Relay>?
    fun selectOutboxRelays(authors: List<String> = emptyList(), taggedPubkeys: List<String> = emptyList(), explicitRelays: List<String> = emptyList()): List<String>
}
