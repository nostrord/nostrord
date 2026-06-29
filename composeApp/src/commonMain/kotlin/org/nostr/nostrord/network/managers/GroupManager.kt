package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.DeclaredChild
import org.nostr.nostrord.network.GroupAdmins
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.GroupRoles
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.RoleDefinition
import org.nostr.nostrord.network.groupMetadataListSerializer
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.addRestrictedGroupForRelay
import org.nostr.nostrord.storage.cache.CacheStore
import org.nostr.nostrord.storage.cache.CachedMsg
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import org.nostr.nostrord.storage.clearGroupMembershipFor
import org.nostr.nostrord.storage.clearMessageBlobMigratedFor
import org.nostr.nostrord.storage.getLeftGroupsForRelay
import org.nostr.nostrord.storage.getRestrictedGroupsForRelay
import org.nostr.nostrord.storage.isFullGroupListCacheFresh
import org.nostr.nostrord.storage.isGroupListCacheFresh
import org.nostr.nostrord.storage.isMessageBlobMigratedFor
import org.nostr.nostrord.storage.loadDroppedGroupIds
import org.nostr.nostrord.storage.loadGroupMembershipFor
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.storage.removeRestrictedGroupForRelay
import org.nostr.nostrord.storage.saveDroppedGroupIds
import org.nostr.nostrord.storage.saveFullGroupListEoseTimestamp
import org.nostr.nostrord.storage.saveGroupListEoseTimestamp
import org.nostr.nostrord.storage.saveGroupMembershipFor
import org.nostr.nostrord.storage.setMessageBlobMigratedFor
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl
import kotlin.coroutines.cancellation.CancellationException

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
    private val adaptiveConfig: AdaptiveConfig? = null,
    private val cacheStore: CacheStore = InMemoryCacheStore(),
    private val onNewMessagesFlushed: ((groupId: String, newMessages: List<NostrGroupClient.NostrMessage>) -> Unit)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventDeduplicator = EventDeduplicator()

    init {
        // The retry queue is the second half of optimistic send: when a queued
        // message is finally accepted (or permanently fails) on reconnect, reflect
        // that into the per-message status so the on-screen indicator resolves.
        pendingEventManager?.onEventDelivered = { eventId -> markDelivered(eventId) }
        pendingEventManager?.onEventPermanentlyFailed = { eventId, groupId, eventJson, reason ->
            markFailed(eventId, groupId, eventJson, reason)
        }
    }

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

    // Group-message recency, least-recent first (re-inserted on touch). Drives whole-group
    // eviction past [MAX_GROUPS_IN_MEMORY] so in-memory history stays bounded over a long
    // multi-group session. Per-group message-count capping is deferred to the disk-backed
    // history store (plan phase 4): trimming a live list here would desync messageIdIndex
    // and stop a re-fetched older message from re-rendering.
    private val groupMessageRecency = LinkedHashSet<String>()

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
        set(value) {
            _currentRelayUrl.value = value
        }
    private val _completeGroupLoadRelays = MutableStateFlow<Set<String>>(emptySet())

    /** Relays that finished serving their group list (EOSE this session); gates the "group no
     * longer available" check so a not-yet-loaded relay doesn't read as deleted. */
    val completeGroupLoadRelays: StateFlow<Set<String>> = _completeGroupLoadRelays.asStateFlow()

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
                _completeGroupLoadRelays,
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

    // Safety-net timers so the sidebar skeleton can never spin forever. Every
    // clear of [_loadingRelays] is otherwise event-driven (group-list EOSE,
    // CLOSED, or a connection Error/Reconnecting). A relay that connects, accepts
    // the REQ, then never sends EOSE nor CLOSED — common after a slow/flaky
    // NIP-46 bunker AUTH where the 2s awaitAuthOrTimeout races the signer — would
    // leave the relay stuck in _loadingRelays indefinitely (#88). Each
    // markRelayLoading arms a per-relay watchdog; markRelayLoaded / EOSE cancel it.
    private val loadingWatchdogs = mutableMapOf<String, Job>()
    private val loadingWatchdogTimeoutMs = 30_000L

    // Relays for which requestGroups() (unfiltered, no #d tag) was sent this session but
    // EOSE hasn't arrived yet, mapped to the epoch-seconds the REQ was sent. Lets
    // handleEoseSuspend() distinguish a full fetch from a lazy (joined-only) fetch when
    // the same sub ID is reused. The timestamp makes the dedup self-expiring: a REQ that
    // never gets EOSE/CLOSED stops blocking retries after [pendingFullFetchStaleSeconds]
    // without the loading watchdog having to erase the marker (which would mis-classify a
    // genuinely slow EOSE that arrives later as a partial fetch).
    private val pendingFullFetchRelays = mutableMapOf<String, Long>()
    private val pendingFullFetchStaleSeconds = 30L

    // Group IDs seen during an in-flight full fetch; cleared on EOSE to prune stale groups.
    private val pendingFetchSeenGroups = mutableMapOf<String, MutableSet<String>>()

    fun markPendingFullFetch(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        pendingFullFetchRelays[normalized] = epochSeconds()
        pendingFetchSeenGroups[normalized] = mutableSetOf()
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
        val sentAt = pendingFullFetchRelays[relayUrl.normalizeRelayUrl()] ?: return false
        // A marker older than the stale window means the REQ got neither EOSE nor
        // CLOSED; stop treating it as in-flight so a re-open of OTHER GROUPS can retry.
        return epochSeconds() - sentAt < pendingFullFetchStaleSeconds
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

    /**
     * Discard the IN-MEMORY record that the FULL group list was fetched for
     * [relayUrl] this session. Called once per relay when it completes NIP-42
     * AUTH: an auth-required relay answers the pre-AUTH unauthenticated
     * group-list REQ with an EMPTY EOSE, which handleEoseSuspend records as
     * "fully fetched", falsely satisfying the dedup in resubscribeAfterAuth and
     * suppressing the real post-AUTH fetch — leaving OTHER GROUPS empty even when
     * expanded (observed on hzrd149's relay). Deliberately does NOT touch the
     * persisted freshness timestamp: that survives across sessions and clearing
     * it on every auth would defeat the cross-session cache.
     */
    fun invalidateFullGroupListFetch(relayUrl: String) {
        _fullGroupListFetchedRelays.update { it - relayUrl.normalizeRelayUrl() }
    }

    fun markRelayLoading(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        _loadingRelays.update { it + normalized }
        // (Re)arm the watchdog: if no EOSE/CLOSED/error clears this relay within
        // the grace period, force-clear ONLY the skeleton flag so it stops
        // spinning. The pending-full-fetch dedup is released separately by the
        // self-expiring timestamp in hasPendingFullFetch — the watchdog must not
        // erase pendingFullFetchRelays, or a genuinely slow EOSE arriving after the
        // timeout would be mis-classified as a partial fetch (isFull=false), skip
        // pruning, and never mark the relay fully fetched.
        loadingWatchdogs.remove(normalized)?.cancel()
        loadingWatchdogs[normalized] = scope.launch {
            try {
                delay(loadingWatchdogTimeoutMs)
                _loadingRelays.update { it - normalized }
            } finally {
                // Remove self only if still the current watchdog for this relay, so a
                // watchdog re-armed by a concurrent markRelayLoading isn't evicted
                // (which would leave it uncancellable and able to clear a later fetch).
                if (loadingWatchdogs[normalized] === coroutineContext[Job]) {
                    loadingWatchdogs.remove(normalized)
                }
            }
        }
    }

    fun markRelayLoaded(relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        _loadingRelays.update { it - normalized }
        loadingWatchdogs.remove(normalized)?.cancel()
    }

    /**
     * Suspends until the relay's `group-list` EOSE has arrived (i.e. it appears
     * in [_completeGroupLoadRelays]), or until [timeoutMs] elapses. Returns true
     * if EOSE was observed, false if the relay timed out without emitting one.
     *
     * Used by callers that need to act on the populated group cache after
     * `requestGroups()` — preferred over a fixed `delay(N)` so fast relays
     * proceed immediately and slow relays still get bounded latency.
     */
    suspend fun awaitGroupListEose(relayUrl: String, timeoutMs: Long = 5_000): Boolean {
        val normalized = relayUrl.normalizeRelayUrl()
        if (normalized in _completeGroupLoadRelays.value) return true
        return withTimeoutOrNull(timeoutMs) {
            _completeGroupLoadRelays.first { normalized in it }
            true
        } ?: false
    }

    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()

    /**
     * Delivery status of the local user's own messages (optimistic send).
     * Sending = the message is on screen and awaiting relay confirmation (or
     * queued for auto-retry); Failed = the relay rejected it or retries were
     * exhausted. A delivered message has NO entry here and renders without an
     * indicator. Keyed by event id. [Failed] carries the signed JSON so the
     * message can be re-sent without re-signing.
     */
    sealed interface MessageStatus {
        data object Sending : MessageStatus

        data class Failed(
            val reason: String,
            val groupId: String,
            val eventJson: String,
        ) : MessageStatus
    }

    private val _messageStatus = MutableStateFlow<Map<String, MessageStatus>>(emptyMap())
    val messageStatus: StateFlow<Map<String, MessageStatus>> = _messageStatus.asStateFlow()

    // Per-relay joined groups cache — the single source of truth for membership.
    private val _joinedGroupsByRelay = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = _joinedGroupsByRelay.asStateFlow()

    // Active-relay view — derived so it can never drift from _joinedGroupsByRelay.
    val joinedGroups: StateFlow<Set<String>> = combine(
        _joinedGroupsByRelay,
        _currentRelayUrl,
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

    // Groups whose initial read is waiting on NIP-42 AUTH (held by holdInitialLoadForReauth).
    // The UI treats these as still-loading so it shows skeletons across the whole pre-AUTH
    // dance (pre-AUTH CLOSE -> AUTH -> resubscribeAfterAuth reset/replay), which briefly
    // bounces the controller through Idle, instead of flashing "No messages yet". Cleared
    // the moment the controller reaches a settled state (an authenticated read completed).
    private val _groupsAwaitingAuthRead = MutableStateFlow<Set<String>>(emptySet())
    val groupsAwaitingAuthRead: StateFlow<Set<String>> = _groupsAwaitingAuthRead.asStateFlow()

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
        val reactors: List<String>, // List of pubkeys who reacted with this emoji
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

    // Durable "I explicitly left this group" intent (kind:9022 sent), persisted per-account/per-relay
    // with a 30-day TTL and restored on cold start. This is the authoritative override the membership
    // derivation checks BEFORE the relay's kind:39002, so a left group reads as not-a-member ("Request
    // to Join") even when the relay keeps listing us (some relays do after a 9022). A rejoin clears it.
    private val _leftGroups = MutableStateFlow<Map<String, Long>>(emptyMap())
    val leftGroups: StateFlow<Set<String>> =
        _leftGroups
            .map { it.keys }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // Lets clientForGroup reconnect a group's own relay on demand. Wired by NostrRepository.
    var messageHandler: ((String, NostrGroupClient) -> Unit)? = null

    // Gate for the approval-recovery path: only fires when the user actually
    // sent kind:9021, never for historical events on already-joined groups.
    // Exposed as a StateFlow so the membership derivation reads "I have an outstanding join request"
    // from this LOCAL truth (set on our 9021, cleared on our 9022/leave/approval) instead of the
    // kind:9021 echo in the message feed, which leaveGroup clears and a re-fetch races, leaving a
    // left group stuck on "Request pending" until a reload.
    private val _pendingApprovalSince = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pendingApprovalSince: StateFlow<Map<String, Long>> = _pendingApprovalSince.asStateFlow()

    // Drops the relay's kind:9022 echo and updated kind:39002 that arrive in
    // the milliseconds after a leave, otherwise they re-populate the lists we
    // just cleared and the UI shows zombie members + a "you left" message.
    private val recentlyLeftAt = mutableMapOf<String, Long>()

    /**
     * True when the group was deleted or left on THIS device. Stale kind:10009
     * merges skip these so a lagging relay cannot resurrect them.
     */
    fun isLocallyDropped(groupId: String): Boolean = groupId in deletedGroupIds || recentlyLeftAt.containsKey(groupId)
    private val LEFT_GROUP_GRACE_MS = 5_000L

    // Relay-of-origin per groupId, recorded from the WebSocket that delivered
    // each event. Used by notifications to route clicks to the correct relay
    // when getRelayForGroup would be ambiguous.
    private val _latestMessageRelayByGroup = MutableStateFlow<Map<String, String>>(emptyMap())
    fun getLatestMessageRelayForGroup(groupId: String): String? = _latestMessageRelayByGroup.value[groupId]

    private fun isRecentlyLeft(groupId: String): Boolean {
        val at = recentlyLeftAt[groupId] ?: return false
        if (epochMillis() - at < LEFT_GROUP_GRACE_MS) return true
        recentlyLeftAt.remove(groupId)
        return false
    }

    // When a group was joined this session. Auto-forget skips groups joined within
    // RECENT_JOIN_GRACE_MS so a just-joined group whose kind:39000 is still in flight is
    // never mistaken for a deleted orphan.
    private val recentlyJoinedAt = mutableMapOf<String, Long>()
    private val RECENT_JOIN_GRACE_MS = 60_000L

    fun markRecentlyJoined(groupId: String) {
        recentlyJoinedAt[groupId] = epochMillis()
    }

    /**
     * Orphaned joined pins safe to auto-forget: groups still missing kind:39000 after their
     * relay finished serving its group list (so the relay was asked and does not have them —
     * deleted, or filed under the wrong relay). Two guards keep this from removing real
     * groups: skip any group joined within [RECENT_JOIN_GRACE_MS] (metadata may still be
     * arriving), and skip any relay where EVERY joined group is an orphan — a relay glitch or
     * transient data loss must never wipe the user's groups; those stay as manual-forget
     * orphans. Returns relay -> forgettable group ids.
     */
    fun autoForgettableOrphans(): Map<String, Set<String>> {
        val orphans = _orphanedJoinedByRelay.value
        if (orphans.isEmpty()) return emptyMap()
        val now = epochMillis()
        val joined = _joinedGroupsByRelay.value
        return orphans
            .mapNotNull { (relay, orphanIds) ->
                val joinedOnRelay = joined[relay].orEmpty()
                // Glitch guard: a healthy relay served metadata for at least one joined group.
                if (joinedOnRelay.isEmpty() || orphanIds.size >= joinedOnRelay.size) {
                    return@mapNotNull null
                }
                val safe =
                    orphanIds.filterTo(mutableSetOf()) { id ->
                        val at = recentlyJoinedAt[id]
                        at == null || now - at > RECENT_JOIN_GRACE_MS
                    }
                if (safe.isEmpty()) null else relay to (safe as Set<String>)
            }.toMap()
    }

    /**
     * Mark a group as restricted (relay denied access).
     * Called when a CLOSED "restricted" message arrives for a group subscription.
     *
     * Kicks off a debounced mux refresh so mux_meta / mux_del / mux_chat are
     * re-sent with this group excluded from the #d/#h batch — otherwise the
     * relay would keep CLOSE-ing the whole mux whenever this group is in it,
     * starving the other joined groups of metadata/delete updates.
     */
    fun markGroupRestricted(groupId: String, reason: String) {
        val wasAlreadyRestricted = groupId in _restrictedGroups.value
        _restrictedGroups.update { it + (groupId to reason) }
        if (!wasAlreadyRestricted) {
            val relayUrl = getRelayForGroup(groupId)
            if (relayUrl != null) {
                currentPubkey?.let { pk ->
                    try {
                        SecureStorage.addRestrictedGroupForRelay(pk, relayUrl, groupId, reason, epochSeconds())
                    } catch (_: Exception) {}
                }
                refreshMuxDebounced(relayUrl)
            }
        }
    }

    /**
     * Clear restricted status for a group (e.g. after successful join).
     */
    fun clearGroupRestricted(groupId: String) {
        if (groupId !in _restrictedGroups.value) return
        _restrictedGroups.update { it - groupId }
        val relayUrl = getRelayForGroup(groupId)
        if (relayUrl != null) {
            currentPubkey?.let { pk ->
                try {
                    SecureStorage.removeRestrictedGroupForRelay(pk, relayUrl, groupId)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Load persisted restricted groups for [relayUrls] into [_restrictedGroups].
     * Called on startup before the first connect, so batched REQs exclude known
     * restricted IDs from the start. Entries older than 7 days are auto-expired
     * by [SecureStorage.getRestrictedGroupsForRelay].
     */
    fun loadRestrictedGroupsFromStorage(pubKey: String, relayUrls: List<String>) {
        if (relayUrls.isEmpty()) return
        val now = epochSeconds()
        val loaded = mutableMapOf<String, String>()
        for (url in relayUrls) {
            try {
                loaded.putAll(SecureStorage.getRestrictedGroupsForRelay(pubKey, url, now))
            } catch (_: Exception) {}
        }
        if (loaded.isNotEmpty()) {
            _restrictedGroups.update { it + loaded }
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val LOADING_TIMEOUT_MS = 10_000L // 10 seconds timeout for loading

        // Cap the disk-first cache read so a hung IndexedDB cursor on web can never dead-lock
        // pagination — fall through to the relay if the cache does not answer in time.
        const val CACHE_PAGE_TIMEOUT_MS = 2_000L

        // Budget for awaiting NIP-42 AUTH before the initial group read. Sized above a
        // remote (NIP-46) signer's round-trip so a private group on a bunker authenticates
        // before the first REQ instead of racing it (a pre-AUTH REQ is CLOSED "auth-required"
        // and the empty result flashes a false "No messages yet"). Public relays skip it.
        const val INITIAL_READ_AUTH_TIMEOUT_MS = 12_000L
        const val MAX_PERSISTED_MESSAGES = 100 // Limit messages per group for storage

        // Most-recently-used groups whose messages stay hydrated in memory. Groups beyond
        // this are evicted whole (list + dedup index + reactions, persisted first) to bound
        // long-session growth across many opened groups. The active group is never evicted.
        const val MAX_GROUPS_IN_MEMORY = 30

        // How many cached messages to render instantly when a group is opened, before the
        // live subscription refreshes the tail.
        const val CACHE_HYDRATE_LIMIT = 300

        // Disk budget for the persistent history cache (messages + events), per account.
        // Evicted oldest-first once exceeded; the eviction is debounced behind writes.
        const val CACHE_BYTE_BUDGET = 75L * 1024 * 1024
        const val CACHE_EVICTION_DEBOUNCE_MS = 30_000L
        const val MEMBER_LOAD_TIMEOUT_MS = 8_000L // Safety timeout for member loading state
        const val REQUEST_COOLDOWN_MS = 2_000L // Prevents duplicate REQs within this window
        const val REACTION_DEBOUNCE_MS = 50L // Coalesces burst reaction arrivals
        const val GROUP_SNAPSHOT_EXTRA_CAP = 100 // Recently-seen non-joined groups kept per relay
    }

    private var currentPubkey: String? = null

    fun setCurrentPubkey(pubkey: String?) {
        currentPubkey = pubkey
    }

    // Catch-up `since` for the first mux refresh after an account switch.
    // Lets the now-active account fetch events that happened while it was
    // inactive. Expires automatically after [CATCH_UP_TTL_S] so it never
    // re-applies on a later reconnect of the same session.
    @kotlin.concurrent.Volatile
    private var catchUpSinceSeconds: Long? = null

    @kotlin.concurrent.Volatile
    private var catchUpSetAtSeconds: Long = 0L
    private val CATCH_UP_TTL_S = 60L

    /**
     * Set a one-shot catch-up `since` to apply on the next mux refresh(es).
     * Pass null to clear. Used by [reloadForActiveAccount] right after an
     * account switch so notification-bearing events that happened while this
     * account was inactive still arrive.
     */
    fun setCatchUpSince(unixSeconds: Long?) {
        catchUpSinceSeconds = unixSeconds
        catchUpSetAtSeconds = if (unixSeconds != null) epochSeconds() else 0L
    }

    private fun activeCatchUpSince(): Long? {
        val s = catchUpSinceSeconds ?: return null
        if (epochSeconds() - catchUpSetAtSeconds > CATCH_UP_TTL_S) {
            catchUpSinceSeconds = null
            return null
        }
        return s
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
    fun getRelayForGroup(groupId: String): String? = _groupsByRelay.value.entries.firstOrNull { (_, groups) -> groups.any { it.id == groupId } }?.key
        // Fallback: private groups may not appear in kind 39000 listing but are
        // tracked in _joinedGroupsByRelay (from kind 10009). Without this,
        // clientForGroup() falls back to the primary client which may be wrong.
        ?: _joinedGroupsByRelay.value.entries.firstOrNull { (_, groupIds) -> groupId in groupIds }?.key

    /**
     * Returns the WebSocket client for the relay that hosts [groupId].
     *
     * A NIP-29 relay rejects a kind:9 ("blocked: group doesn't exist") if it does not
     * host the group in the `h` tag, so once the group's relay is known this never falls
     * back to the primary. A non-primary pool relay that was evicted is reconnected on
     * demand; a relay that is down (or whose socket is dead) returns null so the send
     * fails honestly instead of being misrouted.
     */
    private suspend fun clientForGroup(groupId: String): NostrGroupClient? {
        val relayUrl = getRelayForGroup(groupId)
            ?: return connectionManager.getPrimaryClient()
        val client = connectionManager.getClientForRelay(relayUrl)
        return when {
            client != null && client.isConnected() -> client
            relayUrl.normalizeRelayUrl() == connectionManager.currentRelayUrl.value.normalizeRelayUrl() -> null
            client != null -> null
            else -> messageHandler?.let { connectionManager.getOrConnectRelay(relayUrl, it) }
        }
    }

    /** Returns all cached groups for the given relay URL. */
    fun getGroupsForRelay(relayUrl: String): List<GroupMetadata> = _groupsByRelay.value[relayUrl] ?: emptyList()

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
        // A group we durably left, AND are no longer joined to, must not re-enter the shared mux via
        // the loaded/opened terms (reopening it to see the locked panel would otherwise re-subscribe
        // its live chat). A still-joined group is NEVER excluded — a stale left marker on a rejoined
        // group must not cut off its live messages.
        val excluded = _leftGroups.value.keys - joined
        return (joined + loaded + opened).distinct().filter { it !in excluded }
    }

    /**
     * Set the group currently being viewed by the user.
     * If this group hasn't been opened before, triggers on-demand subscriptions
     * (messages, members, admins, metadata). Refreshes the mux so chat/reactions
     * include this group.
     */
    fun setActiveGroupId(groupId: String?) {
        _activeGroupId = groupId
        if (groupId != null) {
            touchGroupRecency(groupId)
            evictGroupMessagesBeyondCap()
            // Instant render from the persistent history cache; the live sub refreshes the tail.
            scope.launch { hydrateMessagesFromCache(groupId) }
        }
        // Fallback to currentRelayUrl when the group isn't tracked yet (e.g. user
        // navigated to a private group URL without being a member).
        val relayUrl = if (groupId != null) (getRelayForGroup(groupId) ?: currentRelayUrl) else currentRelayUrl

        // On-demand: first time opening this group, fetch its data.
        val isNew = if (groupId != null) {
            val current = _openedGroupIds.value
            if (groupId !in current) {
                _openedGroupIds.value = current + groupId
                true
            } else {
                false
            }
        } else {
            false
        }

        if (groupId == null) {
            if (relayUrl != null) scope.launch { refreshMuxSubscriptionsForRelay(relayUrl) }
            return
        }

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
                // Wait for NIP-42 AUTH before the direct REQs. Closed/private
                // groups answer with CLOSED "auth-required" if these race ahead,
                // and the 39002 never arrives, leaving the screen stuck on
                // "Awaiting admin approval" for an already-approved member.
                client.awaitAuthOrTimeout()
                // Direct fast-lane REQs for the ACTIVE group. Re-issued on EVERY
                // switch, not just the first open: a first open that raced AUTH,
                // timed out an EOSE, or landed before the socket connected would
                // otherwise never re-pull this group's metadata/members/admins, so
                // the screen stays partly empty until something else happens to
                // refresh it. shouldRequest() still debounces rapid re-entry, and
                // duplicates are handled by the event deduplicator.
                client.requestGroupMetadata(groupId) // Metadata (essential for private groups)
                requestGroupMembers(groupId) // Fast member list
                requestGroupAdmins(groupId) // Fast admin list
                // Load message history on first open AND whenever the loader is sitting in a
                // non-loaded state. A cross-relay switch runs clearForRelaySwitch (resets every
                // controller to Idle); the bulk re-subscribe reloads the other groups but skips
                // the active one, so without this the active group stays Idle (hasMore=false) and
                // can never paginate until restart. startInitialLoad is a no-op when the group is
                // already HasMore/Paginating, so a normal re-entry still costs nothing.
                val loadState = loadingRegistry.getController(groupId).state.value
                val needsLoad = isNew ||
                    loadState is GroupLoadingState.Idle ||
                    loadState is GroupLoadingState.Error
                if (needsLoad) requestGroupMessages(groupId)
            }

            muxJob.join()
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
        // Drop restricted groups from every batched filter — including a single
        // restricted #d/#h value makes the relay CLOSE the entire subscription,
        // silencing metadata/delete updates for the groups we DO belong to.
        val restricted = _restrictedGroups.value.keys
        val allGroupIds = getGroupIdsForMux(relayUrl).filter { it !in restricted }
        if (allGroupIds.isEmpty()) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return

        // Two cost models: on the primary relay we keep the on-demand pattern (only
        // groups the user has opened in this session subscribe to live chat) so the
        // hot path stays cheap. On background joined relays we subscribe to live
        // chat for *every* joined group so notifications/sound/unread fire cross-relay
        // — the user isn't browsing them, so the on-demand fallback to _activeGroupId
        // (which lives on a different relay) would silence them entirely.
        //
        // Exception: during a switch-in catch-up window, the primary relay also
        // subscribes to chat for ALL joined groups. Without this, an account that
        // landed on the home screen would only receive notifications for groups
        // it manually opened, defeating the whole point of the catch-up since.
        val catchUp = activeCatchUpSince()
        val isPrimary = relayUrl.normalizeRelayUrl() == currentRelayUrl?.normalizeRelayUrl()
        val chatGroupIds = if (isPrimary && catchUp == null) {
            _openedGroupIds.value
                .filter { it in allGroupIds }
                .ifEmpty {
                    val active = _activeGroupId
                    if (active != null && active in allGroupIds) listOf(active) else emptyList()
                }
        } else {
            allGroupIds
        }

        val baseChatSince = if (chatGroupIds.isNotEmpty()) {
            liveCursorStore.getMinSince(relayUrl, chatGroupIds)
        } else {
            0L
        }
        // After a switch-in, regress `since` (one-shot, TTL-bounded) so the
        // mux replays anything the now-active account missed while inactive.
        // `MuxSubscriptionTracker.needsRefresh` already treats a regressed
        // `since` as a refresh trigger, so the CLOSE+REQ cycle fires here.
        val chatSince = when {
            catchUp == null -> baseChatSince
            chatGroupIds.isEmpty() -> baseChatSince
            else -> minOf(baseChatSince, catchUp)
        }

        val desired = MuxSubscriptionTracker.MuxState(
            metadataGroupIds = allGroupIds.toSet(),
            chatGroupIds = chatGroupIds.toSet(),
            chatSinceSeconds = chatSince,
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
        val relayUrls = (
            _joinedGroupsByRelay.value.keys +
                _messages.value.keys.mapNotNull { getRelayForGroup(it) }
            ).distinct()
        for (relayUrl in relayUrls) {
            try {
                refreshMuxSubscriptionsForRelay(relayUrl)
            } catch (_: Exception) {}
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
    // Per-group cooldown so the several post-AUTH callers (connect, switchRelay,
    // ensureJoinedRelaysConnected, resubscribeAfterAuth) do not each re-fire the same
    // private group's #d REQs in the window before its kind:39000 lands. Without it a
    // cold boot fanned out duplicate per-group REQs that could exhaust a relay's
    // subscription budget and get the chat/pagination REQs CLOSED "too many subscriptions".
    private val privateGroupFetchAt = mutableMapOf<String, Long>()
    private val PRIVATE_GROUP_FETCH_COOLDOWN_MS = 10_000L

    suspend fun requestPrivateGroupData(relayUrl: String) {
        val uncached = getUncachedJoinedGroups(relayUrl)
        if (uncached.isEmpty()) return
        val client = connectionManager.getClientForRelay(relayUrl) ?: return
        if (!client.isConnected()) return
        val now = epochMillis()
        val toFetch = uncached.filter { now - (privateGroupFetchAt[it] ?: 0L) > PRIVATE_GROUP_FETCH_COOLDOWN_MS }
        if (toFetch.isEmpty()) return
        toFetch.forEach { privateGroupFetchAt[it] = now }
        try {
            // One batched #d REQ for ALL missing metadata, not one per group.
            client.requestGroupsMetadata(toFetch)
        } catch (_: Exception) {}
        for (groupId in toFetch) {
            try {
                // Members/admins may already be live (mux_meta or an earlier fetch); only
                // ask for what we still lack so we don't double the kind:39002/39001 REQs.
                if (groupId !in _groupMembers.value) client.requestGroupMembers(groupId)
                if (groupId !in _groupAdmins.value) client.requestGroupAdmins(groupId)
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
     * Load the active group's message history after a relay switch.
     *
     * [clearForRelaySwitch] resets every controller to Idle; the bulk re-subscribe reloads the
     * other groups but not the active one, and setActiveGroupId's own load races the clear and
     * gets wiped (it runs before clearForRelaySwitch). Re-firing here, after the new relay's
     * group-list EOSE (so after the clear), guarantees the active group leaves Idle and can
     * paginate. No-op if the group already loaded — requestGroupMessages only acts from Idle/Error.
     */
    suspend fun requestActiveGroupMessagesIfNeeded(relayUrl: String) {
        val groupId = _activeGroupId ?: return
        if (getRelayForGroup(groupId)?.normalizeRelayUrl() != relayUrl.normalizeRelayUrl()) return
        val state = loadingRegistry.getController(groupId).state.value
        if (state !is GroupLoadingState.Idle && state !is GroupLoadingState.Error) return
        requestGroupMessages(groupId)
    }

    /**
     * Load joined groups from storage
     */
    fun loadJoinedGroupsFromStorage(pubKey: String, relayUrl: String) {
        currentPubkey = pubKey
        // Restore the persisted dropped-group guard: a relay that missed our delete still serves the
        // old kind:10009, and the additive merge would resurrect the group on restart without this.
        deletedGroupIds.addAll(SecureStorage.loadDroppedGroupIds(pubKey))
        val groups = SecureStorage.getJoinedGroupsForRelay(pubKey, relayUrl)
        // Additive: a join that landed before this async restore must not be wiped
        // by the (older) persisted set.
        _joinedGroupsByRelay.update { current ->
            val key = relayUrl.normalizeRelayUrl()
            current + (key to (current[key].orEmpty() + groups))
        }
    }

    /**
     * Load joined groups for all given relays into the per-relay cache without
     * only touching the per-relay cache.
     */
    fun loadAllJoinedGroupsFromStorage(pubKey: String, relayUrls: List<String>) {
        val now = epochSeconds()
        // Restore the durable left markers into the flow (called at every restore path) so a left group
        // reads NONE after a restart. We do NOT filter the joined set against them: leaveGroup already
        // removes the group from the joined slot, and filtering here risked excluding a rejoined group
        // whose marker hadn't been cleared yet, breaking its live messages.
        val leftAll = mutableMapOf<String, Long>()
        for (url in relayUrls) leftAll.putAll(SecureStorage.getLeftGroupsForRelay(pubKey, url, now))
        val updates = relayUrls.associate { url ->
            url.normalizeRelayUrl() to SecureStorage.getJoinedGroupsForRelay(pubKey, url)
        }.filter { (_, groups) -> groups.isNotEmpty() }
        if (leftAll.isNotEmpty()) _leftGroups.update { it + leftAll }
        if (updates.isNotEmpty()) {
            // Additive, like loadJoinedGroupsFromStorage: never wipe a fresher join.
            _joinedGroupsByRelay.update { current ->
                current + updates.mapValues { (relay, groups) -> current[relay].orEmpty() + groups }
            }
        }
    }

    /** Persist [deletedGroupIds] so the dropped-group guard survives a restart (see its callers). */
    private fun persistDroppedGroups(pubKey: String? = currentPubkey) {
        val pk = pubKey?.takeIf { it.isNotBlank() } ?: return
        SecureStorage.saveDroppedGroupIds(pk, deletedGroupIds.toSet())
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
        inviteCode: String? = null,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            deletedGroupIds.remove(groupId)
            persistDroppedGroups()
            recentlyLeftAt.remove(groupId)
            // Rejoining clears the durable left marker BEFORE the optimistic bookkeeping below adds
            // the group back, so the additive kind:10009 merge can't drop the fresh join.
            _leftGroups.update { it - groupId }
            try {
                SecureStorage.removeLeftGroupForRelay(pubKey, groupRelayUrl.normalizeRelayUrl(), groupId)
            } catch (_: Exception) {}
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
                content = "/join",
            )

            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.JoinFailed(groupId, Exception("Event ID not generated")))

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Private/closed relays gate the join-request publish behind NIP-42 AUTH. Firing the
            // 9021 on an unauthenticated socket gets it dropped "auth-required" and the admin never
            // sees the pending request. awaitAuthSigned (not the requiresAuth-gated wait) also covers
            // the fresh-connect race where the relay has not issued its challenge yet by tap time:
            // it waits for the challenge to arrive, then for the signer. Returns fast on open/public
            // relays that never challenge.
            currentClient.awaitAuthSigned(signBudgetMs = INITIAL_READ_AUTH_TIMEOUT_MS)

            // Await the OK so a relay rejection (e.g. a closed group that only admits via invite
            // code) surfaces with its reason instead of failing silently, and so we only record the
            // optimistic membership when the request was actually accepted.
            when (val publish = currentClient.sendAndAwaitOk(message, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> Unit
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    return Result.Error(AppError.Group.JoinFailed(groupId, Exception(publish.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    return Result.Error(AppError.Group.JoinFailed(groupId, Exception("The relay did not confirm the join request.")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    return Result.Error(AppError.Group.JoinFailed(groupId, publish.exception))
            }

            // Normalized key, like every other _joinedGroupsByRelay writer: a raw URL
            // variant ("wss://x/" vs "wss://x") would start a parallel entry whose set
            // lacks the relay's other groups, and the next kind:10009 publish/echo
            // would read as the list being replaced instead of incremented.
            // Merged with the persisted slot because the in-memory map can be partial
            // early in a session (the storage restore is async): writing memory-only
            // here would clobber the slot, and on restart the slot is the only truth
            // (our own kind:10009 echo is dropped by the persisted timestamp guard).
            val normalizedGroupRelay = groupRelayUrl.normalizeRelayUrl()
            val stored = SecureStorage.getJoinedGroupsForRelay(pubKey, normalizedGroupRelay)
            val relayGroups = (_joinedGroupsByRelay.value[normalizedGroupRelay] ?: emptySet()) + stored
            val updated = relayGroups + groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, normalizedGroupRelay, updated)
            _joinedGroupsByRelay.update { it + (normalizedGroupRelay to updated) }
            markRecentlyJoined(groupId)

            publishJoinedGroups()

            clearGroupRestricted(groupId)
            // Seconds, to match GroupMembershipState.requestedAtSeconds (the "Requested ..." label).
            _pendingApprovalSince.update { it + (groupId to epochSeconds()) }
            refreshMuxSubscriptionsForRelay(groupRelayUrl)

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
     * Flip [groupId] to joined on [relayUrl] in memory immediately so the UI reacts
     * without waiting on a relay switch + signer + send (mirrors the optimistic follow
     * button). The real [joinGroup] confirms and persists it; [revertOptimisticJoin]
     * rolls it back on failure. Returns false when it was already joined, so the caller
     * knows not to revert a pre-existing membership. Memory-only: persistence stays with
     * the confirmed join so a failed attempt never lands in SecureStorage.
     */
    fun markOptimisticJoin(relayUrl: String, groupId: String): Boolean {
        val key = relayUrl.normalizeRelayUrl()
        if (groupId in (_joinedGroupsByRelay.value[key] ?: emptySet())) return false
        _joinedGroupsByRelay.update { current ->
            current + (key to ((current[key] ?: emptySet()) + groupId))
        }
        markRecentlyJoined(groupId)
        return true
    }

    /** Undo a [markOptimisticJoin] when the join ultimately fails. */
    fun revertOptimisticJoin(relayUrl: String, groupId: String) {
        val key = relayUrl.normalizeRelayUrl()
        _joinedGroupsByRelay.update { current ->
            current + (key to ((current[key] ?: emptySet()) - groupId))
        }
    }

    /**
     * Append the NIP-29 access flags to a kind:9002 tag list. Flags are presence-only:
     * each is added only when on; absence is the permissive default (public / open /
     * anyone-writes / discoverable). There are no public / open / un-restricted tags, so a
     * complete flag declaration is exactly the set of on-flags, which is also how the OFF
     * state is communicated to the relay.
     */
    private fun addAccessFlags(
        tags: MutableList<List<String>>,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
    ) {
        if (isPrivate) tags.add(listOf("private"))
        if (isClosed) tags.add(listOf("closed"))
        if (isRestricted) tags.add(listOf("restricted"))
        if (isHidden) tags.add(listOf("hidden"))
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
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        customGroupId: String? = null,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        publishJoinedGroups: suspend () -> Unit,
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
                content = "",
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
                suggestedGroupId = suggestedId,
            )

            // kind 9002: edit-metadata — sets name, about, picture, and access in one event.
            val metaTags = mutableListOf(
                listOf("h", confirmedGroupId),
                listOf("name", name),
            )
            // NIP-29 access flags are presence-only: absence is the permissive default
            // (public / open / anyone-writes / discoverable). There are no public / open /
            // un-restricted counterpart tags, so we emit each flag only when on and omit it
            // otherwise — that omission is what declares the OFF state to the relay.
            addAccessFlags(metaTags, isPrivate, isClosed, isRestricted, isHidden)
            if (!about.isNullOrBlank()) metaTags.add(listOf("about", about))
            if (!picture.isNullOrBlank()) metaTags.add(listOf("picture", picture))
            val signedMeta = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = metaTags,
                    content = "",
                ),
            )
            currentClient.send(
                buildJsonArray {
                    add("EVENT")
                    add(signedMeta.toJsonObject())
                }.toString(),
            )

            // Normalized key + merged with the persisted slot, for the same reasons as
            // joinGroup: a raw URL variant forks a parallel relay entry, and a partial
            // in-memory map would clobber the slot that restarts rely on.
            val normalizedCreateRelay = currentRelayUrl.normalizeRelayUrl()
            val storedAtCreate = SecureStorage.getJoinedGroupsForRelay(pubKey, normalizedCreateRelay)
            val relayGroups = (_joinedGroupsByRelay.value[normalizedCreateRelay] ?: emptySet()) + storedAtCreate
            val updatedAfterCreate = relayGroups + confirmedGroupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, normalizedCreateRelay, updatedAfterCreate)
            _joinedGroupsByRelay.update { it + (normalizedCreateRelay to updatedAfterCreate) }
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
        isRestricted: Boolean = false,
        isHidden: Boolean = false,
        pubKey: String,
        currentRelayUrl: String,
        signEvent: suspend (Event) -> Event,
        parentOp: ParentOp? = null,
        childrenEdit: ChildrenEdit? = null,
    ): Result<Unit> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val metaTags = mutableListOf(
                listOf("h", groupId),
                listOf("name", name),
            )
            // Presence-only NIP-29 flags (see createGroup): emit only the ones that are on.
            addAccessFlags(metaTags, isPrivate, isClosed, isRestricted, isHidden)
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
                    if (childrenEdit.closedChildren) {
                        listOf("closed-children")
                    } else {
                        listOf("open-children")
                    },
                )
            }

            val signedMeta = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = metaTags,
                    content = "",
                ),
            )
            val eventJson = buildJsonArray {
                add("EVENT")
                add(signedMeta.toJsonObject())
            }.toString()
            val eventId = signedMeta.id
                ?: return Result.Error(AppError.Group.EditFailed(groupId, Exception("Event ID not generated")))

            when (val result = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> {
                    // Pull the just-edited kind:39000 so name/picture/parent/children land locally
                    // immediately (groupsByRelay + childrenByParent), instead of waiting for a
                    // refresh that no longer happens now that we only fetch known groups.
                    currentClient.requestGroupMetadata(groupId)
                    refreshMuxSubscriptionsForRelay(groupRelayUrl)
                    Result.Success(Unit)
                }
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception(result.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.EditFailed(groupId, result.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.EditFailed(groupId, e))
        }
    }

    /** Child-list edit for [editGroup]: the full desired list plus the flag. */
    data class ChildrenEdit(
        val children: List<DeclaredChild>,
        val closedChildren: Boolean,
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
        signEvent: suspend (Event) -> Event,
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
            val signed = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = tags,
                    content = "",
                ),
            )
            val eventJson = buildJsonArray {
                add("EVENT")
                add(signed.toJsonObject())
            }.toString()
            val eventId = signed.id
                ?: return Result.Error(AppError.Group.EditFailed(groupId, Exception("Event ID not generated")))
            when (val res = currentClient.sendAndAwaitOk(eventJson, eventId)) {
                is org.nostr.nostrord.network.PublishResult.Success -> {
                    // Pull the just-updated kind:39000 so its new `parent` tag lands locally
                    // (childrenByParent + groupsByRelay) right away — otherwise the subgroup
                    // shows up parent-less (in the rail, missing from Subgroups) until a later
                    // refresh, which no longer happens now that we only fetch known groups.
                    currentClient.requestGroupMetadata(groupId)
                    refreshMuxSubscriptionsForRelay(groupRelayUrl)
                    Result.Success(Unit)
                }
                is org.nostr.nostrord.network.PublishResult.Rejected ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception(res.reason)))
                is org.nostr.nostrord.network.PublishResult.Timeout ->
                    Result.Error(AppError.Group.EditFailed(groupId, Exception("Relay did not respond in time")))
                is org.nostr.nostrord.network.PublishResult.Error ->
                    Result.Error(AppError.Group.EditFailed(groupId, res.exception))
            }
        } catch (e: Throwable) {
            Result.Error(AppError.Group.EditFailed(groupId, e))
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
        signEvent: suspend (Event) -> Event,
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
            )
            // Re-declare the existing access flags so this topology/children edit doesn't
            // drop them (presence-only, same rule as createGroup/editGroup). Omitting them
            // here previously cleared restricted/hidden and emitted bogus public/open tags.
            addAccessFlags(
                tags,
                isPrivate = meta?.isPublic == false,
                isClosed = meta?.isOpen == false,
                isRestricted = meta?.isRestricted == true,
                isHidden = meta?.isHidden == true,
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

            val signed = signEvent(
                Event(
                    pubkey = pubKey,
                    createdAt = epochMillis() / 1000,
                    kind = 9002,
                    tags = tags,
                    content = "",
                ),
            )
            val eventJson = buildJsonArray {
                add("EVENT")
                add(signed.toJsonObject())
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
        publishJoinedGroups: suspend () -> Unit,
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
                content = "",
            )
            val signedEvent = signEvent(event)
            val eventId = signedEvent.id
                ?: return Result.Error(AppError.Group.DeleteFailed(groupId, Exception("Event ID not generated")))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Background the 9008 so delete doesn't block on the relay OK (a half-open socket burns
            // the 10s timeout). The local cleanup below drops the group now; the send retries once.
            scope.launch {
                val pub = currentClient.sendAndAwaitOk(message, eventId)
                if (pub is org.nostr.nostrord.network.PublishResult.Timeout && connectionManager.reconnect()) {
                    val freshClient = connectionManager.getClientForRelay(groupRelayUrl)
                        ?: connectionManager.getPrimaryClient()
                    freshClient?.sendAndAwaitOk(message, eventId)
                }
            }

            val idsToRemove = setOf(groupId)
            val normalizedGroupRelay = groupRelayUrl.normalizeRelayUrl()
            // Merge memory with the persisted slot (the map can be partial early in a
            // session), then remove. The slot key must be the normalized URL: storage
            // slots hash the raw string, so a raw variant would write a parallel slot
            // and the canonical one would resurrect the group on restart.
            val storedBeforeLeave = SecureStorage.getJoinedGroupsForRelay(pubKey, normalizedGroupRelay)
            val relayGroupsBefore = (_joinedGroupsByRelay.value[normalizedGroupRelay] ?: emptySet()) + storedBeforeLeave
            val updatedAfterLeave = relayGroupsBefore - idsToRemove
            SecureStorage.saveJoinedGroupsForRelay(pubKey, normalizedGroupRelay, updatedAfterLeave)
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
            _groupsAwaitingAuthRead.update { it - idsToRemove }
            _groupAdmins.update { it - idsToRemove }
            _groupMembers.update { it - idsToRemove }
            idsToRemove.forEach { loadingRegistry.remove(it) }
            deletedGroupIds.addAll(idsToRemove)
            persistDroppedGroups(pubKey)
            recomputeSubgroupTopology()

            Result.Success(Unit)
        } catch (e: Throwable) {
            Result.Error(AppError.Group.DeleteFailed(groupId, e))
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
        _groupsAwaitingAuthRead.update { it - idsToRemove }
        _groupAdmins.update { it - idsToRemove }
        _groupMembers.update { it - idsToRemove }
        idsToRemove.forEach { loadingRegistry.remove(it) }
        deletedGroupIds.addAll(idsToRemove)
        persistDroppedGroups(pubKey)
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
            try {
                SecureStorage.saveJoinedGroupsForRelay(pubKey, normalized, updatedPerRelay)
            } catch (_: Exception) {}
        }
        deletedGroupIds.add(groupId)
        persistDroppedGroups(pubKey)
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
        signEvent: suspend (Event) -> Event,
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
                content = "",
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
        signEvent: suspend (Event) -> Event,
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
                    listOf("p", targetPubkey),
                ),
                content = "",
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
        signEvent: suspend (Event) -> Event,
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
                    listOf("e", joinRequestEventId),
                ),
                content = "",
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
        signEvent: suspend (Event) -> Event,
    ): Result<String> {
        val groupRelayUrl = getRelayForGroup(groupId) ?: currentRelayUrl
        val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
            ?: connectionManager.getPrimaryClient()
            ?: return Result.Error(AppError.Network.Disconnected(groupRelayUrl))

        return try {
            val code = generateInviteCode()
            val tags = listOf(
                listOf("h", groupId),
                listOf("code", code),
            )
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9009,
                tags = tags,
                content = "",
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
        signEvent: suspend (Event) -> Event,
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
                    listOf("e", eventId),
                ),
                content = "",
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
        publishJoinedGroups: suspend () -> Unit,
    ): Result<Unit> {
        // Normalize so the joined-set/storage keys match those written by joinGroup and the
        // kind:10009 handler. getRelayForGroup already returns a normalized key, but the
        // currentRelayUrl fallback may not, and a non-normalized key would leave the group
        // in the canonical (normalized) slot and resurrect it on the next publish.
        val groupRelayUrl = (getRelayForGroup(groupId) ?: currentRelayUrl).normalizeRelayUrl()

        return try {
            // Best-effort 9022 to the group relay. A dead or unreachable relay must NOT
            // block removal from the user's own kind:10009 list, so a missing client or a
            // failed send is swallowed and we still clean the list below. The kind:10009
            // republish goes to the outbox relays, which are independent of this relay.
            val currentClient = connectionManager.getClientForRelay(groupRelayUrl)
                ?: connectionManager.getPrimaryClient()
            if (currentClient != null) {
                try {
                    val event = Event(
                        pubkey = pubKey,
                        createdAt = epochMillis() / 1000,
                        kind = 9022,
                        tags = listOf(listOf("h", groupId)),
                        content = reason.orEmpty(),
                    )
                    val signedEvent = signEvent(event)
                    val message = buildJsonArray {
                        add("EVENT")
                        add(signedEvent.toJsonObject())
                    }.toString()
                    // Private/closed relays gate the leave-request behind NIP-42 AUTH. Firing the
                    // 9022 on an unauthenticated socket gets it dropped "auth-required", so the relay
                    // never removes the user and re-fetched members keep listing them (the group looks
                    // joined again on reopen). Wait for AUTH first, like the join path. No-op on
                    // open/public relays. Fire-and-forget after that so the local cleanup below is
                    // never delayed by an OK that some relays don't send for a 9022.
                    if (currentClient.requiresAuth() && !currentClient.hasAuthSucceeded()) {
                        currentClient.awaitAuthOrTimeout(INITIAL_READ_AUTH_TIMEOUT_MS)
                    }
                    currentClient.send(message)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Relay send/sign failed; the kind:10009 cleanup below still applies.
                }
            }

            val relayGroups = _joinedGroupsByRelay.value[groupRelayUrl] ?: emptySet()
            val updatedAfterLeave = relayGroups - groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, groupRelayUrl, updatedAfterLeave)
            _joinedGroupsByRelay.update { it + (groupRelayUrl to updatedAfterLeave) }
            persistJoinedGroupMetadataSnapshot(groupRelayUrl)

            publishJoinedGroups()

            recentlyLeftAt[groupId] = epochMillis()
            // Durable leave intent: outlives recentlyLeftAt (5s) and a restart, so the membership
            // derivation keeps reporting NONE even when the relay still lists us in kind:39002.
            _leftGroups.update { it + (groupId to epochSeconds()) }
            try {
                SecureStorage.addLeftGroupForRelay(pubKey, groupRelayUrl, groupId, epochSeconds())
            } catch (_: Exception) {}

            _messages.update { it - groupId }
            _isLoadingMore.update { it - groupId }
            _hasMoreMessages.update { it - groupId }
            _groupStates.update { it - groupId }
            _groupsAwaitingAuthRead.update { it - groupId }
            _groupMembers.update { it - groupId }
            _groupAdmins.update { it - groupId }
            _groupRoles.update { it - groupId }
            _loadingMembers.update { it - groupId }
            memberEventTimestamps.remove(groupId)
            adminEventTimestamps.remove(groupId)
            roleEventTimestamps.remove(groupId)
            _pendingApprovalSince.update { it - groupId }
            messageIdIndex.remove(groupId)
            _latestMessageRelayByGroup.update { it - groupId }
            observedGroupsMutex.withLock { observedGroups.remove(groupId) }
            loadingRegistry.remove(groupId)
            _openedGroupIds.update { it - groupId }
            // When the relay gates this group's reads behind NIP-42 AUTH, leaving makes us a
            // non-member and the relay WILL re-CLOSE our reads "restricted" a round-trip later.
            // Leaving is an explicit reset of intent — a future rejoin should
            // get a fresh access attempt instead of being silently excluded.
            clearGroupRestricted(groupId)

            // Overwrite the persisted membership cache (now that the in-memory maps no longer carry
            // this group) so a restart does not re-hydrate self into the left group's member list —
            // which would read back as MEMBER (no Join button, cached chat) on a private group whose
            // 39002 the relay no longer serves us.
            persistGroupMembershipSnapshot()

            // Drop the group from the live mux so the relay stops pushing
            // events for it the moment we send the leave.
            refreshMuxSubscriptionsForRelay(groupRelayUrl)

            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(AppError.Group.LeaveFailed(groupId, e))
        }
    }

    /**
     * Check if a group is joined
     */
    fun isGroupJoined(groupId: String): Boolean = groupId in activeJoinedGroups

    /**
     * Request group messages (initial load).
     * Uses the state machine for reliable loading with proper state transitions.
     */
    suspend fun requestGroupMessages(groupId: String, channel: String? = null): Boolean {
        val currentClient = clientForGroup(groupId) ?: return false

        // Get controller and attempt to start initial load. Enter InitialLoading immediately
        // (so the UI shows skeletons) but DEFER the load timeout: the AUTH wait below can take
        // several seconds on a remote signer and must not consume the timeout budget.
        val controller = loadingRegistry.getController(groupId)
        val subscriptionId = controller.startInitialLoad(armTimeout = false) ?: return false

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        // Update legacy flags for UI compatibility
        updateLegacyFlags(groupId, controller.state.value)

        // Observe state changes to update legacy flags (only once per group)
        observeStateChanges(groupId, controller)

        // Private/closed groups gate reads behind NIP-42 AUTH. Firing the initial REQ before
        // AUTH lands gets CLOSED "auth-required" and zero events, which the UI mistakes for an
        // empty group (the "No messages yet" flicker on bunker login). Wait for AUTH first,
        // with a budget sized for a remote (NIP-46) signer. The controller stays in
        // InitialLoading during the wait, so the UI keeps showing skeletons. No-op once AUTH
        // has succeeded; skipped on public relays that never challenge. Mirrors loadMoreMessages.
        if (currentClient.requiresAuth() && !currentClient.hasAuthSucceeded()) {
            currentClient.awaitAuthOrTimeout(INITIAL_READ_AUTH_TIMEOUT_MS)
        }

        // If messages have already been delivered by mux (possibly during the AUTH wait),
        // request only events older than the oldest one we have to avoid deduplication
        // causing messageCount=0 → Exhausted.
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
                subscriptionId = subscriptionId,
            )
            // The authenticated REQ is now on the wire — start the load timeout.
            controller.armInitialTimeout(subscriptionId)
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
                // A settled state means an authenticated read completed (pre-AUTH reads are
                // held, not settled), so the group is no longer awaiting AUTH. Idle/InitialLoading
                // keep the flag so the resubscribeAfterAuth reset window stays "loading".
                if (state is GroupLoadingState.HasMore ||
                    state is GroupLoadingState.Exhausted ||
                    state is GroupLoadingState.Error
                ) {
                    _groupsAwaitingAuthRead.update { it - groupId }
                }
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
        // Disk-first: serve an older page from the persistent cache before any relay round-trip
        // (and without touching the pagination state machine). Only when local history is
        // exhausted do we fall through to the relay. Works offline too — no client required.
        // Guarded by a timeout: a hung IndexedDB read on web must NEVER dead-lock pagination —
        // the web load latch only releases once this returns, so a hang froze scroll-back
        // entirely (the group settled on HasMore but the latch stayed stuck after one page).
        val servedFromCache = withTimeoutOrNull(CACHE_PAGE_TIMEOUT_MS) { loadOlderFromCache(groupId) } ?: false
        if (servedFromCache) return true

        val currentClient = clientForGroup(groupId) ?: return false

        // Get controller and attempt to start pagination
        val controller = loadingRegistry.getController(groupId)
        val (subscriptionId, cursor) = controller.startPagination() ?: run {
            // Slow relays (e.g. groups.0xchat.com) sometimes deliver messages but no EOSE
            // within the 10s window, leaving the controller in Error(PARTIAL_TIMEOUT) with
            // an advanced cursor. Auto-resume via retry() so user scroll keeps pagination
            // moving instead of deadlocking until a manual retry. retry() honors maxRetries,
            // so a permanently broken relay still stops after a bounded number of attempts.
            val st = controller.state.value
            val canResume = st is GroupLoadingState.Error &&
                st.reason == GroupLoadingState.ErrorReason.PARTIAL_TIMEOUT &&
                st.cursor != null
            if (!canResume) return false
            val retrySubId = controller.retry() ?: return false
            val retryCursor = (controller.state.value as? GroupLoadingState.Retrying)?.cursor
                ?: return false
            Pair(retrySubId, retryCursor)
        }

        // Register subscription for O(1) lookup
        loadingRegistry.registerSubscription(subscriptionId, controller)

        // Update legacy flags
        updateLegacyFlags(groupId, controller.state.value)

        // Continue the relay scan from the oldest message actually in memory, never from a
        // cursor that lags behind it. Two ways the cursor falls behind the in-memory boundary:
        // disk-first pagination prepends older cached pages without advancing the controller,
        // and a mux delivery before the pagination sub leaves the tracker at Long.MAX_VALUE.
        // In both cases firing the REQ at the stale cursor re-requests an already-covered window
        // (every event dedups away), so the loader reads it as "no more" and stops paginating
        // early. Clamping to oldest-in-memory minus one makes page 2 pick up below what we hold.
        val oldestInMemory = _messages.value[groupId]
            ?.filter { it.createdAt > 1_000_000_000L } // guard against epoch-0 outliers
            ?.minOfOrNull { it.createdAt }
        val effectiveUntil = if (oldestInMemory != null) {
            minOf(cursor.untilTimestamp, oldestInMemory - 1L)
        } else {
            cursor.untilTimestamp
        }

        // Private/closed groups gate reads behind NIP-42 AUTH. After a reconnect the
        // socket's auth deferred resets; firing the pagination REQ before re-AUTH lands
        // gets CLOSED "auth-required" and returns zero events, which the UI mistakes for
        // "no older history" and stops paginating early. Wait for re-AUTH first. This is
        // a no-op once AUTH has already succeeded on this socket, and is skipped entirely
        // on relays that never issue a challenge (public groups), so it costs nothing in
        // the common case.
        if (currentClient.requiresAuth() && !currentClient.hasAuthSucceeded()) {
            currentClient.awaitAuthOrTimeout()
        }

        return try {
            currentClient.requestGroupMessages(
                groupId = groupId,
                channel = channel,
                until = effectiveUntil,
                limit = PAGE_SIZE,
                subscriptionId = subscriptionId,
            )
            true
        } catch (e: Throwable) {
            loadingRegistry.unregisterSubscription(subscriptionId)
            controller.handleSendFailure(subscriptionId)
            updateLegacyFlags(groupId, controller.state.value)
            false
        }
    }

    suspend fun fetchGroupMessageById(groupId: String, messageId: String) {
        clientForGroup(groupId)?.requestGroupMessageById(groupId, messageId)
    }

    /**
     * A msg_ subscription's initial load returned/CLOSED while the relay still needs NIP-42
     * AUTH. Hold the load in InitialLoading (skeletons) instead of settling it to a false
     * empty result, because resubscribeAfterAuth replays the read once AUTH completes. This
     * removes the "open a private group from the homepage" flicker, where the first read
     * races the relay's auth-required CLOSE. Returns true if the load was held (caller skips
     * the normal EOSE settle). Unregisters the dead sub so the registry doesn't accumulate.
     */
    suspend fun holdInitialLoadForReauth(subscriptionId: String): Boolean {
        val controller = loadingRegistry.findBySubscription(subscriptionId) ?: return false
        val held = controller.holdInitialLoadForReauth(subscriptionId)
        if (held) {
            _groupsAwaitingAuthRead.update { it + controller.id }
            loadingRegistry.unregisterSubscription(subscriptionId)
        }
        return held
    }

    /**
     * Called when EOSE is received for a subscription.
     * Delegates to the state machine for proper state transitions.
     * CRITICAL: This must be called from a coroutine context to ensure
     * proper ordering with message tracking.
     */
    /**
     * Handle an EOSE from [sourceRelayUrl]'s socket. Passing the source relay
     * explicitly (rather than reverse-mapping from the sub ID) avoids
     * mis-attributing a late EOSE from a torn-down relay to whichever relay is
     * currently primary — previously such a misattribution could mark the
     * current relay as fully fetched and prune its groups even though it had
     * never received its own EOSE.
     */
    suspend fun handleEoseSuspend(subscriptionId: String, sourceRelayUrl: String): Boolean {
        if (subscriptionId == "group-list" || subscriptionId.startsWith("group-list-")) {
            // Reject obviously-stale frames: a relay-specific sub ID must
            // match the source socket. Legacy "group-list" (no hash) is
            // single-relay and trusted as-is.
            if (subscriptionId.startsWith("group-list-") &&
                subscriptionId != "group-list-${sourceRelayUrl.hashCode().toUInt()}"
            ) {
                return true
            }
            val normalizedRelay = sourceRelayUrl.normalizeRelayUrl()
            _completeGroupLoadRelays.update { it + normalizedRelay }
            _loadingRelays.update { it - normalizedRelay }
            loadingWatchdogs.remove(normalizedRelay)?.cancel()
            val now = epochSeconds()
            val isFull = pendingFullFetchRelays.remove(normalizedRelay) != null
            if (isFull) {
                // Save the dedicated full-list timestamp so hasFullGroupListBeenFetched()
                // returns true after an app restart.
                _fullGroupListFetchedRelays.update { it + normalizedRelay }
                try {
                    SecureStorage.saveFullGroupListEoseTimestamp(normalizedRelay, now)
                } catch (_: Exception) {}
                // Prune stale groups: keep only those seen in this fetch, plus joined
                // groups (which become orphans the user can manually forget).
                val seenIds = pendingFetchSeenGroups.remove(normalizedRelay) ?: emptySet()
                val joinedIds = _joinedGroupsByRelay.value[normalizedRelay] ?: emptySet()
                _groupsByRelay.update { current ->
                    val pruned = (current[normalizedRelay] ?: emptyList())
                        .filter { it.id in seenIds || it.id in joinedIds }
                    current + (normalizedRelay to pruned)
                }
            }
            try {
                SecureStorage.saveGroupListEoseTimestamp(normalizedRelay, now)
            } catch (_: Exception) {}
            refreshMuxSubscriptionsForRelay(normalizedRelay)
            return true
        }
        awaitTrackingForSubscription(subscriptionId)
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

    // Incremented synchronously (before scope.launch) so EOSE always sees the full count.
    private val pendingTrackCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun trackMessageForSubscription(subscriptionId: String, timestamp: Long, eventId: String) {
        pendingTrackCounts.update { map ->
            map + (subscriptionId to ((map[subscriptionId] ?: 0) + 1))
        }
        scope.launch {
            try {
                loadingRegistry.trackMessage(subscriptionId, timestamp, eventId)
            } finally {
                pendingTrackCounts.update { map ->
                    val n = map[subscriptionId] ?: return@update map
                    if (n <= 1) map - subscriptionId else map + (subscriptionId to (n - 1))
                }
            }
        }
    }

    private suspend fun awaitTrackingForSubscription(subscriptionId: String) {
        pendingTrackCounts.first { (it[subscriptionId] ?: 0) == 0 }
    }

    /**
     * Check if a subscription is managed by the loading registry.
     */
    suspend fun isPaginationSubscription(subscriptionId: String): Boolean = loadingRegistry.findBySubscription(subscriptionId) != null

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
                subscriptionId = subscriptionId,
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
    suspend fun getLoadingState(groupId: String): GroupLoadingState = loadingRegistry.getController(groupId).state.value

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
     * Like [resetLoadingForGroups] but PRESERVES pagination cursors for groups that
     * are mid-pagination (see [GroupLoadingController.handleReconnect]). Does NOT touch
     * the mux tracker (the caller clears it separately). Use on a re-AUTH re-subscribe so
     * a periodic AUTH challenge doesn't reset an actively-scrolled group to Idle, which
     * would re-fire the initial load with `until = oldest - 1`, jump to the floor, inject
     * ancient events at the top and mark the group Exhausted.
     */
    suspend fun resetLoadingForGroupsPreservingCursor(groupIds: List<String>) {
        loadingRegistry.handleReconnectForGroups(groupIds)
    }

    /**
     * Re-subscribe opened groups after a reconnect while PRESERVING pagination
     * progress (cursor). Clears the mux tracker so the live feed is re-sent, but
     * keeps how far back the user has scrolled so the next page resumes from the
     * same frontier instead of jumping to the oldest message and going Exhausted.
     */
    suspend fun handleReconnectForGroups(groupIds: List<String>) {
        loadingRegistry.handleReconnectForGroups(groupIds)
        groupIds.mapNotNull { getRelayForGroup(it) }.distinct().forEach {
            muxTracker.clearRelay(it)
        }
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
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
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

            // p-tag the author of the message being replied to (NIP-10/22). Without
            // it the recipient is only classified as "replied to" when they happen
            // to have the parent message cached locally, so replies silently miss
            // the per-group "mentions & replies only" notification filter (#70).
            if (replyToMessageId != null) {
                val parentAuthor = findMessageByIdAcrossGroups(replyToMessageId)?.second?.pubkey
                if (parentAuthor != null &&
                    parentAuthor != pubKey &&
                    tags.none { it.size >= 2 && it[0] == "p" && it[1] == parentAuthor }
                ) {
                    tags.add(listOf("p", parentAuthor))
                }
            }

            // Add extra tags (e.g. NIP-68 imeta tags from media uploads), dedup by content
            extraTags.forEach { tag -> if (tag !in tags) tags.add(tag) }

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9,
                tags = tags,
                content = processedContent,
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

            // Optimistic insert: show the message immediately with a Sending status,
            // before the relay round-trip. The relay echo for this id is deduped by
            // messageIdIndex so it never double-inserts; delivery is confirmed by the
            // OK in deliverMessage(), not the echo.
            insertOwnMessage(
                groupId,
                NostrGroupClient.NostrMessage(
                    id = eventId,
                    pubkey = signedEvent.pubkey,
                    content = signedEvent.content,
                    createdAt = signedEvent.createdAt,
                    kind = signedEvent.kind,
                    tags = signedEvent.tags,
                ),
            )
            _messageStatus.update { it + (eventId to MessageStatus.Sending) }

            // Deliver on the manager scope so a group switch or screen exit does not
            // cancel the in-flight send (viewModelScope would). Status resolves async.
            scope.launch { deliverMessage(groupId, message, eventId) }
            Result.Success(Unit)
        } catch (e: Throwable) {
            // Signing/build failure: no optimistic message was inserted yet, so
            // surface this as a real error (the composer restores the draft).
            Result.Error(AppError.Group.SendFailed(groupId, e))
        }
    }

    /**
     * Insert one of the local user's own messages into [_messages] immediately,
     * reusing [messageIdIndex] so the relay's later echo of the same id is deduped.
     */
    private fun insertOwnMessage(groupId: String, message: NostrGroupClient.NostrMessage) {
        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: emptyList()
            val index = messageIdIndex.getOrPut(groupId) { current.mapTo(mutableSetOf()) { it.id } }
            if (!index.add(message.id)) return@update currentMap
            currentMap + (groupId to (current + message).sortedBy { it.createdAt })
        }
        touchGroupRecency(groupId)
        cacheMessages(groupId, listOf(message))
    }

    /**
     * Publish an optimistic message and resolve its status. On timeout/network
     * error the message is queued for auto-retry and stays in [MessageStatus.Sending];
     * only a relay rejection marks it [MessageStatus.Failed].
     */
    private suspend fun deliverMessage(groupId: String, eventJson: String, eventId: String) {
        val client = clientForGroup(groupId)
        if (client == null) {
            // No socket yet: queue so it retries on reconnect; keep it Sending.
            pendingEventManager?.queueEvent(eventJson, eventId, groupId)
            return
        }
        when (val result = client.sendAndAwaitOk(eventJson, eventId)) {
            is org.nostr.nostrord.network.PublishResult.Success -> markDelivered(eventId)
            is org.nostr.nostrord.network.PublishResult.Rejected ->
                markFailed(eventId, groupId, eventJson, result.reason)
            is org.nostr.nostrord.network.PublishResult.Timeout ->
                pendingEventManager?.queueEvent(eventJson, eventId, groupId)
            is org.nostr.nostrord.network.PublishResult.Error ->
                pendingEventManager?.queueEvent(eventJson, eventId, groupId)
        }
    }

    private fun markDelivered(eventId: String) {
        _messageStatus.update { it - eventId }
    }

    private fun markFailed(eventId: String, groupId: String, eventJson: String, reason: String) {
        _messageStatus.update { it + (eventId to MessageStatus.Failed(reason, groupId, eventJson)) }
    }

    /** Re-send a previously failed own message using its stored signed JSON. */
    fun retrySend(eventId: String) {
        val failed = _messageStatus.value[eventId] as? MessageStatus.Failed ?: return
        _messageStatus.update { it + (eventId to MessageStatus.Sending) }
        scope.launch { deliverMessage(failed.groupId, failed.eventJson, eventId) }
    }

    /** Drop a failed own message from the chat (user dismissed it). */
    fun dismissFailed(groupId: String, eventId: String) {
        _messageStatus.update { it - eventId }
        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: return@update currentMap
            val filtered = current.filterNot { it.id == eventId }
            if (filtered.size == current.size) currentMap else currentMap + (groupId to filtered)
        }
        messageIdIndex[groupId]?.remove(eventId)
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
        signEvent: suspend (Event) -> Event,
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
                content = emoji,
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
            handleReaction(
                NostrGroupClient.NostrReaction(
                    id = eventId,
                    pubkey = pubKey,
                    emoji = emoji,
                    emojiUrl = null,
                    targetEventId = targetEventId,
                    createdAt = event.createdAt,
                ),
                immediate = true,
            )

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
     * Delete a message from a group.
     *
     * The deletion kind is chosen by who is deleting whose message:
     * - Authors deleting their own message use kind 5 (NIP-09 standard deletion).
     * - Admins deleting another member's message use kind 9005 (NIP-29
     *   delete-event moderation action). NIP-29 relays only honor a kind 5 from
     *   the author; removing someone else's message requires the admin kind 9005.
     *
     * Sends the deletion event to the relay; the relay broadcasts it and
     * handleDeletion() removes it from local state when received back.
     */
    suspend fun deleteMessage(
        groupId: String,
        messageId: String,
        pubKey: String,
        signEvent: suspend (Event) -> Event,
    ): Result<Unit> {
        val currentClient = clientForGroup(groupId)
            ?: return Result.Error(AppError.Network.Disconnected(""))
        val messageAuthor = _messages.value[groupId]?.firstOrNull { it.id == messageId }?.pubkey
        val deletionKind = deletionKindFor(
            isOwnMessage = messageAuthor == pubKey,
            isAdmin = isGroupAdmin(groupId, pubKey),
        )
        return try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = deletionKind,
                tags = listOf(
                    listOf("h", groupId),
                    listOf("e", messageId),
                ),
                content = "",
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
     * Rebuild and persist the group metadata snapshot for [relayUrl]: every joined group
     * (the rail / My groups) plus a capped set of the most recently seen non-joined groups,
     * so discovery cards on relays you're already on also render their name/avatar instantly
     * on the next launch. Bounded by [GROUP_SNAPSHOT_EXTRA_CAP] to keep the blob small.
     * No-op when [currentPubkey] is not set (unauthenticated state).
     */
    private fun persistJoinedGroupMetadataSnapshot(relayUrl: String) {
        val pubKey = currentPubkey ?: return
        val normalized = relayUrl.normalizeRelayUrl()
        val joinedIds = _joinedGroupsByRelay.value[normalized] ?: emptySet()
        val all = _groupsByRelay.value[normalized] ?: emptyList()
        val joined = all.filter { it.id in joinedIds }
        val others = all.filter { it.id !in joinedIds }.takeLast(GROUP_SNAPSHOT_EXTRA_CAP)
        val snapshot = joined + others
        try {
            SecureStorage.saveJoinedGroupMetadata(pubKey, normalized, json.encodeToString(groupMetadataListSerializer, snapshot))
        } catch (_: Exception) {}
    }

    /**
     * Handle incoming group metadata.
     * Updates the live flow for the current relay AND persists the joined-group snapshot
     * so returning to this relay later is instant without a network re-fetch.
     */
    fun handleGroupMetadata(metadata: GroupMetadata, relayUrl: String) {
        if (metadata.id in deletedGroupIds) return
        val normalized = relayUrl.normalizeRelayUrl()
        pendingFetchSeenGroups[normalized]?.add(metadata.id)
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
     * Restore the group metadata snapshot for fast startup display. Reads the pubkey-scoped
     * cache written by [persistJoinedGroupMetadataSnapshot] — the joined groups plus a capped
     * set of recently-seen others, a small, bounded dataset.
     * Does NOT restore [_fullGroupListFetchedRelays]: this snapshot is not a full list fetch,
     * so OTHER GROUPS still triggers a network fetch when opened.
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
                } catch (_: Exception) {
                    null
                }
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
     * One group's members/admins/roles plus each list's source-event timestamp. The
     * timestamps are persisted so the in-memory staleness guards survive a restart: a
     * re-delivered same-or-older event after reconnect stays a no-op, and only a genuinely
     * newer event replaces the cached list.
     */
    @Serializable
    private data class MembershipSnapshot(
        val members: List<String> = emptyList(),
        val admins: List<String> = emptyList(),
        val roles: List<RoleDefinition> = emptyList(),
        val memberTs: Long = 0L,
        val adminTs: Long = 0L,
        val roleTs: Long = 0L,
    )

    private val membershipSnapshotSerializer =
        MapSerializer(String.serializer(), MembershipSnapshot.serializer())

    /**
     * Persist members/admins/roles for every known group as one per-account blob. Cheap and
     * infrequent: only the rare kind:39001/39002/39003 handlers call it, and only when a list
     * actually changed. No-op when [currentPubkey] is unset (unauthenticated state).
     */
    private fun persistGroupMembershipSnapshot() {
        val pubKey = currentPubkey ?: return
        val groupIds = _groupMembers.value.keys + _groupAdmins.value.keys + _groupRoles.value.keys
        if (groupIds.isEmpty()) return
        val snapshot =
            groupIds.associateWith { id ->
                MembershipSnapshot(
                    members = _groupMembers.value[id] ?: emptyList(),
                    admins = _groupAdmins.value[id] ?: emptyList(),
                    roles = _groupRoles.value[id] ?: emptyList(),
                    memberTs = memberEventTimestamps[id] ?: 0L,
                    adminTs = adminEventTimestamps[id] ?: 0L,
                    roleTs = roleEventTimestamps[id] ?: 0L,
                )
            }
        try {
            SecureStorage.saveGroupMembershipFor(pubKey, json.encodeToString(membershipSnapshotSerializer, snapshot))
        } catch (_: Exception) {
        }
    }

    /**
     * Hydrate members/admins/roles from the per-account cache before sockets open, so opening a
     * previously-seen group shows its member list with no spinner. Live data already in memory
     * wins over the cache (existing keys are kept), and the seeded timestamps only advance the
     * staleness guards, never roll them back.
     */
    fun restoreGroupMembershipFromStorage(pubkey: String) {
        val raw = SecureStorage.loadGroupMembershipFor(pubkey) ?: return
        val snapshot =
            try {
                json.decodeFromString(membershipSnapshotSerializer, raw)
            } catch (_: Exception) {
                return
            }
        if (snapshot.isEmpty()) return
        val members = mutableMapOf<String, List<String>>()
        val admins = mutableMapOf<String, List<String>>()
        val roles = mutableMapOf<String, List<RoleDefinition>>()
        snapshot.forEach { (id, snap) ->
            if (snap.members.isNotEmpty()) {
                members[id] = snap.members
                memberEventTimestamps[id] = maxOf(memberEventTimestamps[id] ?: 0L, snap.memberTs)
            }
            if (snap.admins.isNotEmpty()) {
                admins[id] = snap.admins
                adminEventTimestamps[id] = maxOf(adminEventTimestamps[id] ?: 0L, snap.adminTs)
            }
            if (snap.roles.isNotEmpty()) {
                roles[id] = snap.roles
                roleEventTimestamps[id] = maxOf(roleEventTimestamps[id] ?: 0L, snap.roleTs)
            }
        }
        // `cached + live` so any group already updated this session keeps its live value.
        if (members.isNotEmpty()) _groupMembers.update { members + it }
        if (admins.isNotEmpty()) _groupAdmins.update { admins + it }
        if (roles.isNotEmpty()) _groupRoles.update { roles + it }
    }

    /**
     * Prepend one page of older messages from the persistent cache, ahead of the current oldest
     * in-memory message. Returns true when it added something (the relay is then skipped for this
     * scroll), false when local history is exhausted so the caller paginates the relay. Reads
     * through the same dedup index as the live path; the relay's `until` cursor naturally
     * continues from the new, older in-memory boundary once the disk runs dry.
     */
    private suspend fun loadOlderFromCache(groupId: String): Boolean {
        val account = currentPubkey ?: return false
        val oldestInMemory = _messages.value[groupId]?.minOfOrNull { it.createdAt } ?: return false
        val olderPage =
            try {
                cacheStore.loadBefore(account, groupId, oldestInMemory, PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The web IndexedDB store can fail with a raw JS error (a TypeError), which is NOT
                // a Kotlin Exception. Catching only Exception let it escape loadMoreMessages and
                // kill the pagination coroutine, freezing scroll-back. Treat any cache failure as
                // "nothing cached" and fall through to the relay.
                return false
            }
        if (olderPage.isEmpty()) return false
        val restored = olderPage.map { it.toNostrMessage() }
        var added = false
        _messages.update { current ->
            val existing = current[groupId] ?: emptyList()
            val index = messageIdIndex.getOrPut(groupId) { existing.mapTo(mutableSetOf()) { it.id } }
            val fresh = restored.filter { index.add(it.id) }
            if (fresh.isEmpty()) return@update current
            added = true
            current + (groupId to (existing + fresh).sortedBy { it.createdAt })
        }
        return added
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
        if (isRecentlyLeft(members.groupId)) {
            _loadingMembers.value = _loadingMembers.value - members.groupId
            return emptyList()
        }
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
            persistGroupMembershipSnapshot()
        }
        _loadingMembers.value = _loadingMembers.value - members.groupId

        val self = currentPubkey
        val selfNowMember = self != null && self in members.members
        val selfWasAbsent = currentMembers?.contains(self) != true

        // The relay listing us in 39002 is the ground-truth approval signal. If we were gated
        // (locally pending OR persisted/CLOSED as restricted) and have just transitioned into the
        // member list, we gained read access this moment: clear the gate AND re-arm the live
        // subscription. Keyed on the restricted marker too, not only _pendingApprovalSince, because
        // some join paths (invite link / deep link) never set the pending marker, yet the relay
        // still CLOSED our pre-approval mux as "restricted" — without the re-arm the admin's later
        // messages never reach us until an app restart.
        if (selfNowMember &&
            selfWasAbsent &&
            (members.groupId in _pendingApprovalSince.value || members.groupId in _restrictedGroups.value)
        ) {
            onApprovalDetected(members.groupId)
        } else if (selfNowMember && members.groupId in _restrictedGroups.value) {
            // Confirmed membership with no live transition (e.g. a stale 7-day restricted marker
            // restored from SecureStorage on cold start): just drop the "Private group / invite
            // code" placeholder. The mux is built fresh this session, so no re-arm is needed.
            clearGroupRestricted(members.groupId)
        }

        // Self-heal a stale durable left marker: the relay lists us as a member AND we are joined
        // (our own kind:10009), so we genuinely rejoined — clear the marker so the group stops
        // reading NONE and rejoins the mux. Gated on `joined`: the B2 case (we left, the relay still
        // lists us) leaves us NOT joined, so the marker stays and the group keeps reading "left".
        if (self != null &&
            self in members.members &&
            members.groupId in _leftGroups.value &&
            _joinedGroupsByRelay.value.values.any { members.groupId in it }
        ) {
            _leftGroups.update { it - members.groupId }
            getRelayForGroup(members.groupId)?.let { relay ->
                currentPubkey?.let { pk ->
                    try {
                        SecureStorage.removeLeftGroupForRelay(pk, relay.normalizeRelayUrl(), members.groupId)
                    } catch (_: Exception) {}
                }
            }
        }

        return members.members
    }

    // Pre-approval CLOSED("restricted") drove the loader to Exhausted. Reset it (so
    // startInitialLoad is no longer a no-op) and re-run the initial history load through
    // the controller now that we have read access: refreshMux only re-subscribes the live
    // tail, so without this the group stays on "No messages yet" until someone posts. We go
    // through requestGroupMessages (the state-machine path), NOT a raw _messages fetch /
    // dedup eviction, which is what broke pagination before.
    private fun onApprovalDetected(groupId: String) {
        _pendingApprovalSince.update { it - groupId }
        clearGroupRestricted(groupId)
        scope.launch {
            try {
                loadingRegistry.getController(groupId).reset()
            } catch (_: Exception) {}
            val relayUrl = getRelayForGroup(groupId)
            if (relayUrl != null) {
                try {
                    // Pre-approval the relay CLOSED our batched mux subs with "restricted"; a relay
                    // CLOSE does not update muxTracker, so it still believes the live chat sub is
                    // active and a same-state refresh would be skipped (needsRefresh == false),
                    // leaving the tail dead until an app restart. Invalidate so the re-subscribe
                    // actually fires now that we have read access.
                    muxTracker.clearRelay(relayUrl)
                    refreshMuxSubscriptionsForRelay(relayUrl)
                } catch (_: Exception) {}
            }
            try {
                requestGroupMessages(groupId)
            } catch (_: Exception) {}
        }
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
        if (isRecentlyLeft(admins.groupId)) return
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
            persistGroupMembershipSnapshot()
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
     * Request pending join requests (kind 9021 + 9022) for a group.
     * Use when an admin opens a closed group — the standard chat REQ caps at 50
     * events and buries 9021s under recent chat. Debounced by [shouldRequest].
     */
    suspend fun requestPendingJoinRequests(groupId: String): Boolean {
        if (!shouldRequest(groupId, "joinreq")) return true
        val currentClient = clientForGroup(groupId) ?: return false
        currentClient.requestPendingJoinRequests(groupId)
        return true
    }

    /**
     * Handle incoming group roles (kind 39003)
     */
    fun handleGroupRoles(roles: GroupRoles, createdAt: Long = 0L) {
        if (isRecentlyLeft(roles.groupId)) return
        val existing = roleEventTimestamps[roles.groupId] ?: 0L
        if (createdAt > 0L && createdAt < existing) {
            connStats?.onStateConflict(roles.groupId)
            return
        }
        if (createdAt > 0L) roleEventTimestamps[roles.groupId] = createdAt
        val currentRoles = _groupRoles.value[roles.groupId]
        if (currentRoles != roles.roles) {
            _groupRoles.value = _groupRoles.value + (roles.groupId to roles.roles)
            persistGroupMembershipSnapshot()
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
    fun isGroupAdmin(groupId: String, pubkey: String): Boolean = pubkey in (_groupAdmins.value[groupId] ?: emptyList())

    /**
     * Get members for a specific group
     */
    fun getMembersForGroup(groupId: String): List<String> = _groupMembers.value[groupId] ?: emptyList()

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
                    if (targetPubkey in members) {
                        current
                    } else {
                        current + (groupId to members + targetPubkey)
                    }
                }
            }
            9001 -> { // remove-user
                _groupMembers.update { current ->
                    val members = current[groupId] ?: return@update current
                    if (targetPubkey !in members) {
                        current
                    } else {
                        current + (groupId to members - targetPubkey)
                    }
                }
            }
        }
    }

    // Valid message kinds for group events (NIP-29 and related)
    private val validMessageKinds = setOf(
        9, // Chat messages (NIP-29)
        9000, // Group admin: add user
        9001, // Group admin: remove user
        9002, // Group admin: edit metadata (NIP-29)
        9009, // Group admin: create invite (NIP-29)
        9021, // Join request
        9022, // Leave request
        9321, // Zap request (NIP-57)
    )

    // Deletion kinds that remove other events
    private val deletionKinds = setOf(
        5, // Deletion request (NIP-09)
        9003, // Group admin: delete event (NIP-29)
        9005, // Group admin: delete event (NIP-29 moderation)
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
        relayUrl: String? = null,
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
        if (messageId.isBlank()) return null

        // Track before dedup: events overlapping with mux_chat's `since` window
        // arrive on both subs and would otherwise be deducted from the msg_ sub's
        // page count, flipping a full page to Exhausted.
        if (subscriptionId != null) {
            trackMessageForSubscription(subscriptionId, message.createdAt, message.id)
        }

        if (!eventDeduplicator.tryAddSync(messageId)) return null

        val groupId = extractGroupIdFromMessage(rawMsg) ?: return null

        if (isRecentlyLeft(groupId)) return null

        if (relayUrl != null) {
            _latestMessageRelayByGroup.update { it + (groupId to relayUrl) }
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
        // Guard: currentPubkey is nulled in clear() before a switch. Any flush
        // that arrives after clear() but before setCurrentPubkey() is discarded
        // here to prevent cross-account message contamination.
        if (currentPubkey == null) return

        val chatMessages = messages.filter { it.kind == 9 }
        if (chatMessages.isNotEmpty()) {
        }
        // Record burst for adaptive tuning
        adaptiveConfig?.recordEventBurst(messages.size)

        var capturedNew: List<NostrGroupClient.NostrMessage>? = null
        _messages.update { currentMap ->
            val current = currentMap[groupId] ?: emptyList()
            // Persistent index: O(1) per lookup, no rebuild.
            val index = messageIdIndex.getOrPut(groupId) {
                current.mapTo(mutableSetOf()) { it.id }
            }
            // index.add() returns true if new → filters and indexes in one pass.
            val newMessages = messages.filter { index.add(it.id) }
            if (newMessages.isEmpty()) return@update currentMap
            capturedNew = newMessages

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

        capturedNew?.let { onNewMessagesFlushed?.invoke(groupId, it) }
        capturedNew?.let { cacheMessages(groupId, it) }
        touchGroupRecency(groupId)
        evictGroupMessagesBeyondCap()
    }

    /** Mark [groupId] most-recently-used for whole-group eviction ordering. */
    private fun touchGroupRecency(groupId: String) {
        groupMessageRecency.remove(groupId)
        groupMessageRecency.add(groupId)
    }

    private val tagsSerializer = ListSerializer(ListSerializer(String.serializer()))

    private fun NostrGroupClient.NostrMessage.toCachedMsg(groupId: String): CachedMsg = CachedMsg(
        id = id,
        groupId = groupId,
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        content = content,
        tagsJson = json.encodeToString(tagsSerializer, tags),
    )

    private fun CachedMsg.toNostrMessage(): NostrGroupClient.NostrMessage = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = pubkey,
        content = content,
        createdAt = createdAt,
        kind = kind,
        tags = try {
            json.decodeFromString(tagsSerializer, tagsJson)
        } catch (_: Exception) {
            emptyList()
        },
    )

    /** Write new messages through to the persistent history cache (fire-and-forget). */
    private fun cacheMessages(
        groupId: String,
        messages: List<NostrGroupClient.NostrMessage>,
    ) {
        val account = currentPubkey ?: return
        if (messages.isEmpty()) return
        scope.launch {
            try {
                cacheStore.upsertMessages(account, groupId, messages.map { it.toCachedMsg(groupId) })
            } catch (_: Exception) {
            }
        }
        scheduleCacheEviction()
    }

    /**
     * One-time seeding of the persistent cache from the legacy per-group message blobs
     * (the last-100 snapshot in [SecureStorage]). Without this, a previously-seen group would
     * hydrate empty right after upgrade until the write-through repopulates it; with it, the
     * already-saved history shows on the first open. Idempotent (guarded by a per-account flag)
     * and scoped to the joined groups (already restored at cold start), since the KV blobs
     * aren't enumerable.
     */
    fun migrateMessageBlobsToCache(pubkey: String) {
        if (pubkey.isBlank()) return
        scope.launch {
            if (SecureStorage.isMessageBlobMigratedFor(pubkey)) return@launch
            val groupIds = _joinedGroupsByRelay.value.values.flatten().toSet()
            if (groupIds.isEmpty()) return@launch
            for (groupId in groupIds) {
                val blob = SecureStorage.getMessagesForGroup(pubkey, groupId) ?: continue
                val messages = parseMessagesBlob(blob)
                if (messages.isEmpty()) continue
                try {
                    cacheStore.upsertMessages(pubkey, groupId, messages.map { it.toCachedMsg(groupId) })
                } catch (_: Exception) {
                }
            }
            SecureStorage.setMessageBlobMigratedFor(pubkey)
        }
    }

    private var cacheEvictionJob: Job? = null

    /** Trim the persistent cache to [CACHE_BYTE_BUDGET], debounced so a write burst evicts once. */
    private fun scheduleCacheEviction() {
        val account = currentPubkey ?: return
        cacheEvictionJob?.cancel()
        cacheEvictionJob =
            scope.launch {
                delay(CACHE_EVICTION_DEBOUNCE_MS)
                try {
                    cacheStore.evictToByteBudget(account, CACHE_BYTE_BUDGET)
                } catch (_: Exception) {
                }
            }
    }

    /**
     * Render a previously-seen group instantly from the persistent cache on open, before any
     * relay round-trip. Merges the cached page into [_messages] via the same dedup index as the
     * live path, so the subsequent network refresh (stale-while-revalidate) never double-inserts.
     */
    private suspend fun hydrateMessagesFromCache(groupId: String) {
        val account = currentPubkey ?: return
        val cached =
            try {
                cacheStore.loadLatest(account, groupId, CACHE_HYDRATE_LIMIT)
            } catch (_: Exception) {
                return
            }
        if (cached.isEmpty()) return
        val restored = cached.map { it.toNostrMessage() }
        _messages.update { current ->
            val existing = current[groupId] ?: emptyList()
            val index = messageIdIndex.getOrPut(groupId) { existing.mapTo(mutableSetOf()) { it.id } }
            val fresh = restored.filter { index.add(it.id) }
            if (fresh.isEmpty()) return@update current
            current + (groupId to (existing + fresh).sortedBy { it.createdAt })
        }
        touchGroupRecency(groupId)
    }

    /**
     * Evict the least-recently-used groups' in-memory messages once more than
     * [MAX_GROUPS_IN_MEMORY] groups are loaded, never the active one. Each evicted group is
     * persisted first so reopening hydrates instantly from disk, then its list, dedup index
     * and reactions are dropped together (keeping list and index consistent — a partial trim
     * would not). The live subscription refills the tail on reopen.
     */
    private fun evictGroupMessagesBeyondCap() {
        val present = _messages.value
        val overflow = present.size - MAX_GROUPS_IN_MEMORY
        if (overflow <= 0) return
        val active = _activeGroupId
        val toEvict =
            groupMessageRecency
                .asSequence()
                .filter { it != active && present.containsKey(it) }
                .take(overflow)
                .toSet()
        if (toEvict.isEmpty()) return
        toEvict.forEach { saveMessagesToStorage(it) }
        _messages.update { it - toEvict }
        toEvict.forEach { groupId ->
            val droppedIds = messageIdIndex.remove(groupId)
            if (!droppedIds.isNullOrEmpty()) {
                _reactions.update { it - droppedIds }
            }
            groupMessageRecency.remove(groupId)
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

        // Evict from the persistent cache too. Without this, a deleted event (e.g. a revoked
        // kind:9009 invite code) rehydrates from cache on the next cold open — the relay no longer
        // serves it and the 9005 delete is never cached, so nothing re-removes it. Fire-and-forget.
        val account = currentPubkey
        if (account != null) {
            scope.launch {
                try {
                    cacheStore.deleteByIds(account, eventIdsToDelete)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Get messages for a specific group
     */
    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> = _messages.value[groupId] ?: emptyList()

    fun findMessageByIdAcrossGroups(messageId: String): Pair<String, NostrGroupClient.NostrMessage>? {
        for ((groupId, msgs) in _messages.value) {
            val msg = msgs.firstOrNull { it.id == messageId } ?: continue
            return groupId to msg
        }
        return null
    }

    /**
     * Returns the [NostrGroupClient.NostrMessage.createdAt] of the newest message in the
     * in-memory list for [groupId], or null if the group has no messages loaded yet.
     * Used by gap detection to check whether events near the cursor arrived after reconnect.
     */
    fun getLatestMessageTimestamp(groupId: String): Long? = _messages.value[groupId]?.maxOfOrNull { it.createdAt }

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
        immediate: Boolean = false,
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
            reactors = currentReactors + reactorPubkey,
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
                    } else {
                        info
                    }
                } else {
                    info
                }
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

        // Without clearing this, an in-flight full fetch on the previous relay
        // leaves that relay flagged "pending"; if its late EOSE then arrives
        // the relay would be marked complete with an empty seen-set, pruning
        // its OTHER GROUPS to just the joined entries. Cleared here (alongside
        // [clear] which covers logout) so every relay switch resets the bookkeeping.
        pendingFullFetchRelays.clear()
        pendingFetchSeenGroups.clear()

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
        _joinedGroupsByRelay.update { current -> current - normalized }
    }

    /**
     * Clear all state including the per-relay group metadata cache.
     * Use on logout or full account reset.
     */
    suspend fun clear() {
        // Null out currentPubkey BEFORE flushing so any in-flight flush that
        // fires during or after the clear is discarded by flushBatchToState's
        // null guard. This prevents stale messages from a previous account
        // writing into _messages after a switch.
        currentPubkey = null
        eventOrderingBuffer.flushAll()
        _completeGroupLoadRelays.value = emptySet()
        _fullGroupListFetchedRelays.value = emptySet()
        _loadingRelays.value = emptySet()
        // Snapshot before cancelling: a watchdog already past its delay may call
        // loadingWatchdogs.remove() from its finally during iteration.
        loadingWatchdogs.values.toList().forEach { it.cancel() }
        loadingWatchdogs.clear()
        // Without clearing this, an in-flight full fetch from the previous account
        // leaves the relay flagged as "pending", and the new account's expand of
        // OTHER GROUPS is silently skipped by requestFullGroupListForRelay's guard.
        pendingFullFetchRelays.clear()
        pendingFetchSeenGroups.clear()
        _groupsByRelay.value = emptyMap()
        _openedGroupIds.value = emptySet()
        // Messages from the previous account must NOT survive into the new
        // session. flushBatchToState rebuilds messageIdIndex from _messages,
        // so leaving an event id behind makes the next account's catch-up
        // REQ silently drop it — newMessages stays empty, onNewMessagesFlushed
        // never fires, and UnreadManager never sees the event. Cleared here
        // (not in clearForRelaySwitch) because relay switch must preserve the
        // user's message history for the active account.
        _messages.value = emptyMap()
        groupMessageRecency.clear()
        _messageStatus.value = emptyMap()
        _latestMessageRelayByGroup.value = emptyMap()
        // Joined-group sets and restricted-group markers are pubkey-scoped.
        // Leaving them in place lets isJoined() / isRestricted() report the
        // PREVIOUS account's data while the new account's flow is still
        // settling. Concretely: a kind:9 buffered in the ordering buffer (or
        // arriving on a still-open socket) flushes a few hundred ms after the
        // switch, UnreadManager.isJoined() returns true on the stale set, and
        // the notification lands in the new account's history.
        _joinedGroupsByRelay.value = emptyMap()
        _restrictedGroups.value = emptyMap()
        _leftGroups.value = emptyMap()
        // Per-group state from the previous account must be cleared too.
        // Without this, an account switch leaves stale kind:39002/39001/39003
        // caches that don't include the new account's pubkey, so opening a
        // closed group falls straight into the "Awaiting admin approval" branch
        // in GroupScreen. memberEventTimestamps must also be reset, otherwise a
        // fresh 39002 with the same createdAt is treated as stale and the cache
        // never updates.
        _groupMembers.value = emptyMap()
        _groupAdmins.value = emptyMap()
        _groupRoles.value = emptyMap()
        _loadingMembers.value = emptySet()
        memberEventTimestamps.clear()
        adminEventTimestamps.clear()
        roleEventTimestamps.clear()
        _pendingApprovalSince.value = emptyMap()
        recentlyLeftAt.clear()
        // Pubkey-scoped: the next account reloads its own set from storage in loadJoinedGroupsFromStorage.
        deletedGroupIds.clear()
        // The mux tracker remembers what was last sent per relay. Without
        // clearing it on identity swap, refreshMuxSubscriptionsForRelay can
        // see "no change" and skip the REQ. Private-group 39002 then never
        // arrives on the new identity's session and the chat is stuck on
        // "Awaiting admin approval" until the user restarts the app.
        muxTracker.clearAll()
        // Same idea for the 2s request cooldown: a recent REQ from the
        // previous account would otherwise block an equivalent REQ from
        // the new account during the swap window.
        recentRequests.clear()
        _activeGroupId = null
        // Reset every per-group loading controller. Without this, controllers
        // left mid-load (InitialLoading / Paginating / HasMore) at logout
        // refuse startInitialLoad() on re-login — it only accepts Idle/Error
        // — so requestGroupMessages returns false silently and the chat
        // stays on "No messages yet" until the process restarts. Affects
        // bunker logins disproportionately because their AUTH path is slow
        // enough to leave more groups mid-load when the logout cancellation
        // fires.
        loadingRegistry.clear()
        clearForRelaySwitch()
    }

    /**
     * Clear joined groups for an account
     */
    fun clearJoinedGroupsForAccount(pubKey: String) {
        SecureStorage.clearAllJoinedGroupsForAccount(pubKey)
        SecureStorage.saveDroppedGroupIds(pubKey, emptySet())
        SecureStorage.clearAllJoinedGroupMetadataForAccount(pubKey)
        SecureStorage.clearGroupMembershipFor(pubKey)
        // Wipe the persistent history cache and let a re-added account re-seed from its blobs.
        SecureStorage.clearMessageBlobMigratedFor(pubKey)
        scope.launch {
            try {
                cacheStore.clearAccount(pubKey)
            } catch (_: Exception) {
            }
        }
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
    /** Parse a persisted message blob (JSON array) into messages; empty on any parse error. */
    private fun parseMessagesBlob(messagesJson: String): List<NostrGroupClient.NostrMessage> = try {
        json.parseToJsonElement(messagesJson).jsonArray.mapNotNull { element ->
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
                    } ?: emptyList(),
                )
            } catch (e: Throwable) {
                null
            }
        }
    } catch (e: Throwable) {
        emptyList()
    }

    fun loadMessagesFromStorage(groupId: String) {
        val pubkey = currentPubkey ?: return
        val messagesJson = SecureStorage.getMessagesForGroup(pubkey, groupId) ?: return

        try {
            val messages = parseMessagesBlob(messagesJson)

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
                touchGroupRecency(groupId)
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
                    add(
                        buildJsonObject {
                            put("id", msg.id)
                            put("pubkey", msg.pubkey)
                            put("content", msg.content)
                            put("createdAt", msg.createdAt)
                            put("kind", msg.kind)
                            put(
                                "tags",
                                buildJsonArray {
                                    msg.tags.forEach { tag ->
                                        add(
                                            buildJsonArray {
                                                tag.forEach { add(it) }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
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

/**
 * Pick the deletion event kind for a group message.
 *
 * - Kind 5 (NIP-09) is the standard deletion an author issues for their own message.
 * - Kind 9005 is the NIP-29 delete-event moderation action; NIP-29 relays only let an
 *   admin remove another member's message through it, never through kind 5.
 *
 * An admin deleting their own message stays on kind 5 (they are the author).
 */
internal fun deletionKindFor(isOwnMessage: Boolean, isAdmin: Boolean): Int = if (!isOwnMessage && isAdmin) 9005 else 5
