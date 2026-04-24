package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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
import org.nostr.nostrord.network.groupMetadataListSerializer
import org.nostr.nostrord.network.GroupAdmins
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.DeclaredChild
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.GroupRoles
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.isFullGroupListCacheFresh
import org.nostr.nostrord.storage.isGroupListCacheFresh
import org.nostr.nostrord.storage.saveFullGroupListEoseTimestamp
import org.nostr.nostrord.storage.saveGroupListEoseTimestamp
import org.nostr.nostrord.storage.getGroupListEoseTimestamp
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl

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
    private val liveCursorStore: LiveCursorStore = LiveCursorStore(),
    private val connStats: ConnectionStats? = null,
    private val muxTracker: MuxSubscriptionTracker = MuxSubscriptionTracker(),
    private val adaptiveConfig: AdaptiveConfig? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventDeduplicator = EventDeduplicator()

    /**
     * Collects incoming messages per group, then applies them to [_messages]
     * in a single sorted batch — reducing N StateFlow emissions to 1 during burst loads.
     * Window is dynamic: reads from [AdaptiveConfig.bufferWindowMs] at each enqueue.
     */
    private val eventOrderingBuffer = EventOrderingBuffer(
        scope = scope,
        windowProvider = { adaptiveConfig?.bufferWindowMs ?: EventOrderingBuffer.WINDOW_MS },
    ) { groupId, messages ->
        flushBatchToState(groupId, messages)
    }

    // Persistent per-group message ID index — avoids rebuilding a HashSet on every flush.
    // Updated incrementally in flushBatchToState and loadMessagesFromStorage.
    // Cleared on relay switch / logout.
    private val messageIdIndex = mutableMapOf<String, MutableSet<String>>()

    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()

    // Per-relay group cache — persists across relay switches.
    private val _groupsByRelay = MutableStateFlow<Map<String, List<GroupMetadata>>>(emptyMap())
    val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = _groupsByRelay.asStateFlow()

    // Tracks which relay is currently active and which relays have fully loaded
    // (i.e. received EOSE for the "group-list" subscription).
    private val _currentRelayUrl = MutableStateFlow<String?>(null)
    private var currentRelayUrl: String?
        get() = _currentRelayUrl.value
        set(value) { _currentRelayUrl.value = value }
    private val _completeGroupLoadRelays = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Joined groups on a relay that have no corresponding `kind:39000` after the
     * relay finished serving its group list (EOSE received). These are stale pins
     * from `kind:10009` — the group was deleted while offline, or the relay
     * dropped it. UI surfaces them so the user can explicitly forget them.
     */
    private val _orphanedJoinedByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>> = _orphanedJoinedByRelay.asStateFlow()

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(
                _groupsByRelay,
                _joinedGroupsByRelay,
                _completeGroupLoadRelays
            ) { groupsMap, joinedMap, doneRelays ->
                doneRelays.associateWith { relay ->
                    val known = groupsMap[relay].orEmpty().map { it.id }.toSet()
                    val joined = joinedMap[relay].orEmpty()
                    joined - known
                }.filterValues { it.isNotEmpty() }
            }.collect { _orphanedJoinedByRelay.value = it }
        }
    }

    // The group currently being viewed by the user.
    // Mux chat/reactions subscriptions are scoped to this group only.
    private var _activeGroupId: String? = null
    val activeGroupId: String? get() = _activeGroupId

    // Groups that have been opened (clicked) by the user during this session.
    // Chat/reactions mux covers these groups so they keep receiving live messages.
    // Only cleared on full disconnect/logout, NOT on relay switch.
    // Backed by StateFlow for thread-safe reads/writes from any dispatcher.
    private val _openedGroupIds = MutableStateFlow<Set<String>>(emptySet())

    // NIP-29 subgroups — topology derived from `parent` tags in kind:39000.
    // Tree is built client-side: groups without a parent tag are roots, the
    // rest are grouped under the `d` referenced by their `parent` tag.
    // `childrenByParent` contains Confirmed AND Unverified relationships
    // (the latter are also listed in `unverifiedChildren` so the UI can flag
    // them visually per NIP-29 §"Parent consent"). Invalid claims
    // (closed-children rejection) are hoisted back to the root.
    private val _childrenByParent = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val childrenByParent: StateFlow<Map<String, Set<String>>> = _childrenByParent.asStateFlow()

    // Child ids whose declared parent does NOT list them back and whose parent
    // tag carries no attestation confirming the link. Per NIP-29 these MAY be
    // rendered but SHOULD be flagged visually (⚠ badge / tooltip).
    private val _unverifiedChildren = MutableStateFlow<Set<String>>(emptySet())
    val unverifiedChildren: StateFlow<Set<String>> = _unverifiedChildren.asStateFlow()

    /**
     * Groups the user locally deleted. We ignore incoming kind:39000 for these ids so a
     * re-emitted metadata event (relay replaying, or a relay that doesn't actually honor
     * 9008) doesn't resurrect the group into "Other Groups".
     */
    private val deletedGroupIds = mutableSetOf<String>()

    // Debounce mux refresh: coalesces rapid calls (auth + EOSE + CLOSED) into one.
    private val muxRefreshJobs = mutableMapOf<String, Job>()

    // Request cooldown: prevents duplicate in-flight requests within a short window.
    // Key = "type:groupId", value = epochMillis when last requested.
    private val recentRequests = mutableMapOf<String, Long>()

    /**
     * Returns true if this request type for this group hasn't been issued recently.
     * Prevents duplicate REQs when multiple triggers fire within [cooldownMs].
     */
    private fun shouldRequest(groupId: String, type: String, cooldownMs: Long? = null): Boolean {
        val key = "$type:$groupId"
        val now = epochMillis()
        val effectiveCooldown = cooldownMs
            ?: adaptiveConfig?.requestCooldownMs
            ?: REQUEST_COOLDOWN_MS
        val last = recentRequests[key] ?: 0L
        if (now - last < effectiveCooldown) {
            connStats?.onRequestAvoided(getRelayForGroup(groupId) ?: "unknown")
            return false
        }
        recentRequests[key] = now
        return true
    }

    // Relays for which requestGroups() was sent but EOSE hasn't arrived yet.
    // Used by the UI to show skeleton loaders while groups are being fetched.
    private val _loadingRelays = MutableStateFlow<Set<String>>(emptySet())
    val loadingRelays: StateFlow<Set<String>> = _loadingRelays.asStateFlow()

    // Relays for which requestGroups() (unfiltered, no #d tag) was sent this session but
    // EOSE hasn't arrived yet. Lets handleEoseSuspend() distinguish a full fetch from a
    // lazy (joined-only) fetch when the same sub ID is reused.
    private val pendingFullFetchRelays = mutableSetOf<String>()

    fun markPendingFullFetch(relayUrl: String) {
        pendingFullFetchRelays.add(relayUrl.normalizeRelayUrl())
    }

    /**
     * Remove [relayUrl] from [pendingFullFetchRelays] without marking the full list as fetched.
     * Call this when a partial (joined-only) REQ is sent after a full REQ was already marked
     * pending, to prevent the partial EOSE from incorrectly setting fullGroupListFetchedRelays.
     */
    fun cancelPendingFullFetch(relayUrl: String) {
        pendingFullFetchRelays.remove(relayUrl.normalizeRelayUrl())
    }

    /**
     * Returns true if [relayUrl] has a pending full-fetch REQ that hasn't received EOSE yet.
     * Used by [NostrRepository.requestFullGroupListForRelay] to skip a duplicate REQ when
     * [requestGroupsForRelay] already scheduled a full fetch on connect.
     */
    fun hasPendingFullFetch(relayUrl: String): Boolean {
        return relayUrl.normalizeRelayUrl() in pendingFullFetchRelays
    }

    // Relays whose FULL group list (unfiltered requestGroups()) EOSE was received this session.
    // Updated by handleEoseSuspend when the relay is in pendingFullFetchRelays.
    private val _fullGroupListFetchedRelays = MutableStateFlow<Set<String>>(emptySet())
    val fullGroupListFetchedRelays: StateFlow<Set<String>> = _fullGroupListFetchedRelays.asStateFlow()

    fun hasFullGroupListBeenFetched(relayUrl: String): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        if (normalized in _fullGroupListFetchedRelays.value) return true
        return SecureStorage.isFullGroupListCacheFresh(normalized, epochSeconds())
    }

    fun markRelayLoading(relayUrl: String) {
        _loadingRelays.update { it + relayUrl.normalizeRelayUrl() }
    }

    fun markRelayLoaded(relayUrl: String) {
        _loadingRelays.update { it - relayUrl.normalizeRelayUrl() }
    }

    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()

    // Per-relay joined groups cache — the single source of truth for membership.
    private val _joinedGroupsByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = _joinedGroupsByRelay.asStateFlow()

    // Active-relay view — derived so it can never drift from _joinedGroupsByRelay.
    val joinedGroups: StateFlow<Set<String>> = combine(
        _joinedGroupsByRelay,
        _currentRelayUrl
    ) { map, relay ->
        relay?.normalizeRelayUrl()?.let { map[it] } ?: emptySet()
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Synchronous active-relay view for internal use (avoids stateIn dispatch lag).
    private val activeJoinedGroups: Set<String>
        get() = currentRelayUrl?.normalizeRelayUrl()?.let { _joinedGroupsByRelay.value[it] } ?: emptySet()

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

    // Global emoji shortcode → URL cache, populated from every emoji tag we see.
    // Used as fallback when a kind-7 reaction event omits the emoji tag.
    // LRU-bounded to 2000 entries to prevent unbounded memory growth.
    // Uses KMP-compatible LruCache (not java.util.LinkedHashMap).
    private val emojiUrlCache = org.nostr.nostrord.utils.LruCache<String, String>(2000)

    // Reactions: messageId -> (emoji -> ReactionInfo)
    private val _reactions = MutableStateFlow<Map<String, Map<String, ReactionInfo>>>(emptyMap())
    val reactions: StateFlow<Map<String, Map<String, ReactionInfo>>> = _reactions.asStateFlow()

    // Reaction debounce: coalesces rapid reaction arrivals into a single StateFlow emission.
    // During burst loads (initial EOSE with many reactions), this reduces N emissions to ~1.
    // Pending changes accumulate in _pendingReactions and are flushed to _reactions after
    // REACTION_DEBOUNCE_MS of inactivity.
    private val _pendingReactions = mutableMapOf<String, Map<String, ReactionInfo>>()
    private var reactionFlushJob: Job? = null

    /** Flush all pending reaction changes to the StateFlow immediately. */
    private fun flushPendingReactions() {
        if (_pendingReactions.isEmpty()) return
        val pending = _pendingReactions.toMap()
        _pendingReactions.clear()
        _reactions.value = _reactions.value + pending
    }

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

    // Group roles from kind 39003: groupId -> list of role definitions
    private val _groupRoles = MutableStateFlow<Map<String, List<RoleDefinition>>>(emptyMap())
    val groupRoles: StateFlow<Map<String, List<RoleDefinition>>> = _groupRoles.asStateFlow()

    // Timestamp guards: reject stale kind:39001/39002 events from slower relays.
    private val memberEventTimestamps = mutableMapOf<String, Long>()
    private val adminEventTimestamps = mutableMapOf<String, Long>()
    private val roleEventTimestamps = mutableMapOf<String, Long>()

    // Groups whose subscriptions were closed with "restricted" by the relay.
    // For private groups, the relay denies access to non-members (including metadata).
    // The UI uses this to show a "Private Group" placeholder with invite code input.
    private val _restrictedGroups = MutableStateFlow<Map<String, String>>(emptyMap())
    val restrictedGroups: StateFlow<Map<String, String>> = _restrictedGroups.asStateFlow()

    /**
     * Mark a group as restricted (relay denied access).
     * Called when a CLOSED "restricted" message arrives for a group subscription.
     */
    fun markGroupRestricted(groupId: String, reason: String) {
        _restrictedGroups.update { it + (groupId to reason) }
    }

    /**
     * Clear restricted status for a group (e.g. after successful join).
     */
    fun clearGroupRestricted(groupId: String) {
        _restrictedGroups.update { it - groupId }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val LOADING_TIMEOUT_MS = 10_000L // 10 seconds timeout for loading
        const val MAX_PERSISTED_MESSAGES = 100 // Limit messages per group for storage
        const val MEMBER_LOAD_TIMEOUT_MS = 8_000L // Safety timeout for member loading state
        const val REQUEST_COOLDOWN_MS = 2_000L // Prevents duplicate REQs within this window
        const val REACTION_DEBOUNCE_MS = 50L // Coalesces burst reaction arrivals
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
            // Fallback: private groups may not appear in kind 39000 listing but are
            // tracked in _joinedGroupsByRelay (from kind 10009). Without this,
            // clientForGroup() falls back to the primary client which may be wrong.
            ?: _joinedGroupsByRelay.value.entries.firstOrNull { (_, groupIds) -> groupId in groupIds }?.key

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
        val normalized = relayUrl.normalizeRelayUrl()
        val joined = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val loaded = _messages.value.keys.filter { groupId -> getRelayForGroup(groupId)?.normalizeRelayUrl() == normalized }
        val opened = _openedGroupIds.value.filter { groupId ->
            val relay = getRelayForGroup(groupId)?.normalizeRelayUrl()
            relay == normalized || (relay == null && normalized == currentRelayUrl)
        }
        return (joined + loaded + opened).distinct()
    }

    /**
     * Set the group currently being viewed by the user.
     * If this group hasn't been opened before, triggers on-demand subscriptions
     * (messages, members, admins, metadata). Refreshes the mux so chat/reactions
     * include this group.
     */
    fun setActiveGroupId(groupId: String?) {
        _activeGroupId = groupId
        // Fallback to currentRelayUrl when the group isn't tracked yet (e.g. user
        // navigated to a private group URL without being a member).
        val relayUrl = if (groupId != null) (getRelayForGroup(groupId) ?: currentRelayUrl) else currentRelayUrl

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
                // Quick handshake check — 50ms × 20 = 1s max (down from 500ms × 6 = 3s).
                var client = connectionManager.getClientForRelay(url)
                if (client != null && !client.isConnected()) {
                    repeat(20) {
                        delay(50)
                        if (client!!.isConnected()) return@repeat
                    }
                }
                client = connectionManager.getClientForRelay(url)

                // Fire mux refresh in parallel — covers ongoing delivery for ALL groups.
                val muxJob = scope.launch { refreshMuxSubscriptionsForRelay(url) }

                if (client != null && client.isConnected()) {
                    // Direct requests for the ACTIVE group — fast-lane.
                    // Mux provides breadth; these provide speed for the group the user is looking at.
                    // Duplicates are handled by the event deduplicator.
                    client.requestGroupMetadata(groupId!!) // Metadata (essential for private groups)
                    requestGroupMessages(groupId)  // Pagination (mux has no limit)
                    requestGroupMembers(groupId)   // Fast member list
                    requestGroupAdmins(groupId)    // Fast admin list
                }

                muxJob.join()
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

        val desired = MuxSubscriptionTracker.MuxState(
            metadataGroupIds = allGroupIds.toSet(),
            chatGroupIds = chatGroupIds.toSet(),
            chatSinceSeconds = chatSince
        )

        if (!muxTracker.needsRefresh(relayUrl, desired)) {
            connStats?.onSubscriptionAvoided(relayUrl)
            return
        }

        try {
            client.sendMuxSubscriptions(allGroupIds, chatGroupIds, chatSince)
            muxTracker.update(relayUrl, desired)
            connStats?.onSubscriptionSent(relayUrl)
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
     * Returns joined group IDs for [relayUrl] that are NOT in the _groupsByRelay cache.
     * These are typically private groups whose kind 39000 was not returned by the
     * relay's general listing (because the relay hides private groups from non-members
     * or the listing arrived before AUTH completed).
     */
    fun getUncachedJoinedGroups(relayUrl: String): List<String> {
        val normalized = relayUrl.normalizeRelayUrl()
        val joined = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val cached = _groupsByRelay.value[normalized]?.map { it.id }?.toSet() ?: emptySet()
        return (joined - cached).toList()
    }

    /**
     * Request metadata, members and admins for joined groups that are missing from
     * the group cache. This is essential for private groups: the relay omits them
     * from the general kind 39000 listing, but returns them on targeted #d requests
     * once the client has authenticated via NIP-42.
     */
    suspend fun requestPrivateGroupData(relayUrl: String) {
        val uncached = getUncachedJoinedGroups(relayUrl)
        if (uncached.isEmpty()) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        for (groupId in uncached) {
            try {
                client.requestGroupMetadata(groupId)
                client.requestGroupMembers(groupId)
                client.requestGroupAdmins(groupId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Request metadata for the active group if it has no metadata in the cache.
     * Covers the case where a user navigates directly to a group URL (e.g. via
     * invite link) before the relay has returned the group in its general listing.
     * The relay will respond to a targeted kind:39000 REQ with #d filter even for
     * groups that are private or not in the general listing.
     */
    suspend fun requestActiveGroupMetadataIfMissing(relayUrl: String) {
        val groupId = _activeGroupId ?: return
        // Already have metadata — nothing to do.
        val hasMeta = _groups.value.any { it.id == groupId }
        if (hasMeta) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        try {
            client.requestGroupMetadata(groupId)
            client.requestGroupMembers(groupId)
            client.requestGroupAdmins(groupId)
        } catch (_: Exception) {}
    }

    /**
     * Load joined groups from storage
     */
    fun loadJoinedGroupsFromStorage(pubKey: String, relayUrl: String) {
        currentPubkey = pubKey
        val groups = SecureStorage.getJoinedGroupsForRelay(pubKey, relayUrl)
        _joinedGroupsByRelay.update { it + (relayUrl.normalizeRelayUrl() to groups) }
    }

    /**
     * Load joined groups for all given relays into the per-relay cache without
     * only touching the per-relay cache.
     */
    fun loadAllJoinedGroupsFromStorage(pubKey: String, relayUrls: List<String>) {
        val updates = relayUrls.associate { url ->
            url.normalizeRelayUrl() to SecureStorage.getJoinedGroupsForRelay(pubKey, url)
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
            val additions = relayUrls.map { it.normalizeRelayUrl() }
                .filter { !current.containsKey(it) }
                .associateWith { emptyList<GroupMetadata>() }
            current + additions
        }
    }

    fun pruneRelaysNotIn(authoritativeRelays: Set<String>) {
        val normalizedAuth = authoritativeRelays.map { it.normalizeRelayUrl() }.toSet()
        _groupsByRelay.update { current ->
            current.filterKeys { it.normalizeRelayUrl() in normalizedAuth }
        }
    }

    fun setJoinedGroups(groups: Set<String>) {
        val url = currentRelayUrl?.normalizeRelayUrl() ?: return
        _joinedGroupsByRelay.update { existing ->
            val current = existing[url] ?: emptySet()
            existing + (url to (current + groups))
        }
    }

    fun updateAllRelayJoinedGroups(relayGroups: Map<String, Set<String>>) {
        if (relayGroups.isEmpty()) return
        _joinedGroupsByRelay.update { it + relayGroups }
    }

    /**
     * Join a group
     */
    suspend fun joinGroup(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit,
        inviteCode: String? = null
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            deletedGroupIds.remove(groupId)
            val tags = mutableListOf(listOf("h", groupId))
            val effectiveCode = inviteCode?.takeIf { it.isNotBlank() }
            if (effectiveCode != null) {
                tags.add(listOf("code", effectiveCode))
            }
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9021,
                tags = tags,
                content = "/join"
            )

            val signedEvent = signEvent(event)

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            currentClient.send(message)

            val relayGroups = _joinedGroupsByRelay.value[groupRelayUrl] ?: emptySet()
            val updated = relayGroups + groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updated)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updated) }

            publishJoinedGroups()

            // Clear restricted status — the user is now (pending) member.
            clearGroupRestricted(groupId)

            // Give the relay a moment to process the join event before requesting data
            kotlinx.coroutines.delay(500)

            // Re-request all group data now that we're a member
            // (private groups block these until membership is confirmed)
            currentClient.requestGroupMetadata(groupId)
            currentClient.requestGroupMembers(groupId)
            currentClient.requestGroupAdmins(groupId)
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

            val relayGroups = _joinedGroupsByRelay.value[currentRelayUrl] ?: emptySet()
            val updatedAfterCreate = relayGroups + confirmedGroupId
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
     * Edit a group's metadata, its place in the hierarchy, and the list of
     * accepted children — all in a single kind:9002 event (admin only).
     *
     * NIP-29 permits partial-update semantics on kind:9002: tags that are
     * present overwrite that field; tags that are omitted leave it unchanged.
     * Batching metadata + parent + children into one event avoids the relay
     * briefly seeing intermediate states and halves round-trips from the
     * modal save flow.
     *
     * - [parentOp]: null leaves the current parent alone. `SetTo(id)` links
     *   this group under `id`; `Detach` emits `["parent"]` to promote to root.
     * - [childrenEdit]: null leaves the child list alone. Otherwise emits the
     *   full list (or the bare `["child"]` clear marker when empty) and the
     *   paired `["open-children"]`/`["closed-children"]` flag.
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
        signEvent: suspend (Event) -> Event,
        parentOp: ParentOp? = null,
        childrenEdit: ChildrenEdit? = null
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val metaTags = mutableListOf(
                listOf("h", groupId),
                listOf("name", name),
                if (isPrivate) listOf("private") else listOf("public"),
                if (isClosed) listOf("closed") else listOf("open")
            )
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
            if (!picture.isNullOrBlank()) metaTags.add(listOf("picture", picture))

            when (parentOp) {
                is ParentOp.SetTo -> metaTags.add(listOf("parent", parentOp.id))
                ParentOp.Detach -> metaTags.add(listOf("parent"))
                null -> Unit
            }

            if (childrenEdit != null) {
                if (childrenEdit.children.isEmpty()) {
                    // Explicit clear marker; zero child tags would mean "unchanged".
                    metaTags.add(listOf("child"))
                } else {
                    childrenEdit.children.forEach { child ->
                        val parts = mutableListOf("child", child.id)
                        val hasFlags = child.flags.isNotEmpty()
                        if (child.order != null || hasFlags) {
                            parts.add(child.order.orEmpty())
                        }
                        if (hasFlags) parts.addAll(child.flags)
                        metaTags.add(parts)
                    }
                }
                metaTags.add(
                    if (childrenEdit.closedChildren) listOf("closed-children")
                    else listOf("open-children")
                )
            }

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

    /** Child-list edit for [editGroup]: the full desired list plus the flag. */
    data class ChildrenEdit(
        val children: List<DeclaredChild>,
        val closedChildren: Boolean
    )

    /**
     * Publish a kind:9002 that re-parents a group or promotes it to root.
     *
     * - [parent]: `ParentOp.SetTo(id)` moves the group under a parent,
     *   `ParentOp.Detach` promotes to root (empty parent tag), null = no change.
     */
    suspend fun updateGroupTopology(
        groupId: String,
        parent: ParentOp?,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val tags = mutableListOf<List<String>>(listOf("h", groupId))
            when (parent) {
                is ParentOp.SetTo -> tags.add(listOf("parent", parent.id))
                ParentOp.Detach -> tags.add(listOf("parent"))
                null -> Unit
            }
            val signed = signEvent(Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9002,
                tags = tags,
                content = ""
            ))
            val eventJson = buildJsonArray {
                add("EVENT"); add(signed.toJsonObject())
            }.toString()
            val eventId = signed.id
                ?: return Result.Error(AppError.Group.CreateFailed(Exception("Event ID not generated")))
            when (val res = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.CreateFailed(Exception(res.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.CreateFailed(Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.CreateFailed(res.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.CreateFailed(e))
        }
    }

    sealed class ParentOp {
        data class SetTo(val id: String) : ParentOp()
        /** Republish metadata without a parent (promotes subgroup back to root). */
        object Detach : ParentOp()
    }

    /**
     * Publish a kind:9002 that sets the parent's bilateral child-acceptance list
     * and the `closed-children` / `open-children` flag (NIP-29 "Parent consent").
     *
     * The event carries the required metadata tags (name, visibility, access) so
     * the relay accepts it, plus one `["child", id, order?, flags?]` per entry and
     * `["closed-children"]` or `["open-children"]` to toggle the flag.
     */
    suspend fun updateChildren(
        groupId: String,
        children: List<DeclaredChild>,
        closedChildren: Boolean,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        // Include current metadata so the relay accepts the kind:9002.
        val meta = _groups.value.find { it.id == groupId }

        return try {
            val tags = mutableListOf<List<String>>(
                listOf("h", groupId),
                listOf("name", meta?.name ?: groupId),
                if (meta?.isPublic != false) listOf("public") else listOf("private"),
                if (meta?.isOpen != false) listOf("open") else listOf("closed")
            )
            meta?.about?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("about", it)) }
            meta?.picture?.takeIf { it.isNotBlank() }?.let { tags.add(listOf("picture", it)) }

            if (children.isEmpty()) {
                // NIP-29: a `kind:9002` with no `child` tags at all leaves the list
                // unchanged; a single `["child"]` with no id is the explicit clear marker.
                tags.add(listOf("child"))
            } else {
                children.forEach { child ->
                    val parts = mutableListOf("child", child.id)
                    val hasFlags = child.flags.isNotEmpty()
                    if (child.order != null || hasFlags) {
                        parts.add(child.order.orEmpty())
                    }
                    if (hasFlags) {
                        parts.addAll(child.flags)
                    }
                    tags.add(parts)
                }
            }
            if (closedChildren) {
                tags.add(listOf("closed-children"))
            } else {
                tags.add(listOf("open-children"))
            }

            val signed = signEvent(Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9002,
                tags = tags,
                content = ""
            ))
            val eventJson = buildJsonArray {
                add("EVENT"); add(signed.toJsonObject())
            }.toString()
            val eventId = signed.id
                ?: return Result.Error(AppError.Group.CreateFailed(Exception("Event ID not generated")))

            when (val res = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(Unit)
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.CreateFailed(Exception(res.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.CreateFailed(Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.CreateFailed(res.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.CreateFailed(e))
        }
    }

    /**
     * Delete a group (admin only). Sends kind:9008 (delete-group).
     * Per NIP-29: when a parent is deleted, its children become roots —
     * the relay re-emits their kind:39000 without the parent tag.
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
                kind = 9008,
                tags = listOf(listOf("h", groupId)),
                content = ""
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.LeaveFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            when (val pub = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    return Result.Error(AppError.Group.LeaveFailed(groupId, Exception(pub.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    return Result.Error(AppError.Group.LeaveFailed(groupId, Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    return Result.Error(AppError.Group.LeaveFailed(groupId, pub.exception))
                is org.nostr.nostrord.network.PublishResult.Success -> Unit
            }

            val idsToRemove = setOf(groupId)
            val normalizedGroupRelay = groupRelayUrl.normalizeRelayUrl()
            val relayGroupsBefore = _joinedGroupsByRelay.value[normalizedGroupRelay] ?: emptySet()
            val updatedAfterLeave = relayGroupsBefore - idsToRemove
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updatedAfterLeave)
            _joinedGroupsByRelay.update { it + (normalizedGroupRelay to updatedAfterLeave) }
            publishJoinedGroups()

            _groups.value = _groups.value.filter { it.id !in idsToRemove }
            _groupsByRelay.update { current ->
                val updated = (current[groupRelayUrl] ?: emptyList()).filter { it.id !in idsToRemove }
                current + (groupRelayUrl to updated)
            }
            persistJoinedGroupMetadataSnapshot(groupRelayUrl)

            _messages.update { it - idsToRemove }
            _isLoadingMore.update { it - idsToRemove }
            _hasMoreMessages.update { it - idsToRemove }
            _groupStates.update { it - idsToRemove }
            _groupAdmins.update { it - idsToRemove }
            _groupMembers.update { it - idsToRemove }
            idsToRemove.forEach { loadingRegistry.remove(it) }
            deletedGroupIds.addAll(idsToRemove)
            recomputeSubgroupTopology()

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.LeaveFailed(groupId, e))
        }
    }

    /**
     * Apply a deletion broadcast (kind:9008) coming from the relay for a group the
     * current user didn't initiate. Idempotent; no-op if the group is already gone.
     *
     * Returns true when local state actually changed so callers (e.g. NostrRepository)
     * can republish the user's joined list for cross-device consistency.
     */
    suspend fun handleRemoteDeleteGroup(groupId: String, relayUrl: String, pubKey: String?): Boolean {
        // Once a deletion has been processed, don't re-process it. The joinGroup()
        // method clears the id from deletedGroupIds, so a future deletion after
        // re-join will still be handled correctly.
        if (groupId in deletedGroupIds) return false

        val idsToRemove = setOf(groupId)
        // Intentionally do NOT remove from _joinedGroupsByRelay / kind:10009 — orphaned groups
        // surface in the sidebar so the user can review and explicitly forget them.
        // Per NIP-29: when a parent is deleted, its children become roots —
        // the relay re-emits their kind:39000 without the parent tag.

        _groups.value = _groups.value.filter { it.id !in idsToRemove }
        _groupsByRelay.update { current ->
            val updated = (current[relayUrl] ?: emptyList()).filter { it.id !in idsToRemove }
            current + (relayUrl to updated)
        }
        // The group is intentionally kept in _joinedGroupsByRelay (shows as orphan in sidebar).
        // Rebuild the snapshot so next startup sees it as an orphan (in joined but not in metadata).
        persistJoinedGroupMetadataSnapshot(relayUrl)

        _messages.update { it - idsToRemove }
        _isLoadingMore.update { it - idsToRemove }
        _hasMoreMessages.update { it - idsToRemove }
        _groupStates.update { it - idsToRemove }
        _groupAdmins.update { it - idsToRemove }
        _groupMembers.update { it - idsToRemove }
        idsToRemove.forEach { loadingRegistry.remove(it) }
        deletedGroupIds.addAll(idsToRemove)
        recomputeSubgroupTopology()
        return false
    }

    /**
     * Explicitly forget an orphaned pin: drop the id from the joined set and
     * persist, so the next publishJoinedGroupsList() writes a kind:10009
     * without it. Used by the sidebar's "forget orphan" trash action.
     */
    fun forgetJoinedPin(groupId: String, relayUrl: String, pubKey: String?): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        val currentPerRelay = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val activeKey = currentRelayUrl?.normalizeRelayUrl()
        val activeGroups = activeKey?.let { _joinedGroupsByRelay.value[it] } ?: emptySet()
        val alsoInActive = activeKey != null && activeKey != normalized && groupId in activeGroups
        if (groupId !in currentPerRelay && !alsoInActive) return false

        val updatedPerRelay = currentPerRelay - groupId
        _joinedGroupsByRelay.update { current ->
            val next = current + (normalized to updatedPerRelay)
            if (alsoInActive) next + (activeKey!! to (activeGroups - groupId)) else next
        }
        if (pubKey != null) {
            try { SecureStorage.saveJoinedGroupsForRelay(pubKey, normalized, updatedPerRelay) } catch (_: Exception) {}
        }
        deletedGroupIds.add(groupId)
        return true
    }

    /**
     * Add a user to a group (admin only). Sends kind:9000 (put-user).
     * Optionally assigns roles (e.g. "admin", "moderator").
     */
    suspend fun addUser(
        groupId: String,
        targetPubkey: String,
        roles: List<String> = emptyList(),
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val pTag = mutableListOf("p", targetPubkey).apply { addAll(roles) }
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9000,
                tags = listOf(listOf("h", groupId), pTag),
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
     * Remove a user from a group (admin only). Sends kind:9001 (remove-user).
     */
    suspend fun removeUser(
        groupId: String,
        targetPubkey: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9001,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("p", targetPubkey)
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
     * Reject a join request (admin only). Sends kind:9005 (delete-event) targeting the 9021 event.
     */
    suspend fun rejectJoinRequest(
        groupId: String,
        joinRequestEventId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9005,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("e", joinRequestEventId)
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
     * Create an invite code for a group (admin only). Sends kind:9009.
     * Returns the generated code on success.
     */
    suspend fun createInviteCode(
        groupId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<String> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val code = generateInviteCode()
            val tags = listOf(
                listOf("h", groupId),
                listOf("code", code)
            )
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9009,
                tags = tags,
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
                is org.nostr.nostrord.network.PublishResult.Success -> Result.Success(code)
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
     * Revoke an invite code (admin only). Sends kind:9005 targeting the 9009 event by ID.
     */
    suspend fun revokeInviteCode(
        groupId: String,
        eventId: String,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9005,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("e", eventId)
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

    private fun generateInviteCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (1..8).map { chars.random() }.joinToString("")
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

            val relayGroups = _joinedGroupsByRelay.value[groupRelayUrl] ?: emptySet()
            val updatedAfterLeave = relayGroups - groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updatedAfterLeave)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updatedAfterLeave) }
            persistJoinedGroupMetadataSnapshot(groupRelayUrl)

            publishJoinedGroups()

            _messages.update { it - groupId }
            _isLoadingMore.update { it - groupId }
            _hasMoreMessages.update { it - groupId }
            _groupStates.update { it - groupId }
            observedGroupsMutex.withLock { observedGroups.remove(groupId) }
            loadingRegistry.remove(groupId)
            // Reset opened tracking so setActiveGroupId() re-fetches on rejoin.
            _openedGroupIds.update { it - groupId }

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.LeaveFailed(groupId, e))
        }
    }

    /**
     * Check if a group is joined
     */
    fun isGroupJoined(groupId: String): Boolean {
        return groupId in activeJoinedGroups
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
                val normalizedRelay = relay.normalizeRelayUrl()
                _completeGroupLoadRelays.update { it + normalizedRelay }
                _loadingRelays.update { it - normalizedRelay }
                val now = epochSeconds()
                val isFull = pendingFullFetchRelays.remove(normalizedRelay)
                if (isFull) {
                    // Full unfiltered fetch — save dedicated full-list timestamp so
                    // hasFullGroupListBeenFetched() returns true after an app restart.
                    _fullGroupListFetchedRelays.update { it + normalizedRelay }
                    try { SecureStorage.saveFullGroupListEoseTimestamp(normalizedRelay, now) } catch (_: Exception) {}
                }
                // Always update the general cache timestamp (used by hasCachedGroupsForRelay).
                try { SecureStorage.saveGroupListEoseTimestamp(normalizedRelay, now) } catch (_: Exception) {}
                refreshMuxSubscriptionsForRelay(normalizedRelay)
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
        muxTracker.clearAll()
        recentRequests.clear()
    }

    /**
     * Handle connection lost for a specific set of groups (e.g. a pool relay dropped).
     * Resets their loading states to Idle so re-subscription can proceed after reconnect.
     * Also clears the mux tracker so reconnect re-sends fresh subscriptions.
     */
    suspend fun handleConnectionLostForGroups(groupIds: List<String>) {
        loadingRegistry.handleDisconnectForGroups(groupIds)
        // Clear subscription tracker for affected relays so reconnect re-sends mux.
        groupIds.mapNotNull { getRelayForGroup(it) }.distinct().forEach {
            muxTracker.clearRelay(it)
        }
    }

    /**
     * Reset loading states WITHOUT clearing the mux tracker.
     * Use this when re-subscribing after auth — the caller is responsible for
     * clearing the tracker separately via [clearMuxTrackerForRelay] if needed.
     */
    suspend fun resetLoadingForGroups(groupIds: List<String>) {
        loadingRegistry.handleDisconnectForGroups(groupIds)
    }

    /**
     * Clear the mux subscription tracker for a specific relay so the next
     * [refreshMuxSubscriptionsForRelay] call always re-sends subscriptions.
     * Use after AUTH challenges where the relay may have dropped active subs.
     */
    fun clearMuxTrackerForRelay(relayUrl: String) {
        muxTracker.clearRelay(relayUrl)
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
        extraTags: List<List<String>> = emptyList(),
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

            // Add extra tags (e.g. NIP-68 imeta tags from media uploads), dedup by content
            extraTags.forEach { tag -> if (tag !in tags) tags.add(tag) }

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
            val tags = buildList {
                add(listOf("h", groupId, groupRelayUrl))
                add(listOf("e", targetEventId, "", "", targetPubkey))
                add(listOf("p", targetPubkey))
                // Include emoji tag for custom emojis (NIP-30) so other clients can resolve the URL
                val shortcode = emoji.trim(':')
                if (emoji.startsWith(":") && emoji.endsWith(":") && shortcode.isNotEmpty()) {
                    emojiUrlCache.get(shortcode)?.let { url ->
                        add(listOf("emoji", shortcode, url))
                    }
                }
            }

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


            // Optimistic update: show reaction in UI immediately (no debounce)
            handleReaction(NostrGroupClient.NostrReaction(
                id = eventId,
                pubkey = pubKey,
                emoji = emoji,
                emojiUrl = null,
                targetEventId = targetEventId,
                createdAt = event.createdAt
            ), immediate = true)

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
    /**
     * Rebuild and persist the joined-group metadata snapshot for [relayUrl].
     * Filters [_groupsByRelay] to only the groups the user has joined on that relay,
     * then writes to the pubkey-scoped storage key used for fast startup restore.
     * No-op when [currentPubkey] is not set (unauthenticated state).
     */
    private fun persistJoinedGroupMetadataSnapshot(relayUrl: String) {
        val pubKey = currentPubkey ?: return
        val normalized = relayUrl.normalizeRelayUrl()
        val joinedIds = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val snapshot = (_groupsByRelay.value[normalized] ?: emptyList()).filter { it.id in joinedIds }
        try {
            SecureStorage.saveJoinedGroupMetadata(pubKey, normalized, json.encodeToString(groupMetadataListSerializer, snapshot))
        } catch (e: Exception) {
            println("[IDB] persistJoinedGroupMetadataSnapshot failed relay=$normalized: ${e.message}")
        }
    }

    fun handleGroupMetadata(metadata: GroupMetadata, relayUrl: String) {
        if (metadata.id in deletedGroupIds) return
        val normalized = relayUrl.normalizeRelayUrl()
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
            val relayGroups = current[normalized] ?: emptyList()
            val isNewForRelay = relayGroups.none { it.id == metadata.id }
            val updated = if (isNewForRelay) {
                relayGroups + metadata
            } else {
                relayGroups.map { if (it.id == metadata.id) metadata else it }
            }
            current + (normalized to updated)
        }
        // Persist only joined-group metadata for fast startup restore.
        // Non-joined groups are fetched on-demand and not cached.
        persistJoinedGroupMetadataSnapshot(relayUrl)

        // Recompute the full tree because a kind:39000 update can add/remove children,
        // toggle `closed-children`, or carry new `child`/`parent` tags that change classification
        // for groups other than `metadata` itself.
        recomputeSubgroupTopology()
    }

    /**
     * Recompute the parent→children map and the unverified set from the current
     * `_groups` list and admin map. Called on every kind:39000 / kind:39001 update
     * since classification depends on both the child's `parent` tag and the
     * parent's `child` list / `closed-children` flag / admin list (attestation).
     *
     * Per NIP-29 classification:
     * - **confirmed**: parent lists child back, OR the child's parent tag carries
     *   an attestation pubkey that appears in the parent's `kind:39001`.
     * - **unverified**: only the child declares and no attestation is present;
     *   rendered but flagged.
     * - **invalid**: parent has `closed-children` and does NOT list child;
     *   claim is ignored — child is hoisted to root.
     */
    private fun recomputeSubgroupTopology() {
        val groups = _groups.value
        val byId = groups.associateBy { it.id }
        val admins = _groupAdmins.value

        val nextChildren = mutableMapOf<String, MutableSet<String>>()
        val nextUnverified = mutableSetOf<String>()

        for (child in groups) {
            val parentId = child.parent ?: continue
            val parent = byId[parentId]
            // Missing parent → treat child as root (nothing to verify, per spec).
            if (parent == null) continue

            val listed = parent.children.any { it.id == child.id }
            val attestation = child.parentAttestation
            val attestationValid = attestation != null && attestation in admins[parent.id].orEmpty()

            when {
                parent.closedChildren && !listed -> {
                    // Invalid — overrides any attestation. Hoist to root by omitting.
                }
                listed || attestationValid -> {
                    nextChildren.getOrPut(parentId) { mutableSetOf() }.add(child.id)
                }
                else -> {
                    // Unverified — nest under the declared parent but tag it so the
                    // UI can grey-it-out / badge it per NIP-29 §"Parent consent".
                    nextChildren.getOrPut(parentId) { mutableSetOf() }.add(child.id)
                    nextUnverified.add(child.id)
                }
            }
        }

        _childrenByParent.value = nextChildren.mapValues { (_, s) -> s.toSet() }
        _unverifiedChildren.value = nextUnverified
    }

    /**
     * Restore the groups list from the per-relay cache.
     * Called on relay switch to show previously loaded groups instantly.
     */
    fun restoreGroupsForRelay(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        currentRelayUrl = normalized
        // Merge cached groups for the new relay into the unified live list.
        // State is additive: previously loaded groups from other relays are kept.
        val cached = _groupsByRelay.value[normalized] ?: emptyList()
        if (cached.isNotEmpty()) {
            _groups.value = (_groups.value + cached).distinctBy { it.id }
            recomputeSubgroupTopology()
        }
    }

    /**
     * Restore only the joined-group metadata snapshot for fast startup display.
     * Reads from the pubkey-scoped cache written by [persistJoinedGroupMetadataSnapshot],
     * which contains only the kind:10009 groups — a small, bounded dataset.
     * Does NOT restore [_fullGroupListFetchedRelays]: a joined-only restore does not
     * constitute a full list fetch, so OTHER GROUPS will trigger a network fetch when opened.
     */
    fun restoreJoinedGroupMetadataFromStorage(pubkey: String, relayUrls: List<String>) {
        val now = epochSeconds()
        _groupsByRelay.update { current ->
            val updates = relayUrls.mapNotNull { url ->
                val normalized = url.normalizeRelayUrl()
                val jsonStr = SecureStorage.getJoinedGroupMetadata(pubkey, normalized) ?: return@mapNotNull null
                try {
                    val groups = json.decodeFromString(groupMetadataListSerializer, jsonStr)
                    if (groups.isNotEmpty()) normalized to groups else null
                } catch (_: Exception) { null }
            }.toMap()
            current + updates
        }
        // Restore general cache freshness flags so hasCachedGroupsForRelay() skips the
        // network fetch when the joined-group snapshot is still within the TTL window.
        val normalizedUrls = relayUrls.map { it.normalizeRelayUrl() }
        val freshRelays = normalizedUrls.filter { normalized ->
            SecureStorage.isGroupListCacheFresh(normalized, now) &&
                _groupsByRelay.value[normalized]?.isNotEmpty() == true
        }.toSet()
        if (freshRelays.isNotEmpty()) {
            _completeGroupLoadRelays.update { it + freshRelays }
        }
        recomputeSubgroupTopology()
    }

    /**
     * Restore group metadata for all known relays from SecureStorage.
     * Called on startup before any WebSocket connects so relay switching is instant.
     */
    fun restoreAllGroupsFromStorage(relayUrls: List<String>) {
        val now = epochSeconds()
        _groupsByRelay.update { current ->
            val updates = relayUrls.mapNotNull { url ->
                val normalized = url.normalizeRelayUrl()
                val jsonStr = SecureStorage.getGroupsForRelay(normalized) ?: return@mapNotNull null
                try {
                    val groups = json.decodeFromString(groupMetadataListSerializer, jsonStr)
                    if (groups.isNotEmpty()) normalized to groups else null
                } catch (_: Exception) { null }
            }.toMap()
            current + updates
        }
        // Restore session flags for relays with fresh enough caches.
        val normalizedUrls = relayUrls.map { it.normalizeRelayUrl() }
        val freshRelays = normalizedUrls
            .filter { normalized ->
                SecureStorage.isGroupListCacheFresh(normalized, now) &&
                    _groupsByRelay.value[normalized]?.isNotEmpty() == true
            }
            .toSet()
        if (freshRelays.isNotEmpty()) {
            _completeGroupLoadRelays.update { it + freshRelays }
        }
        // Restore _fullGroupListFetchedRelays for relays whose full list is still fresh,
        // so hasFullGroupListBeenFetched() returns true and the LaunchedEffect in the sidebar
        // doesn't re-fetch when OTHER GROUPS was already open on the previous session.
        val fullFreshRelays = normalizedUrls
            .filter { SecureStorage.isFullGroupListCacheFresh(it, now) }
            .toSet()
        if (fullFreshRelays.isNotEmpty()) {
            _fullGroupListFetchedRelays.update { it + fullFreshRelays }
        }
        recomputeSubgroupTopology()
    }

    /**
     * Returns true if we have a non-empty cached group list for the given relay.
     */
    fun hasCachedGroupsForRelay(relayUrl: String): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        if (_groupsByRelay.value[normalized]?.isNotEmpty() != true) return false
        // Session flag set (EOSE received this session) — always valid.
        if (normalized in _completeGroupLoadRelays.value) return true
        // No session flag: check persisted timestamp so a fresh app restart can skip requestGroups().
        return SecureStorage.isGroupListCacheFresh(normalized, epochSeconds())
    }

    /**
     * Handle incoming group members (kind 39002)
     * Returns list of member pubkeys that need metadata fetching
     */
    fun handleGroupMembers(members: GroupMembers, createdAt: Long = 0L): List<String> {
        val existing = memberEventTimestamps[members.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            // Stale event from a slower relay — skip state update.
            connStats?.onStateConflict(members.groupId)
            _loadingMembers.value = _loadingMembers.value - members.groupId
            return _groupMembers.value[members.groupId] ?: emptyList()
        }
        if (createdAt > 0L) memberEventTimestamps[members.groupId] = createdAt
        // Skip StateFlow update if the member list is identical — avoids unnecessary recomposition.
        val currentMembers = _groupMembers.value[members.groupId]
        if (currentMembers != members.members) {
            _groupMembers.value = _groupMembers.value + (members.groupId to members.members)
        }
        _loadingMembers.value = _loadingMembers.value - members.groupId
        return members.members
    }

    /**
     * Request group members (kind 39002).
     * Marks the group as loading so the UI can show skeletons.
     * Auto-clears after [MEMBER_LOAD_TIMEOUT_MS] if no response arrives.
     */
    suspend fun requestGroupMembers(groupId: String): Boolean {
        if (!shouldRequest(groupId, "members")) return true // recently requested
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
    fun handleGroupAdmins(admins: GroupAdmins, createdAt: Long = 0L) {
        val existing = adminEventTimestamps[admins.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            connStats?.onStateConflict(admins.groupId)
            return
        }
        if (createdAt > 0L) adminEventTimestamps[admins.groupId] = createdAt
        // Skip StateFlow update if the admin list is identical.
        val currentAdmins = _groupAdmins.value[admins.groupId]
        if (currentAdmins != admins.admins) {
            _groupAdmins.value = _groupAdmins.value + (admins.groupId to admins.admins)
            // An admin removal can flip an attestation-based `confirmed` relation back
            // to `unverified` (spec: evaluated against current kind:39001).
            recomputeSubgroupTopology()
        }
    }

    /**
     * Request group admins (kind 39001)
     */
    suspend fun requestGroupAdmins(groupId: String): Boolean {
        if (!shouldRequest(groupId, "admins")) return true // recently requested
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestGroupAdmins(groupId)
        return true
    }

    /**
     * Handle incoming group roles (kind 39003)
     */
    fun handleGroupRoles(roles: GroupRoles, createdAt: Long = 0L) {
        val existing = roleEventTimestamps[roles.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            connStats?.onStateConflict(roles.groupId)
            return
        }
        if (createdAt > 0L) roleEventTimestamps[roles.groupId] = createdAt
        val currentRoles = _groupRoles.value[roles.groupId]
        if (currentRoles != roles.roles) {
            _groupRoles.value = _groupRoles.value + (roles.groupId to roles.roles)
        }
    }

    /**
     * Request group roles (kind 39003)
     */
    suspend fun requestGroupRoles(groupId: String): Boolean {
        if (!shouldRequest(groupId, "roles")) return true
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestGroupRoles(groupId)
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

    /**
     * Apply immediate member list changes from kind:9000 (add-user) and kind:9001 (remove-user)
     * admin events. This prevents ghost members and stale UI between the admin event and
     * the next kind:39002 full member list refresh.
     *
     * Only applies changes from events newer than the last authoritative kind:39002 member
     * list to avoid re-adding users that were removed (historical replays after AUTH).
     */
    private fun applyMemberChangeIfAdmin(message: NostrGroupClient.NostrMessage, groupId: String) {
        val targetPubkey = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
            ?: return
        // Skip historical events older than the authoritative member list.
        val memberListTimestamp = memberEventTimestamps[groupId] ?: 0L
        if (message.createdAt <= memberListTimestamp) return

        when (message.kind) {
            9000 -> { // add-user
                _groupMembers.update { current ->
                    val members = current[groupId] ?: return@update current
                    if (targetPubkey in members) current
                    else current + (groupId to members + targetPubkey)
                }
            }
            9001 -> { // remove-user
                _groupMembers.update { current ->
                    val members = current[groupId] ?: return@update current
                    if (targetPubkey !in members) current
                    else current + (groupId to members - targetPubkey)
                }
            }
        }
    }

    // Valid message kinds for group events (NIP-29 and related)
    private val validMessageKinds = setOf(
        9,      // Chat messages (NIP-29)
        9000,   // Group admin: add user
        9001,   // Group admin: remove user
        9002,   // Group admin: edit metadata (NIP-29)
        9009,   // Group admin: create invite (NIP-29)
        9021,   // Join request
        9022,   // Leave request
        9321    // Zap request (NIP-57)
    )

    // Deletion kinds that remove other events
    private val deletionKinds = setOf(
        5,      // Deletion request (NIP-09)
        9003,   // Group admin: delete event (NIP-29)
        9005    // Group admin: delete event (NIP-29 moderation)
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
        // Handle deletion events separately — but still track for pagination cursor
        if (message.kind in deletionKinds) {
            if (subscriptionId != null) {
                trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
            }
            handleDeletion(message, rawMsg)
            return null
        }

        // Only process valid message kinds
        if (message.kind !in validMessageKinds) {
            return null
        }

        val messageId = message.id
        if (messageId.isBlank() || !eventDeduplicator.tryAddSync(messageId)) {
            if (message.kind == 9) {
            }
            return null // Duplicate message
        }

        val groupId = extractGroupIdFromMessage(rawMsg) ?: return null

        if (message.kind == 9) {
        }

        // Populate global emoji cache from message emoji tags and backfill
        // any existing reactions that are missing their image URL.
        var newEmojis = false
        for (tag in message.tags) {
            if (tag.size >= 3 && tag[0] == "emoji" && !emojiUrlCache.containsKey(tag[1])) {
                emojiUrlCache.put(tag[1], tag[2])
                newEmojis = true
            }
        }
        if (newEmojis) backfillReactionEmojiUrls()

        // Update live cursor so reconnects resume from the right timestamp.
        // Fire-and-forget: cursor updates are non-critical and should not block message processing.
        if (relayUrl != null && message.createdAt > 0L) {
            scope.launch { liveCursorStore.update(relayUrl, groupId, message.createdAt) }
        }

        // Track message in state machine for cursor calculation
        if (subscriptionId != null) {
            trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
        }

        // Inline member list updates from admin events — provides immediate UI feedback
        // without waiting for a new kind:39002 event from the relay.
        applyMemberChangeIfAdmin(message, groupId)

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
        val chatMessages = messages.filter { it.kind == 9 }
        if (chatMessages.isNotEmpty()) {
        }
        // Record burst for adaptive tuning
        adaptiveConfig?.recordEventBurst(messages.size)

        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: emptyList()
            // Persistent index: O(1) per lookup, no rebuild.
            val index = messageIdIndex.getOrPut(groupId) {
                current.mapTo(mutableSetOf()) { it.id }
            }
            // index.add() returns true if new → filters and indexes in one pass.
            val newMessages = messages.filter { index.add(it.id) }
            if (newMessages.isEmpty()) return@update currentMap

            // Sort optimization: if all new messages are newer than the last existing
            // message, just append the sorted batch (the common live-message case).
            // Avoids re-sorting the full list on every flush.
            val lastExistingTs = current.lastOrNull()?.createdAt ?: 0L
            val allNewer = newMessages.all { it.createdAt >= lastExistingTs }
            val merged = if (allNewer) {
                current + newMessages.sortedBy { it.createdAt }
            } else {
                (current + newMessages).sortedBy { it.createdAt }
            }
            currentMap + (groupId to merged)
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

        // Also remove deleted reactions — flush pending first so deletions apply to full state.
        flushPendingReactions()
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
     * Handle incoming reaction (kind 7).
     *
     * @param immediate When true, the StateFlow emits synchronously (used for
     *                  optimistic UI updates from the user's own reaction).
     *                  When false (default), the emission is debounced to coalesce
     *                  burst loads from relays into a single UI update.
     * @return the pubkey of the reactor if metadata should be fetched
     */
    fun handleReaction(
        reaction: NostrGroupClient.NostrReaction,
        immediate: Boolean = false
    ): String? {
        val messageId = reaction.targetEventId
        if (messageId.isBlank()) return null

        // Deduplicate reactions by id
        if (!eventDeduplicator.tryAddSync(reaction.id)) {
            return null
        }

        // Check pending (staged) reactions first, then committed state.
        val currentReactions = _pendingReactions[messageId]
            ?: _reactions.value[messageId]
            ?: emptyMap()
        val emoji = reaction.emoji
        val reactorPubkey = reaction.pubkey

        // Get current reaction info for this emoji
        val currentInfo = currentReactions[emoji]
        val currentReactors = currentInfo?.reactors ?: emptyList()

        if (reactorPubkey in currentReactors) return null // Already reacted with this emoji

        val shortcode = emoji.trim(':')
        val resolvedUrl = reaction.emojiUrl
            ?: currentInfo?.emojiUrl
            ?: emojiUrlCache.get(shortcode)
            ?: findEmojiUrlInMessages(shortcode)

        val isNewCacheEntry = resolvedUrl != null && !emojiUrlCache.containsKey(shortcode)
        if (resolvedUrl != null) {
            emojiUrlCache.put(shortcode, resolvedUrl)
        }

        val updatedInfo = ReactionInfo(
            emojiUrl = resolvedUrl,
            reactors = currentReactors + reactorPubkey
        )
        val updatedEmojiMap = currentReactions + (emoji to updatedInfo)

        if (immediate) {
            // Optimistic update from the user — flush any pending batch first,
            // then apply this reaction and emit to UI synchronously.
            reactionFlushJob?.cancel()
            reactionFlushJob = null
            flushPendingReactions()
            _reactions.value = _reactions.value + (messageId to updatedEmojiMap)
        } else {
            // Relay burst — stage the change and schedule a debounced flush.
            // Subsequent handleReaction calls within the window read from both
            // _reactions.value and _pendingReactions so they see the full state.
            _pendingReactions[messageId] = updatedEmojiMap
            reactionFlushJob?.cancel()
            reactionFlushJob = scope.launch {
                delay(REACTION_DEBOUNCE_MS)
                flushPendingReactions()
            }
        }

        if (isNewCacheEntry) backfillReactionEmojiUrls()

        return reactorPubkey
    }

    private fun findEmojiUrlInMessages(shortcode: String): String? {
        for ((_, messages) in _messages.value) {
            for (msg in messages) {
                for (tag in msg.tags) {
                    if (tag.size >= 3 && tag[0] == "emoji" && tag[1] == shortcode) {
                        emojiUrlCache.put(shortcode, tag[2])
                        return tag[2]
                    }
                }
            }
        }
        return null
    }

    private fun backfillReactionEmojiUrls() {
        flushPendingReactions()
        val current = _reactions.value
        var changed = false
        val updated = current.mapValues { (_, emojiMap) ->
            emojiMap.mapValues { (emoji, info) ->
                if (info.emojiUrl == null) {
                    val cached = emojiUrlCache.get(emoji.trim(':'))
                    if (cached != null) {
                        changed = true
                        info.copy(emojiUrl = cached)
                    } else info
                } else info
            }
        }
        if (changed) _reactions.value = updated
    }

    /**
     * Remove a reaction from local state (used for rollback on relay rejection).
     */
    private fun removeReaction(messageId: String, emoji: String, pubkey: String) {
        // Flush any pending reactions so we operate on the full state.
        flushPendingReactions()
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
        currentRelayUrl = null
        _groups.value = emptyList()
        observedGroups.clear()

        // Invalidate group-list cache so the next relay fetches all kind:39000
        // instead of relying on stale data from a previous visit.
        _completeGroupLoadRelays.value = emptySet()

        // MUST be synchronous (not scope.launch). If this is async, requestGroupMessages
        // called immediately after switchRelay sees stale HasMore/Exhausted state from the
        // previous relay and startInitialLoad() returns null — group silently never loads.
        loadingRegistry.clear()
        eventDeduplicator.clear()
        messageIdIndex.clear()
    }

    /**
     * Remove a single relay entry from the in-memory cache so the rail updates immediately.
     */
    fun removeRelayEntry(url: String) {
        val normalized = url.normalizeRelayUrl()
        _completeGroupLoadRelays.update { it - normalized }
        _fullGroupListFetchedRelays.update { it - normalized }
        _groupsByRelay.update { current -> current - normalized }
    }

    /**
     * Clear all state including the per-relay group metadata cache.
     * Use on logout or full account reset.
     */
    suspend fun clear() {
        eventOrderingBuffer.flushAll()
        _completeGroupLoadRelays.value = emptySet()
        _fullGroupListFetchedRelays.value = emptySet()
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
        SecureStorage.clearAllJoinedGroupMetadataForAccount(pubKey)
        _joinedGroupsByRelay.value = emptyMap()
        currentPubkey = null
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
                // Add to deduplicator and persistent message index
                messages.forEach { msg -> eventDeduplicator.tryAddSync(msg.id) }
                val index = messageIdIndex.getOrPut(groupId) { mutableSetOf() }
                _messages.update { current ->
                    val existing = current[groupId] ?: emptyList()
                    // Seed index from existing + new, dedup via index
                    existing.forEach { index.add(it.id) }
                    val newMsgs = messages.filter { index.add(it.id) }
                    if (newMsgs.isEmpty() && existing.isNotEmpty()) return@update current
                    val merged = (existing + newMsgs).sortedBy { it.createdAt }
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
