package org.nostr.nostrord.network

import kotlinx.coroutines.flow.StateFlow
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.ZapManager
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
    val bunkerState: StateFlow<BunkerState>
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

    /** Relays (in LAZY mode) whose full group list has been fetched this session. */
    val fullGroupListFetchedRelays: StateFlow<Set<String>>

    /** Relays that returned CLOSED "restricted" — access permanently denied. */
    val restrictedRelays: StateFlow<Map<String, String>>
    val isLoadingMore: StateFlow<Map<String, Boolean>>
    val hasMoreMessages: StateFlow<Map<String, Boolean>>

    /**
     * Per-group GroupLoadingState. Lets the UI tell apart "Idle / InitialLoading
     * (haven't gotten EOSE yet)" from "Exhausted (relay confirmed empty)", which
     * the boolean [hasMoreMessages] conflates — both are `hasMore = false`. The
     * web's "No messages yet" empty state needs the distinction so it doesn't
     * flash before the relay has actually spoken (issue: empty state showing on
     * group open before any kind:9 has streamed).
     */
    val groupStates: StateFlow<Map<String, org.nostr.nostrord.network.managers.GroupLoadingState>>

    /**
     * Force-reset the loading state of [groupId] to Idle. Used to recover from
     * controllers stuck in InitialLoading because their underlying socket died
     * (account swap, connection reset) but the natural onConnectionLost path
     * didn't fire (e.g. explicit primaryClient.disconnect() during a reconnect()
     * doesn't always trigger the WebSocket close callback in time).
     */
    suspend fun resetGroupLoadingState(groupId: String)
    val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>>

    /** NIP-57 zap totals keyed by zapped event id. */
    val zaps: StateFlow<Map<String, ZapManager.ZapInfo>>
    val groupMembers: StateFlow<Map<String, List<String>>>
    val groupAdmins: StateFlow<Map<String, List<String>>>
    val groupRoles: StateFlow<Map<String, List<RoleDefinition>>>
    val loadingMembers: StateFlow<Set<String>>

    /** Groups whose subscriptions were CLOSED with "restricted" — private group, non-member. */
    val restrictedGroups: StateFlow<Map<String, String>>

    // --- Metadata state ---
    val userMetadata: StateFlow<Map<String, UserMetadata>>
    val cachedEvents: StateFlow<Map<String, CachedEvent>>
    val unreadCounts: StateFlow<Map<String, Int>>

    /**
     * High-water mark per group: `created_at` (seconds) of the newest message
     * the client has processed. Persisted across sessions via `UnreadManager`.
     * Used by the sidebar to surface groups with recent activity.
     */
    val latestMessageTimestamps: StateFlow<Map<String, Long>>
    val totalUnread: StateFlow<Int>
    val unreadByRelay: StateFlow<Map<String, Int>>
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

    suspend fun loginSuspend(
        privKey: String,
        pubKey: String,
        isNewIdentity: Boolean = false,
    ): Result<Unit>

    suspend fun loginWithNip07(pubkey: String): Result<Unit>

    suspend fun loginWithBunker(bunkerUrl: String): Result<String>

    /** Default relays seeding the nostrconnect:// QR login (user-overridable). */
    val defaultNostrConnectRelays: List<String>

    suspend fun createNostrConnectSession(relays: List<String> = defaultNostrConnectRelays): Pair<String, Nip46Client>

    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol"),
    ): String

    suspend fun logout()

    /**
     * After AccountManager.switchAccount has swapped credentials and reset
     * in-memory caches, re-hydrate joined-group state from per-account storage
     * for the new pubkey and re-issue subscriptions on the active relay.
     */
    suspend fun reloadForActiveAccount()

    // --- Connection operations ---
    suspend fun connect()

    suspend fun reconnect(): Boolean

    /** Fire-and-forget reconnect — safe to call from non-suspend contexts. */
    fun triggerReconnect()

    suspend fun switchRelay(newRelayUrl: String)

    suspend fun removeRelay(url: String)

    suspend fun disconnect()

    // --- Per-relay fetch mode ---

    /** Set whether a relay uses lazy fetch mode (only joined-group metadata on connect). */
    fun setGroupFetchLazy(
        relayUrl: String,
        lazy: Boolean,
    )

    fun isGroupFetchLazy(relayUrl: String): Boolean

    /** Fetch the full group list for a relay — used when the user expands OTHER GROUPS on a lazy relay. */
    suspend fun requestFullGroupListForRelay(relayUrl: String)

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
    suspend fun createGroup(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        picture: String? = null,
        customGroupId: String? = null,
    ): Result<String>

    /**
     * Create a group and immediately declare [parentGroupId] as its parent
     * (chained kind:9007 + kind:9002).
     */
    suspend fun createSubgroup(
        parentGroupId: String,
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        picture: String? = null,
        customGroupId: String? = null,
    ): Result<String>

    suspend fun joinGroup(
        groupId: String,
        inviteCode: String? = null,
    ): Result<Unit>

    suspend fun leaveGroup(
        groupId: String,
        reason: String? = null,
    ): Result<Unit>

    /**
     * Locally remove a joined group that no longer has a `kind:39000` on the
     * relay (deleted while offline, or never existed anymore). Does NOT send
     * `kind:9022` — the group is gone, so there's no relay-side state to leave.
     * Republishes `kind:10009` so other devices drop the stale pin too.
     */
    suspend fun forgetGroup(
        groupId: String,
        relayUrl: String,
    ): Result<Unit>

    /** Joined groups on a relay that have no corresponding `kind:39000` metadata. */
    val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>>

    /**
     * Edit a group in one kind:9002 event. [parentOp] and [childrenEdit]
     * are optional — omit them to leave those fields unchanged
     * (NIP-29 partial-update semantics).
     */
    suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean,
        picture: String? = null,
        parentOp: GroupManager.ParentOp? = null,
        childrenEdit: GroupManager.ChildrenEdit? = null,
    ): Result<Unit>

    suspend fun deleteGroup(groupId: String): Result<Unit>

    /**
     * Publish a kind:9002 to re-parent a group or promote it to root.
     * See [GroupManager.updateGroupTopology].
     */
    suspend fun updateGroupTopology(
        groupId: String,
        parent: GroupManager.ParentOp?,
    ): Result<Unit>

    /**
     * Publish a kind:9002 that declares the parent's accepted children
     * (`["child", id, order?, flags?]`) and, optionally, `["closed-children"]`.
     * Empty [children] + `closedChildren=true` signals "accepts no children",
     * per NIP-29 "Parent consent".
     */
    suspend fun updateChildren(
        groupId: String,
        children: List<DeclaredChild>,
        closedChildren: Boolean,
    ): Result<Unit>

    fun isGroupJoined(groupId: String): Boolean

    suspend fun requestGroupMessages(
        groupId: String,
        channel: String? = null,
    )

    suspend fun requestGroupMembers(groupId: String)

    suspend fun requestGroupAdmins(groupId: String)

    /**
     * Request pending join requests (kind 9021 + 9022) for a group. Admin-only;
     * supplements the standard chat REQ, which buries old 9021s under recent chat.
     */
    suspend fun requestPendingJoinRequests(groupId: String)

    /**
     * Fire-and-forget NIP-11 fetch for [relayUrl]. Used by the AddRelay modal
     * to populate icons + names for relays the user hasn't connected to yet.
     * Idempotent: succeeded/in-flight URLs are skipped inside the manager.
     */
    fun fetchRelayMetadata(relayUrl: String)

    suspend fun refreshGroupMetadata(groupId: String)

    /** Observable parent→children map derived from `parent` tags in kind:39000. */
    val childrenByParent: StateFlow<Map<String, Set<String>>>

    /**
     * Subgroups whose declared parent neither lists them via `["child", ...]` nor
     * attests via an admin pubkey currently in the parent's `kind:39001`. Per NIP-29
     * these MAY be rendered but SHOULD be flagged (⚠) so users know the relationship
     * is one-sided. Invalid claims (closed-children rejection) are already hoisted
     * to root and do not appear here.
     */
    val unverifiedChildren: StateFlow<Set<String>>

    /** Connect to a relay in the background and fetch kind 39000 metadata for a group preview. */
    suspend fun fetchGroupPreview(
        groupId: String,
        relayUrl: String,
    )

    suspend fun loadMoreMessages(
        groupId: String,
        channel: String? = null,
    ): Boolean

    suspend fun fetchGroupMessageById(
        groupId: String,
        messageId: String,
    )

    suspend fun sendMessage(
        groupId: String,
        content: String,
        channel: String? = null,
        mentions: Map<String, String> = emptyMap(),
        replyToMessageId: String? = null,
        extraTags: List<List<String>> = emptyList(),
    ): Result<Unit>

    suspend fun addUser(
        groupId: String,
        targetPubkey: String,
        roles: List<String> = emptyList(),
    ): Result<Unit>

    suspend fun removeUser(
        groupId: String,
        targetPubkey: String,
    ): Result<Unit>

    suspend fun rejectJoinRequest(
        groupId: String,
        joinRequestEventId: String,
    ): Result<Unit>

    suspend fun createInviteCode(groupId: String): Result<String>

    suspend fun revokeInviteCode(
        groupId: String,
        eventId: String,
    ): Result<Unit>

    suspend fun deleteMessage(
        groupId: String,
        messageId: String,
    ): Result<Unit>

    suspend fun sendReaction(
        groupId: String,
        targetEventId: String,
        targetPubkey: String,
        emoji: String,
    ): Result<Unit>

    /**
     * Resolve [recipientPubkey]'s LNURL-pay endpoint and fetch a bolt11 invoice for a
     * NIP-57 zap of [amountSats]. The returned invoice must be paid with an external
     * wallet. [eventId] is the zapped message, or null for a profile zap.
     */
    suspend fun requestZapInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String,
        eventId: String?,
    ): Result<ZapManager.ZapInvoice>

    /**
     * Suspend until a zap receipt settling [bolt11] is observed (returns true), or a timeout
     * elapses (returns false). Polls the relays named in the zap request while waiting.
     */
    suspend fun watchZapPayment(
        bolt11: String,
        recipientPubkey: String,
        eventId: String?,
    ): Boolean

    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage>

    fun markGroupAsRead(groupId: String)

    /** Advance the last-read timestamp for partial-read tracking. See UnreadManager.markAsReadUpTo. */
    fun markGroupAsReadUpTo(groupId: String, timestamp: Long)

    fun getUnreadCount(groupId: String): Int

    fun getLastReadTimestamp(groupId: String): Long?

    // --- Metadata operations ---
    suspend fun requestUserMetadata(pubkeys: Set<String>)

    suspend fun updateProfileMetadata(
        displayName: String? = null,
        name: String? = null,
        about: String? = null,
        picture: String? = null,
        banner: String? = null,
        nip05: String? = null,
        lud16: String? = null,
        website: String? = null,
    ): Result<Unit>

    suspend fun publishRelayList(relays: List<Nip65Relay>): Result<Unit>

    // --- Event operations ---
    suspend fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
    )

    suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relays: List<String> = emptyList(),
    )

    suspend fun requestQuotedEvent(eventId: String)

    suspend fun requestRelayLists(pubkeys: Set<String>)

    fun getRelayListForPubkey(pubkey: String): List<Nip65Relay>?

    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList(),
    ): List<String>
}
