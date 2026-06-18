package org.nostr.nostrord.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.auth.parseSignedEventJson
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.ConnectionStats
import org.nostr.nostrord.network.managers.DmConversation
import org.nostr.nostrord.network.managers.DmManager
import org.nostr.nostrord.network.managers.DmMessage
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.LiveCursorStore
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.RelayMetadataManager
import org.nostr.nostrord.network.managers.RelayReconnectScheduler
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.managers.ZapManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.Nip17
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearCurrentRelayUrlFor
import org.nostr.nostrord.storage.getLastActiveAt
import org.nostr.nostrord.storage.isGroupFetchLazy
import org.nostr.nostrord.storage.loadDmLastRead
import org.nostr.nostrord.storage.loadDmMessages
import org.nostr.nostrord.storage.loadDmSyncCursor
import org.nostr.nostrord.storage.loadRelayListFor
import org.nostr.nostrord.storage.saveCurrentRelayUrlFor
import org.nostr.nostrord.storage.saveDmLastRead
import org.nostr.nostrord.storage.saveDmMessages
import org.nostr.nostrord.storage.saveDmSyncCursor
import org.nostr.nostrord.storage.saveGroupFetchLazy
import org.nostr.nostrord.storage.saveRelayListFor
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.urlDecode
import kotlin.concurrent.Volatile

/**
 * Curator whose public kind:10009 seeds the Recommended discovery tab. Mirrored here so the
 * group-list fetch can include the curator's groups in its targeted (known-only) #d request,
 * matching what the Recommended tab needs (HomePageViewModel uses the same pubkey).
 */
private const val DISCOVERY_CURATOR_PUBKEY = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"

// NIP-59 backdates gift-wrap timestamps up to 2 days into the past, so the DM-inbox `since`
// must reach back this far from the sync cursor or recent wraps would be missed on resync.
private const val GIFT_WRAP_BACKDATE_SECONDS = 2L * 24 * 60 * 60

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
    private val zapManager: ZapManager,
    private val unreadManager: UnreadManager,
    private val pendingEventManager: org.nostr.nostrord.network.managers.PendingEventManager? = null,
    private val relayMetadataManager: RelayMetadataManager? = null,
    private val liveCursorStore: LiveCursorStore? = null,
    private val connStats: ConnectionStats = ConnectionStats(),
    private val notificationHistoryStore: org.nostr.nostrord.notifications.NotificationHistoryStore? = null,
    private val notificationSettings: org.nostr.nostrord.settings.NotificationSettings? = null,
    private val scope: CoroutineScope,
) : NostrRepositoryApi {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A gap is suspected when the newest in-memory message is older than the cursor by at least
     * this many seconds. 120 s gives relays reasonable delivery time before we consider the
     * difference a real hole rather than normal clock skew.
     */
    private val GAP_THRESHOLD_S = 120L

    /**
     * Minimum seconds between gap-detection passes for the same relay.
     * Prevents the CLOSE→REQ mux cycle from triggering three successive gap fills.
     */
    private val GAP_DETECTION_COOLDOWN_S = 30L
    private val lastGapDetectionAt = mutableMapOf<String, Long>()

    /** How long the zap modal waits for a payment confirmation, and the receipt poll cadence. */
    private val ZAP_PAYMENT_WATCH_MS = 90_000L
    private val ZAP_PAYMENT_POLL_MS = 3_000L

    /**
     * Epoch-seconds of the last requestGroups() call per relay.
     * Prevents resubscribeAfterAuth from sending a duplicate group-list REQ when
     * connect() already sent one within the last 10 seconds.
     */
    private val lastRequestGroupsAt = mutableMapOf<String, Long>()

    // Caps how many NIP-17 DM decryptions hit the signer at once. A cold load streams
    // every unread kind:1059 (dozens, sometimes 80+), and with a remote signer (NIP-46
    // bunker) each decrypt is a serialized round-trip. Launching them all flooded the
    // signer queue so the group relays' NIP-42 AUTH signs were starved — the relay then
    // closed the unauthenticated socket and private groups never loaded (the more DMs,
    // the worse; absent on main, which has no NIP-17). Bounding the burst leaves the
    // signer free to answer AUTH promptly while DMs decrypt steadily in the background.
    private val dmDecryptSemaphore = Semaphore(3)

    // Upper bound on how long a bunker account holds its DM gift-wrap backlog
    // while the active relay signs its NIP-42 AUTH. awaitAuthOrTimeout returns
    // the instant AUTH completes, so this only caps the wait for relays that
    // turn out to need no AUTH (public). See the gift-wrap handler.
    private val DM_INGEST_AUTH_GRACE_MS = 10_000L

    // After an interactive bunker login, let finishLoginInit's own connect settle
    // before the idempotent signer-ready recovery runs, so the two don't reconnect
    // the primary at once. The recovery only acts if AUTH did not take.
    private val BUNKER_LOGIN_RECOVERY_DELAY_MS = 2_500L

    /**
     * Relays for which a post-AUTH group-list fetch has already been issued this
     * session. resubscribeAfterAuth invalidates the (possibly pre-AUTH, empty-EOSE)
     * full-list marker only on the FIRST auth completion per relay; thereafter the
     * in-session marker is trustworthy, so subsequent re-AUTH challenges fall back
     * to the normal 10s dedup instead of force-refetching the full list every time.
     */
    private val authedGroupListFetchedRelays = mutableSetOf<String>()

    /**
     * Relays for which the UI requested the full OTHER GROUPS list while the
     * corresponding client wasn't yet primary/connected/AUTHed. Drained by
     * [drainFullFetchRequest] from connect()/switchRelay()/resubscribeAfterAuth
     * once the client is ready, so the user-triggered click is honoured
     * without a polling wait.
     */
    private val pendingFullFetchRequests = mutableSetOf<String>()
    private val pendingFullFetchMutex = Mutex()

    /**
     * Relay URL of the group currently open on screen — used to promote reconnect priority.
     * Null when no group is focused (Home screen, settings, etc.).
     */
    private var activeRelayUrl: String? = null

    // Debounced metadata refresh — when multiple mux subs are CLOSED at once
    // (idle drop), only one refreshVisibleUserMetadata() fires.
    private var metadataRefreshJob: kotlinx.coroutines.Job? = null

    // Tracks the intended relay; stale concurrent switchRelay() calls bail out when they see a mismatch.
    private val _targetSwitchRelayUrl = MutableStateFlow<String?>(null)

    // Per-relay bounded message pipelines — prevents unbounded coroutine creation under burst load.
    // Map: relayUrl -> (client, pipeline). The client reference is used to detect reconnects:
    // when a new NostrGroupClient is created for the same URL, the old pipeline is closed and a
    // new one is created, ensuring handleUnifiedMessage always sees the live client.
    private val relayPipelines = mutableMapOf<String, Pair<NostrGroupClient, RelayEventPipeline>>()

    /**
     * Routes an incoming WebSocket frame through the per-relay pipeline.
     * Creates the pipeline on first message; replaces it if the client has changed (reconnect).
     */
    private fun enqueueToRelayPipeline(msg: String, client: NostrGroupClient) {
        val url = client.getRelayUrl()
        val entry = relayPipelines[url]
        val pipeline = if (entry != null && entry.first === client) {
            entry.second
        } else {
            entry?.second?.close()
            RelayEventPipeline(url, scope) { m -> handleUnifiedMessage(m, client) }
                .also { relayPipelines[url] = client to it }
        }
        pipeline.enqueue(msg)
    }

    // Centralised reconnect scheduler for previously-connected pool relays.
    // Only relays in connectedPoolRelays are scheduled for reconnection.
    private val relayReconnectScheduler = RelayReconnectScheduler(
        scope = scope,
        isRelayActive = { relayUrl -> relayUrl in connectedPoolRelays },
        doReconnect = { relayUrl ->
            val alreadyConnected = connectionManager.getClientForRelay(relayUrl) != null
            val client = connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            }
            if (client != null) {
                if (!alreadyConnected) resubscribePoolRelay(relayUrl, client)
                true
            } else {
                false
            }
        },
    )

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Expose connection state
    override val currentRelayUrl: StateFlow<String> = connectionManager.currentRelayUrl
    override val connectionState: StateFlow<ConnectionManager.ConnectionState> = connectionManager.connectionState
    private val _isDiscoveringRelays = MutableStateFlow(false)
    override val isDiscoveringRelays: StateFlow<Boolean> = _isDiscoveringRelays.asStateFlow()
    private val _pendingDeepLinkRelay = MutableStateFlow<String?>(null)
    override val pendingDeepLinkRelay: StateFlow<String?> = _pendingDeepLinkRelay.asStateFlow()

    // Expose group state
    override val groups: StateFlow<List<GroupMetadata>> = groupManager.groups
    override val groupsByRelay: StateFlow<Map<String, List<GroupMetadata>>> = groupManager.groupsByRelay
    override val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = groupManager.messages
    override val messageStatus: StateFlow<Map<String, GroupManager.MessageStatus>> = groupManager.messageStatus
    override val joinedGroups: StateFlow<Set<String>> = groupManager.joinedGroups
    override val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = groupManager.joinedGroupsByRelay
    override val loadingRelays: StateFlow<Set<String>> = groupManager.loadingRelays
    private val _restrictedRelays = MutableStateFlow<Map<String, String>>(emptyMap())
    override val restrictedRelays: StateFlow<Map<String, String>> = _restrictedRelays.asStateFlow()
    override val isLoadingMore: StateFlow<Map<String, Boolean>> = groupManager.isLoadingMore
    override val hasMoreMessages: StateFlow<Map<String, Boolean>> = groupManager.hasMoreMessages
    override val groupStates: StateFlow<Map<String, org.nostr.nostrord.network.managers.GroupLoadingState>> = groupManager.groupStates
    override val groupsAwaitingAuthRead: StateFlow<Set<String>> = groupManager.groupsAwaitingAuthRead

    override suspend fun resetGroupLoadingState(groupId: String) {
        groupManager.resetLoadingForGroups(listOf(groupId))
    }
    override val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = groupManager.reactions

    // NIP-57 zap totals per zapped event id.
    override val zaps: StateFlow<Map<String, ZapManager.ZapInfo>> = zapManager.zaps
    override val groupMembers: StateFlow<Map<String, List<String>>> = groupManager.groupMembers
    override val groupAdmins: StateFlow<Map<String, List<String>>> = groupManager.groupAdmins
    override val groupRoles: StateFlow<Map<String, List<RoleDefinition>>> = groupManager.groupRoles
    override val loadingMembers: StateFlow<Set<String>> = groupManager.loadingMembers
    override val restrictedGroups: StateFlow<Map<String, String>> = groupManager.restrictedGroups

    // Expose auth state
    override val isLoggedIn: StateFlow<Boolean> = sessionManager.isLoggedIn

    // Active account's pubkey, derived from the session swap so it changes on every
    // account switch. Screens key their per-account loading state off this.
    override val activePubkey: StateFlow<String?> =
        ActiveAccountManager.session
            .map { it?.pubkey }
            .stateIn(scope, SharingStarted.Eagerly, ActiveAccountManager.currentPubkey)
    override val isBunkerConnected: StateFlow<Boolean> = sessionManager.isBunkerConnected
    override val isBunkerVerifying: StateFlow<Boolean> = sessionManager.isBunkerVerifying
    override val bunkerState: StateFlow<BunkerState> = sessionManager.bunkerState
    override val authUrl: StateFlow<String?> = sessionManager.authUrl
    override val pendingUnlockAccount: StateFlow<Account?> = sessionManager.pendingUnlock

    override fun clearPendingUnlock() = sessionManager.clearPendingUnlock()

    // Expose metadata state
    override val userMetadata: StateFlow<Map<String, UserMetadata>> = metadataManager.userMetadata
    override val cachedEvents: StateFlow<Map<String, CachedEvent>> = metadataManager.cachedEvents

    // Expose NIP-65 state
    override val userRelayList: StateFlow<List<Nip65Relay>> = outboxManager.userRelayList

    // Expose unread state
    override val unreadCounts: StateFlow<Map<String, Int>> = unreadManager.unreadCounts
    override val latestMessageTimestamps: StateFlow<Map<String, Long>> = unreadManager.latestMessageTimestamps

    // Filtered to relays the UI can actually show (rail's source list:
    // kind:10009 ∪ group-tag relays ∪ current). Without this, joined groups
    // on relays the user can't navigate to would silently inflate the title
    // counter ("(2) Nostrord" with no visible badge anywhere).
    override val unreadByRelay: StateFlow<Map<String, Int>> = combine(
        groupManager.joinedGroupsByRelay,
        unreadManager.unreadCounts,
        outboxManager.kind10009Relays,
        outboxManager.groupTagRelays,
        connectionManager.currentRelayUrl,
    ) { joined, counts, kind10009, groupTags, current ->
        val visible = (kind10009 + groupTags + setOf(current))
            .filter { it.isNotBlank() }
            .map { it.normalizeRelayUrl() }
            .toSet()
        joined
            .filterKeys { it in visible }
            .mapValues { (_, ids) -> ids.sumOf { counts[it] ?: 0 } }
            .filterValues { it > 0 }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    override val totalUnread: StateFlow<Int> = unreadByRelay
        .map { it.values.sum() }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // Expose NIP-11 relay metadata
    private val _relayMetadataManager = relayMetadataManager ?: RelayMetadataManager(scope)
    override val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadataManager.relayMetadata

    // Relays we could not reach (normalized URLs): the WebSocket connect failed, or
    // the NIP-11 HTTP fetch exhausted its retries, AND no socket is currently up. A
    // relay whose socket connected is always reachable even if its NIP-11 is missing
    // (many NIP-29 relays do not serve a NIP-11 document). Discovery surfaces hide
    // groups hosted on these.
    override val unreachableRelays: StateFlow<Set<String>> =
        combine(
            connectionManager.relayReachability,
            _relayMetadataManager.failedRelays,
        ) { reachability, nip11Failed ->
            val socketOk = reachability.filterValues { it }.keys
            val socketFailed = reachability.filterValues { !it }.keys.toSet()
            val nip11Bad = nip11Failed.map { it.normalizeRelayUrl() }.toSet()
            (socketFailed + (nip11Bad - socketOk)).toSet()
        }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    override val kind10009Relays: StateFlow<Set<String>> = outboxManager.kind10009Relays
    override val groupTagRelays: StateFlow<Set<String>> = outboxManager.groupTagRelays

    // Public kind:10009 group lists of OTHER users (profile pages). Newest event
    // wins per pubkey; the active account's own list never lands here.
    private val _userGroupLists = MutableStateFlow<Map<String, List<UserGroupRef>>>(emptyMap())
    override val userGroupLists: StateFlow<Map<String, List<UserGroupRef>>> = _userGroupLists.asStateFlow()

    private val userGroupListsSerializer =
        MapSerializer(String.serializer(), ListSerializer(UserGroupRef.serializer()))
    private var userGroupListsPersistJob: Job? = null

    /** Most recently-seen pubkeys to keep when snapshotting the kind:10009 cache to disk. */
    private val userGroupListsCacheCap = 500

    /** Hydrate other users' kind:10009 lists from disk so discovery tabs render from cache. */
    private fun restoreUserGroupListsFromCache() {
        try {
            val cached = SecureStorage.getUserGroupListsCache()
            if (cached.isNullOrBlank()) return
            val map = json.decodeFromString(userGroupListsSerializer, cached)
            if (map.isNotEmpty()) _userGroupLists.value = map
        } catch (_: Exception) {
            // Corrupted cache — start fresh.
        }
    }

    /** Debounced snapshot of the (recency-capped) kind:10009 cache to the global on-disk store. */
    private fun scheduleUserGroupListsPersist() {
        userGroupListsPersistJob?.cancel()
        userGroupListsPersistJob =
            scope.launch {
                delay(5_000)
                val snapshot = _userGroupLists.value
                val capped =
                    if (snapshot.size <= userGroupListsCacheCap) {
                        snapshot
                    } else {
                        snapshot.entries.toList().takeLast(userGroupListsCacheCap).associate { it.toPair() }
                    }
                if (capped.isEmpty()) return@launch
                try {
                    SecureStorage.saveUserGroupListsCache(json.encodeToString(userGroupListsSerializer, capped))
                } catch (_: Exception) {
                }
            }
    }
    private val userGroupListCreatedAt = mutableMapOf<String, Long>()

    // The active account's own NIP-02 contact list (kind:3). [following] is the set
    // of "p"-tagged pubkeys; the raw tags + content are kept so follow/unfollow
    // re-publish on top of the latest known list without dropping relay hints,
    // petnames, or the (legacy) relay-JSON content.
    private val _following = MutableStateFlow<Set<String>>(emptySet())
    override val following: StateFlow<Set<String>> = _following.asStateFlow()

    // True once the active account's kind:3 has actually loaded (arrived from a relay,
    // been published by us, or the fetch resolved as "no list"). Lets the UI tell
    // "still loading" apart from "follows nobody", so an empty [following] after the
    // user unfollows everyone is shown as empty instead of falling back to a stale cache.
    private val _contactListLoaded = MutableStateFlow(false)
    override val contactListLoaded: StateFlow<Boolean> = _contactListLoaded.asStateFlow()
    private var contactListCreatedAt = 0L
    private var contactListContent = ""
    private var contactListTags: List<List<String>> = emptyList()
    private var contactListRequested = false
    private val contactListMutex = Mutex()

    // Debounced kind:3 publisher. [following] holds the user's desired set (flipped
    // optimistically on each tap); rapid taps coalesce into a single publish.
    private var pendingContactListPublish: Job? = null
    private var hasUnpublishedContactChanges = false
    private val contactListPublishDebounceMs = 700L

    private fun followsFrom(tags: List<List<String>>): Set<String> = tags
        .filter { it.firstOrNull() == "p" }
        .mapNotNull { it.getOrNull(1)?.takeIf { pk -> pk.isNotBlank() } }
        .toSet()

    private fun handleKind3Event(event: JsonObject) {
        // Only the active account's own list drives [following]; other users' kind:3
        // events (some relays gossip them) are ignored here.
        val pubKey = sessionManager.getPublicKey() ?: return
        val eventPubkey = event["pubkey"]?.jsonPrimitive?.contentOrNull ?: return
        if (eventPubkey != pubKey) return
        val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
        if (createdAt < contactListCreatedAt) return
        val tags =
            event["tags"]?.jsonArray.orEmpty().map { tag ->
                tag.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }
        contactListCreatedAt = createdAt
        contactListContent = event["content"]?.jsonPrimitive?.contentOrNull ?: ""
        contactListTags = tags
        contactListRequested = true
        _contactListLoaded.value = true
        // Keep the optimistic [following] when there are local taps not yet published,
        // so a relay echo arriving mid-follow doesn't clobber a just-tapped follow.
        if (!hasUnpublishedContactChanges) {
            _following.value = followsFrom(tags)
        }
    }

    override fun forceInitialized() {
        _isInitialized.value = true
    }

    override suspend fun initialize() {
        // Wire up connection lifecycle callbacks once — avoids overwriting them on every connect().
        connectionManager.onConnectionDropped = {
            scope.launch { groupManager.handleConnectionLost() }
        }
        connectionManager.onPoolRelayLost = { relayUrl ->
            // Only reconnect pool relays that were actively connected during this session
            // (had been primary at some point). Lazy pool relays are not reconnected.
            if (relayUrl in connectedPoolRelays) {
                relayReconnectScheduler.schedule(relayUrl)
            }
        }
        connectionManager.onReconnected = { client ->
            resubscribeAllGroups(client)
            pendingEventManager?.onConnectionRestored()
            reconnectDroppedNip29PoolRelays()
            resubscribeDmInbox()
            scope.launch { refreshVisibleUserMetadata() }
        }

        // Clear the sidebar skeleton for a relay that becomes unreachable. EOSE never
        // arrives for a failed connection, so without this the loading flag would
        // pulse indefinitely while the offline management screen is already showing.
        scope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is ConnectionManager.ConnectionState.Error ||
                    state is ConnectionManager.ConnectionState.Reconnecting
                ) {
                    val relay = connectionManager.currentRelayUrl.value
                    if (relay.isNotBlank()) {
                        groupManager.markRelayLoaded(relay)
                    }
                }
            }
        }

        // The set of groups we KNOW on the primary relay grows over time: friends'/curator
        // kind:10009 lists arrive after connect, and joining/creating a group (incl. a subgroup)
        // adds to joinedGroupsByRelay. When it grows, send a fresh targeted #d fetch so those
        // groups' metadata loads (name/picture/parent) — we never pull the full directory.
        scope.launch {
            combine(_following, _userGroupLists, groupManager.joinedGroupsByRelay) { _, _, _ -> Unit }.collect {
                val relay = connectionManager.currentRelayUrl.value
                if (relay.isBlank()) return@collect
                val client = connectionManager.getPrimaryClient() ?: return@collect
                val ids = knownGroupIdsForRelay(relay).toSet()
                if (ids.isNotEmpty() && ids != sentKnownGroupFetch[relay.normalizeRelayUrl()]) {
                    groupManager.markRelayLoading(relay)
                    sendKnownGroupsFetch(client, relay)
                }
            }
        }

        // Open the NIP-17 DM inbox once we're logged in (cold-boot restore or a fresh login).
        // startDmInbox() dedups; resetContactListState re-arms it on account switch.
        scope.launch {
            sessionManager.isLoggedIn.collect { loggedIn ->
                if (loggedIn) startDmInbox()
            }
        }

        // Bunker (NIP-46) signer-ready recovery. On session restore / account-add
        // the account is marked logged-in optimistically while the remote signer
        // connects asynchronously (issue #85). connect() + NIP-42 AUTH for the
        // group relays run before the signer can sign the AUTH event, so their
        // group/mux REQs come back CLOSED "auth-required" and nothing retries —
        // groups stuck on "No messages yet" / "Members 0" until app restart.
        // When the signer transitions to ready, reconnect the group relays: fresh
        // sockets -> fresh AUTH (now signable) -> resubscribe (messages+members).
        // Interactive loginWithBunker connects the signer synchronously (verifying
        // never goes true), so this never fires spuriously for a fresh login.
        scope.launch {
            var wasReady = sessionManager.isBunkerConnected.value &&
                !sessionManager.isBunkerVerifying.value &&
                sessionManager.isBunkerReady()
            combine(
                sessionManager.isBunkerConnected,
                sessionManager.isBunkerVerifying,
            ) { connected, verifying ->
                connected && !verifying && sessionManager.isBunkerReady()
            }.collect { ready ->
                if (ready && !wasReady) recoverBunkerGroupRelays()
                wasReady = ready
            }
        }

        metadataManager.messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) }
        groupManager.messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) }

        connectionManager.startNetworkMonitor()

        // Deep link relay from URL query params (web) — merge into relay list
        val deepLinkRelay = StartupResolver.deepLinkRelayUrl

        // Populate IndexedDB-backed caches (relay_metadata, joined_group_meta) before any reads.
        // No-op on Android/JVM where storage is synchronous.
        SecureStorage.preloadMetadata()

        // One-shot legacy → multi-account migration. Idempotent; no-op once any
        // account exists. Runs before restoreSession() so the AccountStore is
        // ready when Phase 2 wires AuthManager to read from it.
        AppModule.accountStore.migrateFromLegacyIfNeeded()

        // Now that the IDB cache is populated, prime the relay-metadata StateFlow so the sidebar
        // shows icons/names immediately instead of waiting for NIP-11 HTTP fetches.
        _relayMetadataManager.restoreFromCache()
        // Same for kind:0 profiles: hydrate names/avatars from the global on-disk store so they
        // show instantly on cold start instead of waiting for the network.
        metadataManager.restoreFromCache()
        // And friends'/curator's kind:10009 so the From friends / Recommended tabs render from
        // cache before the network answers; loadFriendsGroups/loadRecommended revalidate.
        restoreUserGroupListsFromCache()

        val restored = sessionManager.restoreSession()
        if (restored) {
            // Activate an AccountSession so all signing routes through the
            // isolated NostrSigner from this point forward.
            AppModule.activateSessionForActiveAccount()
            // Now that the session is active, load the per-account current
            // relay pointer. Doing this earlier (before restoreSession) would
            // read with a null pubkey and force currentRelayUrl to blank,
            // making the boot flow pick a different primary relay than the
            // one the user was on.
            connectionManager.loadSavedRelay()
            val pubkey = sessionManager.getPublicKey()
            val activeRelay = connectionManager.currentRelayUrl.value

            // Seed the kind:10009 relay set from the active account's persisted
            // relay list so the sidebar pre-fills before the network fetch. Must
            // run after restoreSession so we have a pubkey — a blank pubkey
            // would leak the previous account's relays into a fresh login.
            outboxManager.seedFromCache(pubkey.orEmpty())

            // Load saved relay list and pre-populate the rail before connecting
            val savedRelays = SecureStorage.loadRelayListFor(pubkey.orEmpty())

            // No NIP-29 relays saved locally — fetch kind:10009 from bootstrap
            // relays so "r" tags can restore the user's relay list automatically.
            // Once relays are discovered, connect to the first one as primary.
            if (activeRelay.isBlank() && savedRelays.isEmpty() && deepLinkRelay == null) {
                if (pubkey != null) {
                    unreadManager.initialize(pubkey)
                    notificationSettings?.initialize(pubkey)
                    notificationHistoryStore?.initialize(pubkey)
                    initializeOutboxModel()
                    scope.launch {
                        outboxManager.loadJoinedGroupsFromNostr(pubkey) { msg, c ->
                            enqueueToRelayPipeline(msg, c)
                        }
                        // After kind:10009 is fetched, check if relays were restored.
                        // If so, connect to the first one so the app doesn't stay in empty state.
                        val restoredRelays = SecureStorage.loadRelayListFor(pubkey)
                        if (restoredRelays.isNotEmpty()) {
                            val primaryRelay = restoredRelays.first()
                            // Persist and set as active so the UI picks it up immediately.
                            SecureStorage.saveCurrentRelayUrlFor(pubkey, primaryRelay)
                            connectionManager.loadSavedRelay()

                            groupManager.prePopulateRelayList(restoredRelays)
                            _relayMetadataManager.fetchAll(restoredRelays)
                            liveCursorStore?.loadAll(restoredRelays)
                            groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
                            groupManager.loadAllJoinedGroupsFromStorage(pubkey, restoredRelays)
                            groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, restoredRelays)
                            groupManager.restoreGroupMembershipFromStorage(pubkey)
                            groupManager.migrateMessageBlobsToCache(pubkey)
                            connect(primaryRelay)
                            scope.launch { ensureJoinedRelaysConnected(primaryRelay) }
                        }
                    }
                    requestUserMetadata(setOf(pubkey))
                }
                _isInitialized.value = true
                return
            }

            // Merge deep link relay into the visible list (not persisted until user confirms)
            val baseRelays = if (activeRelay.isBlank()) savedRelays else (listOf(activeRelay) + savedRelays)
            val allRelays = (baseRelays + listOfNotNull(deepLinkRelay)).distinct()
            // Deep link relay becomes primary; otherwise use the first saved relay
            val primaryRelay = deepLinkRelay ?: allRelays.first()
            if (deepLinkRelay != null) {
                // Only set as current relay for this session — don't save to relay list
                if (pubkey != null) {
                    SecureStorage.saveCurrentRelayUrlFor(pubkey, primaryRelay)
                }
                connectionManager.loadSavedRelay()
                // Signal UI to offer adding this relay if it's not already saved
                if (deepLinkRelay !in baseRelays) {
                    _pendingDeepLinkRelay.value = deepLinkRelay
                }
            }
            liveCursorStore?.loadAll(allRelays)
            groupManager.prePopulateRelayList(allRelays)
            _relayMetadataManager.fetchAll(allRelays)

            if (pubkey != null) {
                groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
                groupManager.loadAllJoinedGroupsFromStorage(pubkey, allRelays)
                groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, allRelays)
                groupManager.restoreGroupMembershipFromStorage(pubkey)
                groupManager.migrateMessageBlobsToCache(pubkey)
                unreadManager.initialize(pubkey)
                notificationSettings?.initialize(pubkey)
                notificationHistoryStore?.initialize(pubkey)
            }
            initializeOutboxModel()

            // Local data loaded — show UI while connect() runs in the background
            _isInitialized.value = true

            connect(primaryRelay)
            scope.launch { ensureJoinedRelaysConnected(primaryRelay) }
            if (pubkey != null) {
                requestUserMetadata(setOf(pubkey))
            }

            // Pool relays are known but NOT connected — they connect lazily when the
            // user switches to them via switchRelay(). Pre-population above already
            // makes them visible in the relay rail.
        } else {
            _isInitialized.value = true
        }

        // Periodically refresh live group subscriptions so relays that drop idle subs
        // (e.g. pyramid.fiatjaf.com) don't permanently stall message delivery.
        // The CLOSED handler already re-opens live subs on explicit relay closure; this
        // periodic refresh is the safety net for silent drops with no CLOSED message.
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L) // 5 minutes
                if (connectionManager.connectionState.value is ConnectionManager.ConnectionState.Connected) {
                    groupManager.refreshLiveSubscriptions()
                }
                liveCursorStore?.persistAll()
            }
        }

        // Low-frequency revalidation of the metadata on screen (open-group authors/members
        // and the followed sidebar) so a long-running session picks up renamed profiles and
        // new avatars without needing a reconnect. Only stale entries are refetched.
        scope.launch {
            while (true) {
                delay(MetadataManager.STALE_THRESHOLD_MS)
                if (connectionManager.connectionState.value is ConnectionManager.ConnectionState.Connected) {
                    refreshVisibleUserMetadata()
                }
            }
        }
    }

    /**
     * Connect a pool relay in the background and track it as actively connected.
     * Only called for relays that were previously primary (demoted to pool) and need reconnection.
     */
    private suspend fun connectToRelayBackground(relayUrl: String) {
        if (connectionManager.getPrimaryClient()?.getRelayUrl() == relayUrl) return
        try {
            connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            } ?: return
            connectedPoolRelays.add(relayUrl)
        } catch (_: Exception) {}
    }

    // True only while finishLoginInit is wiring up a newly logged-in account.
    // The bunker-ready collector reacts to _bunkerState turning Connected, which
    // for the synchronous loginWithBunker path happens mid-swap, before the new
    // account's relays exist; reconnecting then raced the swap's own reconnect
    // and left the primary unauthenticated (the add-account-needs-restart bug).
    @Volatile
    private var bunkerLoginInProgress = false

    /**
     * Drive the active relay's NIP-42 AUTH once the bunker signer is reachable,
     * so private groups that came back CLOSED "auth-required" while the signer
     * was still connecting load without an app restart.
     *
     * Idempotent: skips the reconnect when the primary already AUTH'd this
     * session (a slow bunker can finish signing before the verifying flag flips,
     * and reconnecting would throw away ~3.5 s of setup + AUTH); ensureJoined is
     * always safe to re-run. No-ops while a login swap is still in flight.
     */
    private suspend fun recoverBunkerGroupRelays() {
        if (bunkerLoginInProgress) return
        try {
            val primary = connectionManager.getPrimaryClient()
            val primaryHealthy = primary != null &&
                primary.isConnected() &&
                primary.hasAuthSucceeded()
            if (!primaryHealthy) reconnect()
            ensureJoinedRelaysConnected(
                connectionManager.currentRelayUrl.value.takeIf { it.isNotBlank() },
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /**
     * Open a WebSocket + send a mux chat REQ for every relay where the user has
     * joined groups, except [skipPrimary] (already connected). Idempotent; safe
     * to call from startup, on relay switch, or after a join.
     *
     * Without this, only the primary relay delivers live kind:9 — joined groups
     * on other relays go silent. See plan: cosmic-beaming-eclipse.md.
     */
    private suspend fun ensureJoinedRelaysConnected(skipPrimary: String?) {
        val joinedRelays = groupManager.joinedGroupsByRelay.value.keys.toList()
        val skipNormalized = skipPrimary?.normalizeRelayUrl()
        val restrictedNormalized = _restrictedRelays.value.keys
            .map { it.normalizeRelayUrl() }
            .toSet()
        val targets = joinedRelays
            .map { it.normalizeRelayUrl() }
            .distinct()
            .filter { it.isNotBlank() && it != skipNormalized && it !in restrictedNormalized }

        for (relayUrl in targets) {
            // Skip if already connected — connectToRelayBackground is idempotent
            // but we'd still pay the awaitAuthOrTimeout round-trip needlessly.
            val existing = connectionManager.getClientForRelay(relayUrl)
            if (existing != null && existing.isConnected() && relayUrl in connectedPoolRelays) {
                groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
                delay(100)
                continue
            }
            connectToRelayBackground(relayUrl)
            val client = connectionManager.getClientForRelay(relayUrl) ?: run {
                delay(100)
                continue
            }
            try {
                if (client.isConnected()) client.awaitAuthOrTimeout()
            } catch (_: Throwable) {}
            groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
            delay(100)
        }
    }

    override fun clearAuthUrl() {
        sessionManager.clearAuthUrl()
    }

    override suspend fun loginWithBunker(bunkerUrl: String): Result<String> = try {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        // Suppress the reactive bunker-ready recovery while the swap wires up the
        // new account: loginWithBunker flips _bunkerState to Connected mid-swap,
        // which would otherwise fire a reconnect against the old account's relays.
        bunkerLoginInProgress = true
        val userPubkey = try {
            val pk = sessionManager.loginWithBunker(bunkerUrl)
            finishLoginInit(previousPubkey, pk)
            pk
        } finally {
            bunkerLoginInProgress = false
        }
        // Now the new account's relays are wired up and the signer is connected,
        // drive a correctly-timed, idempotent AUTH recovery so a first bunker add
        // never needs an app restart to load private groups.
        scope.launch {
            delay(BUNKER_LOGIN_RECOVERY_DELAY_MS)
            recoverBunkerGroupRelays()
        }
        Result.Success(userPubkey)
    } catch (e: Exception) {
        Result.Error(AppError.Auth.BunkerError(e.message ?: "Bunker connection failed", e))
    }

    override val defaultNostrConnectRelays: List<String> = sessionManager.defaultNostrConnectRelays

    override suspend fun createNostrConnectSession(relays: List<String>): Pair<String, org.nostr.nostrord.nostr.Nip46Client> = sessionManager.createNostrConnectSession(relays)

    override suspend fun completeNostrConnectLogin(
        client: org.nostr.nostrord.nostr.Nip46Client,
        relays: List<String>,
    ): String {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        bunkerLoginInProgress = true
        val userPubkey = try {
            val pk = sessionManager.completeNostrConnectLogin(client, relays)
            finishLoginInit(previousPubkey, pk)
            pk
        } finally {
            bunkerLoginInProgress = false
        }
        scope.launch {
            delay(BUNKER_LOGIN_RECOVERY_DELAY_MS)
            recoverBunkerGroupRelays()
        }
        return userPubkey
    }

    override suspend fun loginSuspend(privKey: String, pubKey: String, isNewIdentity: Boolean, ncryptsec: String?): Result<Unit> = try {
        val previousPubkey = sessionManager.getPublicKey()
        // A freshly generated identity has nothing on the network yet — no
        // kind:10002, no kind:10009 — so don't bother flagging the relay
        // discovery spinner. The user will land on OnboardingScreen and pick
        // their first relay manually.
        if (!isNewIdentity && connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        sessionManager.loginWithPrivateKey(privKey, pubKey, ncryptsec)
        finishLoginInit(previousPubkey, pubKey, isNewIdentity = isNewIdentity)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
    }

    override suspend fun loginWithNip07(pubkey: String): Result<Unit> = try {
        val previousPubkey = sessionManager.getPublicKey()
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        sessionManager.loginWithNip07(pubkey)
        finishLoginInit(previousPubkey, pubkey)
        Result.Success(Unit)
    } catch (e: Exception) {
        Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
    }

    /**
     * Shared post-login setup. Handles two cases:
     *
     * - Cold start ([previousPubkey] is null): just (re)initialise per-account
     *   stores for [newPubkey] and kick off the initial relay connect.
     * - Warm swap (already authenticated as a different pubkey): clear the
     *   previous identity's in-memory caches via [AppModule.applyActiveAccountChange],
     *   then re-hydrate joined-group state for [newPubkey] from storage and
     *   force a reconnect so pubkey-filtered REQs reissue.
     */
    private suspend fun finishLoginInit(
        previousPubkey: String?,
        newPubkey: String,
        isNewIdentity: Boolean = false,
    ) {
        val isWarmSwap = previousPubkey != null && previousPubkey != newPubkey
        // Activate an AccountSession for the logged-in account so all signing
        // routes through the isolated NostrSigner and the session scope is
        // properly bounded. Must happen before any event is published.
        AppModule.activateSessionForActiveAccount()
        if (isWarmSwap) {
            AppModule.applyActiveAccountChange(AppModule.accountStore.active)
            initializeOutboxModel()
            sessionManager.setLoggedIn(true)
            reloadForActiveAccount()
        } else {
            // Re-bind GroupManager to the new identity. logout() called
            // groupManager.clear() which nulls currentPubkey, and the cold-start
            // path otherwise only resets it as a side-effect of
            // loadJoinedGroupsFromStorage — which is not invoked here because
            // the boot flow (initialize) is the one that calls it on cold start,
            // not a login that happens later in the process. Without this,
            // flushBatchToState drops every incoming kind:9 because of its
            // `currentPubkey == null` guard, leaving every group stuck on
            // "No messages yet" until the user restarts the app.
            groupManager.setCurrentPubkey(newPubkey)
            // Repopulate _currentRelayUrl from the per-account persisted slot.
            // logout() called connectionManager.clearCurrentRelay() which blanked
            // the StateFlow, so without this `connect()` below would dispatch
            // to connect("") and return immediately. Worse: when the kind:10009
            // arrives over the outbox bootstrap, onRelaysRestored fires
            // autoConnectFirstRelay(newRelays), whose `isBlank()` guard passes
            // and which OVERWRITES the persisted slot with relays.first() —
            // silently moving the user off the relay they were on (e.g. from
            // groups.fiatjaf.com to whatever happens to be first in their
            // kind:10009 r-tag list). The cold-boot path (initialize) and the
            // warm-swap path (applyActiveAccountChange) both call loadSavedRelay
            // for the same reason; the cold-start re-login path was the only
            // one missing it.
            connectionManager.loadSavedRelay()
            // Seed the relay rail (kind:10009 set) from the per-account cache so
            // ALL of the user's relays show immediately, exactly as initialize()
            // does on cold boot. Without this, the rail showed only the current
            // relay after re-login until the slow network kind:10009 fetch landed
            // (or the user restarted the app).
            outboxManager.seedFromCache(newPubkey)
            // Re-hydrate joined-group state from local storage, exactly as the
            // cold-boot path (initialize) does at lines 377-380. Previously this
            // re-login branch relied solely on the kind:10009 outbox fetch to
            // repopulate the group list; when that network fetch was slow or the
            // bunker signer was unreachable (#85), nothing loaded the locally
            // persisted groups as a fallback. The result (#88): every group was
            // stuck on "No messages yet" and the relay's groups were never
            // subscribed, intermittently, depending on outbox-fetch timing.
            val activeRelay = connectionManager.currentRelayUrl.value
            val savedRelays = SecureStorage.loadRelayListFor(newPubkey)
            val allRelays = (listOfNotNull(activeRelay.ifBlank { null }) + savedRelays).distinct()
            val primaryRelay = activeRelay.ifBlank { allRelays.firstOrNull().orEmpty() }
            if (primaryRelay.isNotBlank()) {
                groupManager.prePopulateRelayList(allRelays)
                // Mirror the rest of initialize()'s sibling bootstrap, not just the
                // joined-group loaders: without loadAll the first mux subscription
                // has no persisted `since` cursor, and without fetchAll the relay
                // rail shows stale NIP-11 icons/names after re-login.
                liveCursorStore?.loadAll(allRelays)
                _relayMetadataManager.fetchAll(allRelays)
                groupManager.loadJoinedGroupsFromStorage(newPubkey, primaryRelay)
                groupManager.loadAllJoinedGroupsFromStorage(newPubkey, allRelays)
                groupManager.restoreJoinedGroupMetadataFromStorage(newPubkey, allRelays)
                groupManager.restoreGroupMembershipFromStorage(newPubkey)
                groupManager.migrateMessageBlobsToCache(newPubkey)
            }
            unreadManager.initialize(newPubkey)
            notificationSettings?.initialize(newPubkey)
            notificationHistoryStore?.initialize(newPubkey)
            // Skip the outbox bootstrap for a freshly generated identity:
            // nothing has been published yet, so kind:10002 / kind:10009 fetches
            // would only delay landing the user on the onboarding screen.
            // The bootstrap connections will form on demand once the user
            // adds their first relay.
            if (!isNewIdentity) initializeOutboxModel()
            sessionManager.setLoggedIn(true)
            scope.launch { connect() }
            // Open sockets for joined groups that live on secondary (non-primary)
            // relays too. connect() only handles the primary; without this those
            // groups stay on "No messages yet" until the user manually switches to
            // their relay — the same #88 symptom, confined to non-primary relays.
            if (primaryRelay.isNotBlank()) {
                scope.launch { ensureJoinedRelaysConnected(primaryRelay) }
            }
        }
        scope.launch { requestUserMetadata(setOf(newPubkey)) }
    }

    override suspend fun logout() {
        // Do NOT cancel appScope's children here: logout runs on a coroutine that
        // is itself an appScope child (AccountManager.removeAccountAsync), so a
        // scope-wide cancelChildren() would abort logout midway, leaving
        // _isLoggedIn true and the sockets/legacy slots uncleared (the app then
        // could not leave the last account and a restart re-migrated it). Per-
        // account in-flight work lives on AccountSession.scope and is cancelled by
        // ActiveAccountManager.clear() via applyActiveAccountChange(null).

        // Preserve persisted per-account state (relay list, joined groups,
        // current relay, last viewed group). Re-login with the same pubkey
        // should restore the previous setup from local storage. Wiping here
        // forced a cold-start that came back empty or, worse, raced an
        // in-flight kind:10009 from another account.
        _isDiscoveringRelays.value = false
        outboxManager.clear()
        groupManager.clear()
        unreadManager.clear()
        notificationSettings?.clear()
        notificationHistoryStore?.clear()
        liveCursorStore?.clear()
        relayPipelines.values.forEach { (_, pipeline) -> pipeline.close() }
        relayPipelines.clear()
        connectionManager.clearCurrentRelay()

        try {
            connectionManager.clearAll()
        } catch (_: Exception) {}
        connectedPoolRelays.clear()

        // Reset session-scoped in-memory dedup/scoping caches. These are NOT
        // persisted state — they only make sense within a single login
        // session. Leaving them populated meant a logout→re-login on the same
        // process kept stale entries that made resubscribeAfterAuth and the
        // mux/message refresh paths skip work for the new session, leaving
        // every group stuck on "No messages yet" until the user restarted.
        lastRequestGroupsAt.clear()
        authedGroupListFetchedRelays.clear()
        lastGapDetectionAt.clear()
        pendingFullFetchMutex.withLock { pendingFullFetchRequests.clear() }
        _closedGroupSubscriptions.value = emptySet()
        activeRelayUrl = null
        // Per-relay AUTH-required / restricted markers from the previous
        // session would otherwise short-circuit switchRelay on re-login (early
        // return at the restriction check), so a relay that just had a
        // transient AUTH timeout — common on bunker logins — would stay
        // permanently "restricted" in the UI until process restart, even
        // though the new session's signer can answer the challenge fine.
        _restrictedRelays.value = emptyMap()
        resetContactListState()

        sessionManager.logout()
    }

    // ===== Direct messages (NIP-17 over NIP-59 gift wraps) =====

    private val dmManager = DmManager(scope)

    /** Conversations (most-recent first), derived from decrypted NIP-17 messages. */
    override val dmConversations: StateFlow<List<DmConversation>> get() = dmManager.conversations

    /** Decrypted DM messages keyed by peer pubkey. */
    override val dmMessagesByPeer: StateFlow<Map<String, List<DmMessage>>> get() = dmManager.messagesByPeer

    /** Unread DM count per peer (incoming messages newer than the read high-water). */
    override val dmUnreadByPeer: StateFlow<Map<String, Int>> get() = dmManager.unreadByPeer

    /** Total unread DMs across all conversations, for the nav badge. */
    override val totalDmUnread: StateFlow<Int> get() = dmManager.totalUnread

    // Our own effective DM relays (kind:10050, or the defaults until we publish one). Drives the
    // Settings editor; kept in sync on inbox open, on our own kind:10050, and on publish.
    private val _myDmRelays = MutableStateFlow<List<String>>(emptyList())
    override val myDmRelays: StateFlow<List<String>> = _myDmRelays.asStateFlow()

    // Fallback DM relays for users (and us) without a published kind:10050.
    private val defaultDmRelays =
        listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://auth.nostr1.com")

    private var dmInboxStarted = false
    private var dmPersistenceWired = false
    private val dmPersistenceJobs = mutableListOf<kotlinx.coroutines.Job>()

    /** Mark a DM conversation read up to its newest message (clears its unread badge). */
    override suspend fun markDmRead(peerPubkey: String) {
        dmManager.markRead(peerPubkey)
    }

    private fun dmRelaysFor(pubkey: String): List<String> = dmManager.dmRelaysFor(pubkey).map { it.normalizeRelayUrl() }.ifEmpty { defaultDmRelays }

    /**
     * Send a NIP-17 direct message: build the rumor, seal + gift-wrap it for the recipient and a
     * self-copy for us, and publish each to its side's DM relays. Requires a local key for now
     * (bunker / NIP-07 NIP-44 delegation lands in a later phase).
     */
    override suspend fun sendDm(recipientPubkey: String, content: String): Result<Unit> {
        val signer =
            ActiveAccountManager.session.value?.signer
                ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return try {
            val rumor = Nip17.buildRumor(myPub, recipientPubkey, content)
            val recipientWrap = Nip17.wrap(rumor, recipientPubkey, signer)
            val selfWrap = Nip17.wrap(rumor, myPub, signer)
            dmManager.addOptimistic(rumor, recipientPubkey, myPub)
            publishEventToRelays(dmRelaysFor(recipientPubkey), recipientWrap)
            publishEventToRelays(dmRelaysFor(myPub), selfWrap)
            Result.Success(Unit)
        } catch (e: NostrSigner.SigningException) {
            Result.Error(AppError.Unknown("Your signer could not encrypt this message (NIP-44). It may not support direct messages."))
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to send the message"))
        }
    }

    private suspend fun publishEventToRelays(relays: List<String>, event: Event) {
        val json =
            buildJsonArray {
                add("EVENT")
                add(event.toJsonObject())
            }.toString()
        relays.distinct().forEach { url ->
            val client =
                connectionManager.getClientForRelay(url)
                    ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
            try {
                client?.send(json)
            } catch (_: Throwable) {
            }
        }
    }

    /** Connect to our DM relays and subscribe to the kind:1059 inbox so DMs arrive in real time. */
    suspend fun startDmInbox() {
        if (dmInboxStarted) return
        val myPub = sessionManager.getPublicKey() ?: return
        dmInboxStarted = true

        // Hydrate from disk before the inbox streams so old conversations render instantly and
        // already-seen gift wraps are never re-decrypted.
        dmManager.hydrate(SecureStorage.loadDmMessages(myPub), SecureStorage.loadDmLastRead(myPub))
        wireDmPersistence(myPub)

        _myDmRelays.value = dmRelaysFor(myPub)
        fetchDmRelays(myPub)
        resendDmInboxReq(myPub)
        // The sync cursor is "last time we opened the inbox"; advance it now so the next start
        // refetches only from cursor - 2 days (NIP-59 backdates gift wraps up to 2 days).
        SecureStorage.saveDmSyncCursor(myPub, org.nostr.nostrord.utils.epochSeconds())

        // Make ourselves reachable: publish a kind:10050 if we don't already have one.
        scope.launch {
            delay(4_000)
            if (dmManager.dmRelaysFor(myPub).isEmpty()) {
                publishDmRelayList(defaultDmRelays)
            } else {
                _myDmRelays.value = dmRelaysFor(myPub)
            }
        }
    }

    /** Persist decrypted messages + read state whenever they change (one collector per session). */
    private fun wireDmPersistence(myPub: String) {
        if (dmPersistenceWired) return
        dmPersistenceWired = true
        dmPersistenceJobs +=
            scope.launch {
                dmManager.messagesByPeer.drop(1).collect {
                    SecureStorage.saveDmMessages(myPub, dmManager.allMessages())
                }
            }
        dmPersistenceJobs +=
            scope.launch {
                dmManager.lastReadByPeer.drop(1).collect { reads ->
                    SecureStorage.saveDmLastRead(myPub, reads)
                }
            }
    }

    /** (Re)subscribe to the kind:1059 inbox on all DM relays. Idempotent; also run on reconnect. */
    private suspend fun resendDmInboxReq(myPub: String) {
        val since = SecureStorage.loadDmSyncCursor(myPub)
        val filter =
            buildJsonObject {
                putJsonArray("kinds") { add(Nip17.KIND_GIFT_WRAP) }
                putJsonArray("#p") { add(myPub) }
                if (since > 0L) put("since", since - GIFT_WRAP_BACKDATE_SECONDS)
            }
        val req =
            buildJsonArray {
                add("REQ")
                add("dm_inbox")
                add(filter)
            }.toString()
        val urls = dmRelaysFor(myPub).distinct()
        // Keep DM relays alive: register them with the reconnect scheduler so a dropped DM
        // socket is revived (and re-subscribed via resubscribePoolRelay) rather than going
        // silent until the next app start. They host no NIP-29 groups, so group-resubscribe
        // on them is a harmless no-op.
        connectedPoolRelays.addAll(urls)
        urls.forEach { url ->
            val client =
                connectionManager.getClientForRelay(url)
                    ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
            try {
                client?.send(req)
            } catch (_: Throwable) {
            }
        }
    }

    /** Re-arm the DM inbox after a relay reconnect so real-time receive survives drops. */
    private fun resubscribeDmInbox() {
        if (!dmInboxStarted) return
        val myPub = sessionManager.getPublicKey() ?: return
        scope.launch { resendDmInboxReq(myPub) }
    }

    /**
     * Publish our NIP-17 DM relay list (kind:10050) so other clients know where to send our DMs.
     * Replaceable event with a `relay` tag per URL. Published to general relays + the DM relays.
     */
    override suspend fun publishDmRelayList(relays: List<String>): Result<Unit> {
        val myPub = sessionManager.getPublicKey() ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val clean = relays.map { it.normalizeRelayUrl() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return Result.Error(AppError.Unknown("No DM relays to publish"))
        return try {
            val event =
                Event(
                    pubkey = myPub,
                    createdAt = org.nostr.nostrord.utils.epochSeconds(),
                    kind = Nip17.KIND_DM_RELAYS,
                    tags = clean.map { listOf("relay", it) },
                    content = "",
                )
            val signed = sessionManager.signEvent(event)
            dmManager.ingestDmRelays(signed)
            _myDmRelays.value = clean
            val targets = (clean + outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
            publishEventToRelays(targets, signed)
            Result.Success(Unit)
        } catch (e: NostrSigner.SigningException) {
            Result.Error(AppError.Unknown("Your signer rejected publishing the DM relay list."))
        } catch (e: Throwable) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to publish DM relays"))
        }
    }

    /** One-shot fetch of [pubkey]'s kind:10050 DM relay list from the default relays. */
    private suspend fun fetchDmRelays(pubkey: String) {
        val filter =
            buildJsonObject {
                putJsonArray("kinds") { add(Nip17.KIND_DM_RELAYS) }
                putJsonArray("authors") { add(pubkey) }
            }
        val req =
            buildJsonArray {
                add("REQ")
                add("dmrelays_${pubkey.take(8)}")
                add(filter)
            }.toString()
        defaultDmRelays.forEach { url ->
            val client =
                connectionManager.getClientForRelay(url)
                    ?: connectionManager.getOrConnectRelay(url) { m, c -> enqueueToRelayPipeline(m, c) }
            try {
                client?.send(req)
            } catch (_: Throwable) {
            }
        }
    }

    /** Drops the previous account's kind:3 state so [following] never leaks across accounts. */
    private fun resetContactListState() {
        contactListCreatedAt = 0L
        contactListContent = ""
        contactListTags = emptyList()
        contactListRequested = false
        // Cancel any in-flight debounced publish and clear the loaded flag, so the new
        // account starts "not loaded" (UI shows its cache, not a false "follows nobody")
        // and a pending publish never targets the wrong account.
        pendingContactListPublish?.cancel()
        pendingContactListPublish = null
        hasUnpublishedContactChanges = false
        _contactListLoaded.value = false
        _following.value = emptySet()
        dmPersistenceJobs.forEach { it.cancel() }
        dmPersistenceJobs.clear()
        dmManager.clear()
        _myDmRelays.value = emptyList()
        dmInboxStarted = false
        dmPersistenceWired = false
    }

    override fun forgetBunkerConnection() {
        sessionManager.forgetBunkerConnection()
    }

    override suspend fun reloadForActiveAccount() {
        val pubkey = sessionManager.getPublicKey() ?: return
        // The contact list is per-account; drop the prior account's before the swap.
        resetContactListState()
        val activeRelay = connectionManager.currentRelayUrl.value
        val savedRelays = SecureStorage.loadRelayListFor(pubkey)

        // Compute a one-shot catch-up `since` for the upcoming mux refresh:
        // earliest of the account's last-active timestamp and the newest
        // notification it already has, minus a small overlap. Skipped when
        // this account has never been active before (no data point exists,
        // so the default per-group windowing in LiveCursorStore is correct).
        val lastActiveAt = SecureStorage.getLastActiveAt(pubkey)
        val newestNotif = notificationHistoryStore?.entries?.value?.firstOrNull()?.createdAt ?: 0L
        if (lastActiveAt > 0L || newestNotif > 0L) {
            val now = epochSeconds()
            val candidate = listOf(lastActiveAt, newestNotif).filter { it > 0L }.min()
            val capped = maxOf(
                candidate - LiveCursorStore.RECONNECT_OVERLAP_S,
                now - LiveCursorStore.MAX_SINCE_AGE_S,
            )
            groupManager.setCatchUpSince(capped)
            // Also pin UnreadManager's "first seen" fallback to the same value
            // so events arriving via catch-up qualify (otherwise they're
            // filtered as createdAt < firstSeenAt(now) for groups the active
            // account has never opened).
            unreadManager.setCatchUpAnchor(capped)
        } else {
            groupManager.setCatchUpSince(null)
            unreadManager.setCatchUpAnchor(null)
        }

        // Pre-fill the rail for the new account before the kind:10009 fetch
        // returns; without this the sidebar would show empty until the network
        // resolves, even for accounts that already have a persisted relay list.
        outboxManager.seedFromCache(pubkey)

        // Mirror initialize()/finishLoginInit's full relay+group bootstrap so a
        // warm account swap establishes the SAME state as a cold start. The swap
        // previously loaded joined groups but skipped prePopulateRelayList / the
        // cursor + NIP-11 metadata bootstrap, and elected no primary when the new
        // account had a blank current-relay slot — leaving it on empty groups /
        // a one-relay rail / stale icons until the app was restarted.
        val allRelays = (listOfNotNull(activeRelay.ifBlank { null }) + savedRelays).distinct()
        val primaryRelay = activeRelay.ifBlank { allRelays.firstOrNull().orEmpty() }
        if (primaryRelay.isNotBlank()) {
            groupManager.prePopulateRelayList(allRelays)
            liveCursorStore?.loadAll(allRelays)
            _relayMetadataManager.fetchAll(allRelays)
            groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
            groupManager.loadAllJoinedGroupsFromStorage(pubkey, allRelays)
            groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, allRelays)
            groupManager.restoreGroupMembershipFromStorage(pubkey)
            groupManager.migrateMessageBlobsToCache(pubkey)
            groupManager.loadRestrictedGroupsFromStorage(pubkey, allRelays)
            // Point GroupManager's current-relay flow at the new primary so the
            // derived joinedGroups (My Groups — sidebar AND homescreen) reflects
            // this account immediately. applyActiveAccountChange.clear() nulled it,
            // and the warm-swap path only re-sets it if reconnect() actually runs
            // (non-blank relay + a live message handler) via resubscribeAllGroups
            // — so without this, My Groups stayed empty after an account switch.
            groupManager.restoreGroupsForRelay(primaryRelay)
            // The account had no persisted current relay — elect one so the
            // primary actually connects (and its cache-hit mux is set up) instead
            // of relying on a reconnect that no-ops against a blank current relay.
            if (activeRelay.isBlank()) {
                SecureStorage.saveCurrentRelayUrlFor(pubkey, primaryRelay)
                connectionManager.loadSavedRelay()
            }
        }

        initializeOutboxModel()
        scope.launch {
            outboxManager.loadJoinedGroupsFromNostr(pubkey) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            }
        }

        // Force re-subscribe on the active relay so pubkey-filtered REQs
        // reissue with the new identity instead of carrying stale ones.
        // Background relays still hold sockets authed as the previous account,
        // so the catch-up mux REQ would either be filtered or silently dropped
        // until the next AUTH challenge; clear their mux tracker and reconnect
        // them so the new identity's AUTH runs before subs go out (matches
        // [ensureJoinedRelaysConnected] in the boot path).
        if (primaryRelay.isNotBlank()) {
            triggerReconnect()
        }
        scope.launch {
            try {
                ensureJoinedRelaysConnected(primaryRelay.takeIf { it.isNotBlank() })
            } catch (_: Exception) {}
            // Safety net for the active relay and any joined relay not covered
            // above: applies the catch-up `since` set earlier so events missed
            // while inactive arrive across every relay.
            try {
                groupManager.refreshLiveSubscriptions()
            } catch (_: Exception) {}
            // Re-fetch the new account's kind:3 so [following] (and the friends list)
            // repopulate: resetContactListState() cleared the prior account's, and a warm
            // swap does not reconstruct the screen ViewModel that requests it on cold boot.
            try {
                requestContactList()
            } catch (_: Exception) {}
        }
    }

    private fun initializeOutboxModel() {
        val pubKey = sessionManager.getPublicKey() ?: return
        outboxManager.initialize(
            pubKey = pubKey,
            messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) },
            onDiscoveryComplete = {
                _isDiscoveringRelays.value = false
                // Track bootstrap relays for reconnection so metadata fetches
                // keep working if a bootstrap relay drops mid-session.
                connectedPoolRelays.addAll(outboxManager.bootstrapRelays)
            },
        )
    }

    override suspend fun connect() {
        connect(connectionManager.currentRelayUrl.value)
    }

    /**
     * Manually trigger reconnection to the relay.
     * Use this when auto-reconnection fails or user wants to retry.
     */
    override fun triggerReconnect() {
        scope.launch { reconnect() }
    }

    override suspend fun reconnect(): Boolean {
        // Explicitly notify GroupManager that all in-flight loading is dead.
        // connectionManager.reconnect() calls primaryClient.disconnect() which
        // closes the old socket — but the `onConnectionLost` callback that
        // would normally cascade into GroupManager.handleConnectionLost() may
        // not fire in time (explicit disconnect doesn't always trigger the
        // close-event handler synchronously). Without this reset, controllers
        // left in InitialLoading from REQs sent on the dying socket reject
        // resubscribeAllGroups' new REQ calls (startInitialLoad only accepts
        // Idle/Error), so the new session never gets fresh data — chat sits
        // on skeletons until the controller's own ~10s timeout fires.
        groupManager.handleConnectionLost()
        val connected = connectionManager.reconnect()
        if (connected) {
            val client = connectionManager.getPrimaryClient()
            if (client != null) {
                resubscribeAllGroups(client)
                pendingEventManager?.onConnectionRestored()
            }
            reconnectDroppedNip29PoolRelays()
        }
        return connected
    }

    /**
     * Called when the app returns to the foreground.
     * - If the primary relay is disconnected or errored: triggers a full reconnect.
     * - If already connected: refreshes all live mux subscriptions and pool relays.
     */
    override fun onForeground() {
        scope.launch {
            val state = connectionManager.connectionState.value
            when (state) {
                // Only force reconnect if fully disconnected (no auto-reconnect running).
                // Error state means auto-reconnect exhausted Phase 1 — force a fresh attempt.
                is ConnectionManager.ConnectionState.Disconnected,
                is ConnectionManager.ConnectionState.Error,
                -> reconnect()

                // Auto-reconnect or initial connect in progress — don't interrupt.
                is ConnectionManager.ConnectionState.Reconnecting,
                is ConnectionManager.ConnectionState.Connecting,
                -> {
                    reconnectDroppedNip29PoolRelays()
                }

                // Already connected — refresh subscriptions and pool relays.
                is ConnectionManager.ConnectionState.Connected -> {
                    groupManager.refreshLiveSubscriptions()
                    reconnectDroppedNip29PoolRelays()
                }
            }
        }
    }

    /**
     * Called when the app moves to the background.
     * Persists live cursors to storage so they survive process death.
     */
    override fun onBackground() {
        scope.launch {
            liveCursorStore?.persistAll()
        }
    }

    /**
     * Called when the app process is about to be destroyed (Android onDestroy / process exit).
     * Persists all live cursors and disconnects gracefully.
     */
    override fun onDestroy() {
        scope.launch {
            liveCursorStore?.persistAll()
            groupManager.saveAllMessagesToStorage()
            connectionManager.disconnectPrimary()
        }
    }

    /**
     * Notify the repository which group the user is currently viewing.
     * The relay hosting [groupId] is promoted to [RelayReconnectScheduler.Priority.ACTIVE]
     * so reconnect attempts for it use faster (500 ms base) backoff.
     * Pass null when the user leaves the group screen to revert to BACKGROUND priority.
     */
    override fun setActiveGroup(groupId: String?) {
        activeRelayUrl = if (groupId != null) groupManager.getRelayForGroup(groupId) else null
        // Update mux subscriptions to scope chat/reactions to the active group only.
        groupManager.setActiveGroupId(groupId)
        // Suppress unread-counter bumps for the group currently on screen.
        unreadManager.setActiveGroup(groupId)
    }

    /**
     * Gap detection: after EOSE for the [mux_chat_] subscription, check each group on
     * [relayUrl] for evidence of a gap between the cursor and what was actually delivered.
     *
     * A gap is suspected when the newest in-memory message for a group is more than
     * [GAP_THRESHOLD_S] seconds older than what the cursor expected to have received.
     * In that case a targeted history fetch is scheduled to fill the hole.
     */
    private suspend fun detectAndFillGaps(relayUrl: String) {
        val now = epochSeconds()
        val lastAt = lastGapDetectionAt[relayUrl] ?: 0L
        if (now - lastAt < GAP_DETECTION_COOLDOWN_S) return
        lastGapDetectionAt[relayUrl] = now

        val groupIds = groupManager.getGroupIdsForMux(relayUrl)
        if (groupIds.isEmpty()) return

        for (groupId in groupIds) {
            val cursorSince = liveCursorStore?.getSince(relayUrl, groupId) ?: continue
            // getSince already subtracts RECONNECT_OVERLAP_S; add it back to get the actual
            // timestamp of the last event we KNOW we received.
            val lastKnown = cursorSince + LiveCursorStore.RECONNECT_OVERLAP_S
            val latestInMemory = groupManager.getLatestMessageTimestamp(groupId)

            val gapDetected = when {
                latestInMemory == null -> false // no messages loaded yet — normal cold start
                latestInMemory < lastKnown - GAP_THRESHOLD_S -> true // memory is stale
                else -> false
            }

            if (gapDetected) {
                val gapSec = lastKnown - (latestInMemory ?: 0)
                scope.launch {
                    try {
                        groupManager.requestGroupMessages(groupId)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Group ids we know on [relayUrl] WITHOUT listing the relay's full directory: our own joined
     * groups plus the groups in the kind:10009 lists of people we follow (and the discovery
     * curator). Restricted groups are excluded — the relay CLOSEs the whole batch if any single
     * id is denied.
     */
    private fun knownGroupIdsForRelay(relayUrl: String): List<String> {
        val normalized = relayUrl.normalizeRelayUrl()
        val restricted = groupManager.restrictedGroups.value.keys
        val ids = LinkedHashSet<String>()
        groupManager.joinedGroupsByRelay.value[normalized].orEmpty().forEach { ids.add(it) }
        val lists = _userGroupLists.value
        (_following.value + DISCOVERY_CURATOR_PUBKEY).forEach { pk ->
            lists[pk].orEmpty().forEach { ref ->
                if (ref.relayUrl.normalizeRelayUrl() == normalized) ids.add(ref.groupId)
            }
        }
        return ids.filter { it !in restricted }
    }

    // Group ids already requested (targeted #d) per normalized relay this session, so the
    // friends/curator re-fetch only fires when the known set actually grows.
    private val sentKnownGroupFetch = mutableMapOf<String, Set<String>>()

    /**
     * Fetch kind:39000 for ONLY the groups we know on [relayUrl] (see [knownGroupIdsForRelay]),
     * via a targeted #d REQ — the relay's full group directory is never downloaded. When nothing
     * is known yet, marks the relay loaded and wires the mux directly (no EOSE would arrive).
     */
    private suspend fun sendKnownGroupsFetch(client: NostrGroupClient, relayUrl: String) {
        groupManager.cancelPendingFullFetch(relayUrl)
        val ids = knownGroupIdsForRelay(relayUrl)
        if (ids.isEmpty()) {
            groupManager.markRelayLoaded(relayUrl)
            groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
            return
        }
        sentKnownGroupFetch[relayUrl.normalizeRelayUrl()] = ids.toSet()
        client.requestGroupsForIds(ids)
    }

    /**
     * Send the group-list REQ for a relay. Always targeted to the groups we know (joined +
     * friends' / curator lists); we deliberately never pull the relay's full group directory.
     */
    private suspend fun requestGroupsForRelay(client: NostrGroupClient, relayUrl: String) {
        sendKnownGroupsFetch(client, relayUrl)
    }

    private suspend fun connect(relayUrl: String) {
        if (relayUrl.isBlank()) return
        _relayMetadataManager.fetch(relayUrl)

        // Mark loading before the connection attempt so the skeleton shows during
        // the initial Connecting state on cold start. The state-flow observer in
        // initialize() clears this if the connection fails (Error/Reconnecting).
        groupManager.markRelayLoading(relayUrl)

        val connected = connectionManager.connectPrimary(relayUrl) { msg, client ->
            enqueueToRelayPipeline(msg, client)
        }

        if (connected) {
            val client = connectionManager.getPrimaryClient()
            if (client != null) {
                // Wait for a potential NIP-42 AUTH challenge before sending any REQ.
                // Relays like meta.spaces.coracle.social require auth first; sending
                // REQ before AUTH results in CLOSED "auth-required".
                val authHandled = client.awaitAuthOrTimeout()
                groupManager.restoreGroupsForRelay(relayUrl)
                if (!groupManager.hasCachedGroupsForRelay(relayUrl)) {
                    // If auth was handled, resubscribeAfterAuth already called
                    // requestGroups(); only request if auth was not needed.
                    if (!authHandled) {
                        lastRequestGroupsAt[relayUrl] = epochSeconds()
                        groupManager.markRelayLoading(relayUrl)
                        requestGroupsForRelay(client, relayUrl)
                    }
                } else if (
                    SecureStorage.isGroupFetchLazy(relayUrl) &&
                    relayUrl.normalizeRelayUrl() !in groupManager.fullGroupListFetchedRelays.value &&
                    SecureStorage.getBooleanPref("sidebar_other_expanded_$relayUrl", default = true)
                ) {
                    // Cache is fresh (partial, joined-only) but OTHER GROUPS is open and the full
                    // list was never fetched THIS SESSION. Trigger a full fetch now so the sidebar
                    // populates automatically on connect — don't make the user click the homescreen
                    // OTHER GROUPS tab. We check the in-memory set, not hasFullGroupListBeenFetched:
                    // a stale persisted timestamp (or a previous session's full fetch) would
                    // otherwise satisfy that guard while the cache holds only joined groups,
                    // leaving OTHER GROUPS empty until a manual fetch.
                    groupManager.markRelayLoading(relayUrl)
                    sendKnownGroupsFetch(client, relayUrl)
                } else {
                    // Cache hit — no EOSE will arrive, so clear the early loading mark.
                    groupManager.markRelayLoaded(relayUrl)
                    // No group-list REQ was sent, so the EOSE-driven mux setup
                    // (handleEoseSuspend -> refreshMuxSubscriptionsForRelay) never
                    // fires. Set up the live chat + metadata mux now, otherwise the
                    // primary relay's groups show "No messages yet" and "Members 0"
                    // until a re-AUTH or the 5-min periodic refresh. This path is hit
                    // whenever login hydrates joined groups from storage (#88), which
                    // makes the relay a cache hit and skips the group-list fetch.
                    groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
                }
                drainFullFetchRequest(client, relayUrl)
                // After AUTH (or if no AUTH needed), request metadata for private
                // groups that are in the joined list (kind 10009) but not in the
                // group cache. The general kind 39000 listing omits private groups.
                // Also request metadata for the active group if the user navigated
                // directly via URL (e.g. invite link) and the group isn't known yet.
                scope.launch {
                    // Wait for the group-list EOSE so we only request kind:39000 for
                    // groups genuinely missing from the public listing. Bounded fallback
                    // keeps slow relays from blocking the targeted fetches indefinitely.
                    groupManager.awaitGroupListEose(relayUrl)
                    groupManager.requestPrivateGroupData(relayUrl)
                    groupManager.requestActiveGroupMetadataIfMissing(relayUrl)
                }
            }
        }
    }

    private suspend fun autoConnectFirstRelay(relays: List<String>) {
        if (relays.isEmpty()) return
        if (connectionManager.getPrimaryClient() != null) return
        if (connectionManager.currentRelayUrl.value.isNotBlank()) return

        val primaryRelay = relays.first()
        _isDiscoveringRelays.value = false
        val pubkey = sessionManager.getPublicKey()
        if (pubkey != null) {
            SecureStorage.saveCurrentRelayUrlFor(pubkey, primaryRelay)
        }
        connectionManager.loadSavedRelay()
        liveCursorStore?.loadAll(relays)
        if (pubkey != null) {
            groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
            groupManager.loadAllJoinedGroupsFromStorage(pubkey, relays)
            groupManager.restoreJoinedGroupMetadataFromStorage(pubkey, relays)
            groupManager.restoreGroupMembershipFromStorage(pubkey)
            groupManager.migrateMessageBlobsToCache(pubkey)
            groupManager.loadRestrictedGroupsFromStorage(pubkey, relays)
        }
        connect(primaryRelay)
    }

    override suspend fun switchRelay(newRelayUrl: String) {
        _targetSwitchRelayUrl.value = newRelayUrl

        // Skip if already on this relay — avoids unnecessary disconnect/reconnect/AUTH cycle.
        // Also skip if a connect to the same relay is in flight: deep-link cold-start
        // fires repo.switchRelay() from AppShell's useEffectOnce after initialize()
        // has already kicked off connect(primaryRelay) but before primaryClient is set.
        // Without this guard, switchRelay nulls the in-flight primaryClient and opens
        // a duplicate socket on the same URL — observed as a doomed second WebSocket
        // attempt to groups.0xchat.com (handshake fails, ~1.7 s lost to backoff).
        val sameRelay = newRelayUrl == connectionManager.currentRelayUrl.value
        if (sameRelay) {
            val state = connectionManager.connectionState.value
            val healthy = connectionManager.getPrimaryClient()?.isConnected() == true
            val connectInFlight = state is ConnectionManager.ConnectionState.Connecting ||
                state is ConnectionManager.ConnectionState.Reconnecting
            if (healthy || connectInFlight) return
        }

        val normalized = newRelayUrl.normalizeRelayUrl()

        // If this relay previously returned "restricted", show the cached
        // error immediately instead of reconnecting and skeleton-loading.
        val restriction = _restrictedRelays.value[normalized]
        if (restriction != null) {
            connectionManager.switchRelay(newRelayUrl) { msg, client ->
                enqueueToRelayPipeline(msg, client)
            }
            connectionManager.setError(restriction)
            return
        }

        _relayMetadataManager.fetch(newRelayUrl)

        // Mark the new relay as loading BEFORE clearing groups so the UI
        // shows skeleton loaders immediately instead of a flash of empty state.
        groupManager.markRelayLoading(newRelayUrl)

        // Clears messages/state but NOT the group metadata cache (_groupsByRelay).
        groupManager.clearForRelaySwitch()

        if (_targetSwitchRelayUrl.value != newRelayUrl) return

        connectionManager.switchRelay(newRelayUrl) { msg, client ->
            enqueueToRelayPipeline(msg, client)
        }

        if (_targetSwitchRelayUrl.value != newRelayUrl) return

        val pubKey = sessionManager.getPublicKey() ?: ""

        groupManager.restoreGroupsForRelay(newRelayUrl)

        val outboxCached = outboxManager.getJoinedGroupsForRelay(newRelayUrl)
        val inMemoryCached = groupManager.joinedGroupsByRelay.value[normalized] ?: emptySet()
        val cachedJoined = outboxCached + inMemoryCached
        if (cachedJoined.isNotEmpty()) {
            groupManager.setJoinedGroups(cachedJoined)
        } else {
            groupManager.loadJoinedGroupsFromStorage(pubKey, newRelayUrl)
        }

        val client = connectionManager.getPrimaryClient()
        if (client != null) {
            // Wait for a potential NIP-42 AUTH challenge before sending any REQ.
            val authHandled = client.awaitAuthOrTimeout()
            if (_targetSwitchRelayUrl.value != newRelayUrl) return
            // Skip re-fetch if cached; re-fetching races against restored state.
            if (!groupManager.hasCachedGroupsForRelay(newRelayUrl)) {
                // Always request groups here. Even if AUTH was already handled
                // (authHandled == true), resubscribeAfterAuth only calls
                // requestGroups() for the primary client — if this client was
                // promoted from the pool (e.g. connected by a link preview),
                // AUTH happened while it was a pool client and requestGroups()
                // was never sent.
                requestGroupsForRelay(client, newRelayUrl)
            } else if (
                SecureStorage.isGroupFetchLazy(newRelayUrl) &&
                newRelayUrl.normalizeRelayUrl() !in groupManager.fullGroupListFetchedRelays.value &&
                SecureStorage.getBooleanPref("sidebar_other_expanded_$newRelayUrl", default = true)
            ) {
                // Cache holds only the joined-group subset (from a previous lazy
                // fetch) but OTHER GROUPS is open and this session hasn't
                // received a full EOSE — fire the full fetch so the sidebar
                // populates instead of flashing "no other groups". Mirrors the
                // equivalent branch in connect(). We check the in-memory session
                // set rather than hasFullGroupListBeenFetched so a stale persisted
                // timestamp can't suppress the auto-fetch on switching to a relay.
                groupManager.markRelayLoading(newRelayUrl)
                sendKnownGroupsFetch(client, newRelayUrl)
            } else {
                // Cache was restored — no EOSE will arrive, so unmark loading now.
                groupManager.markRelayLoaded(newRelayUrl)
                // As in connect(): a cache hit sends no group-list REQ, so the
                // EOSE-driven mux setup won't fire. Refresh the chat + metadata mux
                // for the new primary now so messages/members load instead of
                // showing "No messages yet" / "Members 0".
                groupManager.refreshMuxSubscriptionsForRelay(newRelayUrl)
            }
            drainFullFetchRequest(client, newRelayUrl)
            // Request metadata for private groups not in the cache (kind 10009 joined
            // but not returned by the general kind 39000 listing), and for the active
            // group if navigated via URL before the relay was connected.
            scope.launch {
                // Wait for the group-list EOSE before requesting per-group metadata —
                // see the equivalent block in connect() for rationale.
                groupManager.awaitGroupListEose(newRelayUrl)
                groupManager.requestPrivateGroupData(newRelayUrl)
                groupManager.requestActiveGroupMetadataIfMissing(newRelayUrl)
            }
        } else if (groupManager.hasCachedGroupsForRelay(newRelayUrl)) {
            // No connection yet but cache was restored — unmark loading.
            groupManager.markRelayLoaded(newRelayUrl)
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

        // Ensure every other joined relay also has a live mux chat sub.
        scope.launch { ensureJoinedRelaysConnected(newRelayUrl) }
    }

    override suspend fun removeRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        val existing = outboxManager.kind10009Relays.value.toList()
        val remaining = existing.filter { it != normalized }
        val pubKey = sessionManager.getPublicKey()

        // Apply removal locally first so the relay vanishes even when the relay is offline.
        // The kind:10009 publish is attempted afterwards; failure is non-fatal — the local
        // removal and the persisted timestamp guard in OutboxManager prevent resurrection.
        if (pubKey != null) {
            SecureStorage.saveRelayListFor(pubKey, remaining)
            SecureStorage.clearJoinedGroupsForRelay(pubKey, normalized)
        }
        groupManager.removeRelayEntry(normalized)
        outboxManager.removeRelayFromCache(normalized)

        val fallback = remaining.firstOrNull()
        if (fallback != null && fallback != connectionManager.currentRelayUrl.value.normalizeRelayUrl()) {
            switchRelay(fallback)
        } else if (fallback == null) {
            if (pubKey != null) SecureStorage.clearCurrentRelayUrlFor(pubKey)
            connectionManager.clearCurrentRelay()
        }
        connectionManager.disconnectRelay(normalized)
        connectedPoolRelays.remove(normalized)

        // Publish updated kind:10009 to remaining relays. Signer denial is the only error
        // that would matter here (user explicitly cancelled), but at this point the relay is
        // already gone locally, so we just fire-and-forget.
        if (pubKey != null) {
            publishJoinedGroupsListWith(pubKey, nip29Relays = remaining)
        }
    }

    override suspend fun disconnect() {
        connectionManager.disconnectPrimary()
        groupManager.clear()
    }

    override fun setGroupFetchLazy(relayUrl: String, lazy: Boolean) {
        SecureStorage.saveGroupFetchLazy(relayUrl, lazy)
    }

    override fun isGroupFetchLazy(relayUrl: String): Boolean = SecureStorage.isGroupFetchLazy(relayUrl)

    override val fullGroupListFetchedRelays: StateFlow<Set<String>> =
        groupManager.fullGroupListFetchedRelays

    override suspend fun requestFullGroupListForRelay(relayUrl: String) {
        // Only guard against in-flight duplicates — hasFullGroupListBeenFetched is intentionally
        // NOT checked here: a stale persisted timestamp can make it return true when the current
        // session has no data, silently blocking user-triggered fetches.
        if (groupManager.hasPendingFullFetch(relayUrl)) return

        val normalizedTarget = relayUrl.normalizeRelayUrl()

        // We must NOT fall back to the current primary: the sidebar triggers this
        // right after selectedRelayUrl changes, BEFORE switchRelay() has made the
        // new relay primary. Falling back would send requestGroups() on the old
        // primary's WebSocket, polluting that relay's _groupsByRelay cache with
        // unrelated kind:39000 events — surfacing as OTHER GROUPS auto-populating
        // on a collapsed relay the next time the user switches back to it.
        //
        // If the target client isn't ready yet (typically: still connecting / awaiting
        // AUTH during a relay switch), enqueue the request and let
        // [drainFullFetchRequest] fire it from the connect/switchRelay/resubscribeAfterAuth
        // post-AUTH path — avoiding the previous 30 s polling wait that was
        // particularly painful on Android.
        val client = connectionManager.getClientForRelay(normalizedTarget)
        val ready = client != null &&
            client.isConnected() &&
            client.getRelayUrl().normalizeRelayUrl() == normalizedTarget
        if (!ready) {
            pendingFullFetchMutex.withLock { pendingFullFetchRequests.add(normalizedTarget) }
            // Mark loading right away so the sidebar shows the spinner from the
            // moment the user clicks — instead of flashing "no other groups"
            // until the post-AUTH drain fires the REQ.
            groupManager.markRelayLoading(relayUrl)
            return
        }

        groupManager.markRelayLoading(relayUrl)
        sendKnownGroupsFetch(client!!, relayUrl)
    }

    /**
     * Fire any pending full-fetch request the UI enqueued for [relayUrl] while
     * [client] wasn't ready. Called from connect()/switchRelay()/resubscribeAfterAuth
     * after AUTH completes. No-op if no pending request, if the connect-path
     * already fired the REQ (hasPendingFullFetch), or if [client] mismatches.
     */
    private suspend fun drainFullFetchRequest(client: NostrGroupClient, relayUrl: String) {
        val normalized = relayUrl.normalizeRelayUrl()
        val requested = pendingFullFetchMutex.withLock {
            pendingFullFetchRequests.remove(normalized)
        }
        if (!requested) return
        if (groupManager.hasPendingFullFetch(relayUrl)) return
        if (!client.isConnected()) return
        if (client.getRelayUrl().normalizeRelayUrl() != normalized) return
        groupManager.markRelayLoading(relayUrl)
        sendKnownGroupsFetch(client, relayUrl)
    }

    override suspend fun addRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        val alreadyInKind10009 = normalized in outboxManager.kind10009Relays.value
        if (!alreadyInKind10009) {
            val existing = outboxManager.kind10009Relays.value.toList()
            val newList = (existing + normalized).distinct()
            val pubKey = sessionManager.getPublicKey()
            if (!pubKey.isNullOrEmpty()) {
                val result = publishJoinedGroupsListWith(pubKey, nip29Relays = newList)
                if (result is Result.Success) {
                    SecureStorage.saveRelayListFor(pubKey, newList)
                } else {
                    return // signer denied or publish failed — don't save
                }
            }
        }
        // Clear deep link prompt if this was the pending relay
        if (_pendingDeepLinkRelay.value == normalized) {
            _pendingDeepLinkRelay.value = null
        }
    }

    override fun dismissDeepLinkRelay() {
        _pendingDeepLinkRelay.value = null
    }

    // Auth delegation
    override fun getPublicKey(): String? = sessionManager.getPublicKey()
    override fun getPrivateKey(): String? = sessionManager.getPrivateKey()
    override fun isUsingBunker(): Boolean = sessionManager.isUsingBunker()
    override fun isBunkerReady(): Boolean = sessionManager.isBunkerReady()
    override suspend fun ensureBunkerConnected(): Boolean = sessionManager.ensureBunkerConnected()

    // Group operations
    override suspend fun joinGroup(groupId: String, inviteCode: String?): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val result = groupManager.joinGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
            inviteCode = inviteCode,
        )
        if (result is Result.Success) {
            // Joining a group may have introduced a new joined relay — ensure it's
            // connected with a chat sub so notifications fire even when the user is
            // browsing a different primary.
            scope.launch { ensureJoinedRelaysConnected(connectionManager.currentRelayUrl.value) }
        }
        return result
    }

    override fun markOptimisticJoin(relayUrl: String, groupId: String): Boolean = groupManager.markOptimisticJoin(relayUrl, groupId)

    override fun revertOptimisticJoin(relayUrl: String, groupId: String) = groupManager.revertOptimisticJoin(relayUrl, groupId)

    override suspend fun createGroup(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        customGroupId: String?,
    ): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        if (relayUrl != connectionManager.currentRelayUrl.value) {
            switchRelay(relayUrl)
        }
        val result = groupManager.createGroup(
            name = name,
            about = about,
            picture = picture,
            isPrivate = isPrivate,
            isClosed = isClosed,
            isRestricted = isRestricted,
            isHidden = isHidden,
            customGroupId = customGroupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
        )
        if (result is Result.Success) {
            scope.launch { ensureJoinedRelaysConnected(connectionManager.currentRelayUrl.value) }
        }
        return result
    }

    override suspend fun createSubgroup(
        parentGroupId: String,
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        customGroupId: String?,
    ): Result<String> {
        val created = createGroup(name, about, relayUrl, isPrivate, isClosed, isRestricted, isHidden, picture, customGroupId)
        if (created !is Result.Success) return created
        // Attach to parent via kind:9002.
        val topology = updateGroupTopology(
            groupId = created.data,
            parent = GroupManager.ParentOp.SetTo(parentGroupId),
        )
        return when (topology) {
            is Result.Success -> created
            is Result.Error -> Result.Error(topology.error)
        }
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
            publishJoinedGroups = { publishJoinedGroupsList() },
        )
    }

    override suspend fun forgetGroup(groupId: String, relayUrl: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
        val changed = groupManager.forgetJoinedPin(groupId, relayUrl, pubKey)
        if (changed) publishJoinedGroupsList()
        return Result.Success(Unit)
    }

    override val orphanedJoinedByRelay: StateFlow<Map<String, Set<String>>>
        get() = groupManager.orphanedJoinedByRelay

    override fun isGroupJoined(groupId: String): Boolean = groupManager.isGroupJoined(groupId)

    override suspend fun requestGroupMessages(groupId: String, channel: String?) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupMessages(groupId, channel)
        // Also request group metadata (kind 39000), members (kind 39002) and admins (kind 39001)
        val relayUrl = groupManager.getRelayForGroup(groupId)
        val metaClient = if (relayUrl != null) connectionManager.getClientForRelay(relayUrl) else null
        (metaClient ?: connectionManager.getPrimaryClient())?.requestGroupMetadata(groupId)
        groupManager.requestGroupMembers(groupId)
        groupManager.requestGroupAdmins(groupId)
        groupManager.requestGroupRoles(groupId)
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

    /**
     * Request pending join requests (kind 9021 + 9022) for a group. Admin-only
     * use case — the standard chat REQ misses old 9021s in active groups.
     */
    override suspend fun requestPendingJoinRequests(groupId: String) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestPendingJoinRequests(groupId)
    }

    /**
     * Fire-and-forget NIP-11 fetch for [relayUrl]. Powers the suggested-relay
     * cards in the AddRelay modal; deduplicated inside [RelayMetadataManager].
     */
    override fun fetchRelayMetadata(relayUrl: String) {
        _relayMetadataManager.fetch(relayUrl)
    }

    /**
     * Request group roles (kind 39003) for a specific group.
     */
    suspend fun requestGroupRoles(groupId: String) {
        if (connectionManager.getPrimaryClient() == null) {
            connect()
        }
        groupManager.requestGroupRoles(groupId)
    }

    override val childrenByParent: StateFlow<Map<String, Set<String>>> = groupManager.childrenByParent
    override val unverifiedChildren: StateFlow<Set<String>> = groupManager.unverifiedChildren

    override suspend fun refreshGroupMetadata(groupId: String) {
        val relayUrl = groupManager.getRelayForGroup(groupId)
        val client = (if (relayUrl != null) connectionManager.getClientForRelay(relayUrl) else null)
            ?: connectionManager.getPrimaryClient()
            ?: return
        client.requestGroupMetadata(groupId)
        client.requestGroupAdmins(groupId)
    }

    override suspend fun fetchGroupPreview(groupId: String, relayUrl: String) {
        // Already have metadata for this group — skip
        val existing = groupManager.groupsByRelay.value.values.flatten().find { it.id == groupId }
        if (existing?.name != null) return

        try {
            val client = connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                enqueueToRelayPipeline(msg, c)
            } ?: return
            connectedPoolRelays.add(relayUrl)
            client.requestGroupMetadata(groupId)
        } catch (_: Exception) {}
    }

    override suspend fun fetchGroupPreviews(relayToGroups: Map<String, Set<String>>) {
        // Skip groups whose metadata we already have a name for.
        val known = groupManager.groupsByRelay.value.values.flatten()
            .filter { it.name != null }
            .map { it.id }
            .toSet()
        relayToGroups.forEach { (relayUrl, groupIds) ->
            if (relayUrl.isBlank()) return@forEach
            val missing = groupIds.filter { it !in known }
            if (missing.isEmpty()) return@forEach
            try {
                // getOrConnectRelay is a pooled singleflight: one connection per relay
                // even across concurrent callers, so duplicate relays never stack up.
                val client = connectionManager.getOrConnectRelay(relayUrl) { msg, c ->
                    enqueueToRelayPipeline(msg, c)
                } ?: return@forEach
                connectedPoolRelays.add(relayUrl)
                client.requestGroupsMetadata(missing)
            } catch (_: Exception) {}
        }
    }

    override suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean,
        picture: String?,
        parentOp: GroupManager.ParentOp?,
        childrenEdit: GroupManager.ChildrenEdit?,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        val result = groupManager.editGroup(
            groupId = groupId,
            name = name,
            about = about,
            picture = picture,
            isPrivate = isPrivate,
            isClosed = isClosed,
            isRestricted = isRestricted,
            isHidden = isHidden,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            parentOp = parentOp,
            childrenEdit = childrenEdit,
        )
        if (result is Result.Success) refreshGroupMetadata(groupId)
        return result
    }

    override suspend fun deleteGroup(groupId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.deleteGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
        )
    }

    override suspend fun updateGroupTopology(
        groupId: String,
        parent: GroupManager.ParentOp?,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.updateGroupTopology(
            groupId = groupId,
            parent = parent,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun updateChildren(
        groupId: String,
        children: List<org.nostr.nostrord.network.DeclaredChild>,
        closedChildren: Boolean,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.updateChildren(
            groupId = groupId,
            children = children,
            closedChildren = closedChildren,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun loadMoreMessages(groupId: String, channel: String?): Boolean = groupManager.loadMoreMessages(groupId, channel)

    override suspend fun fetchGroupMessageById(groupId: String, messageId: String) {
        groupManager.fetchGroupMessageById(groupId, messageId)
    }

    override suspend fun sendMessage(groupId: String, content: String, channel: String?, mentions: Map<String, String>, replyToMessageId: String?, extraTags: List<List<String>>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendMessage(
            groupId = groupId,
            content = content,
            pubKey = pubKey,
            channel = channel,
            mentions = mentions,
            replyToMessageId = replyToMessageId,
            extraTags = extraTags,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override fun retrySend(eventId: String) = groupManager.retrySend(eventId)

    override fun dismissFailed(groupId: String, eventId: String) = groupManager.dismissFailed(groupId, eventId)

    override suspend fun addUser(groupId: String, targetPubkey: String, roles: List<String>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.addUser(
            groupId = groupId,
            targetPubkey = targetPubkey,
            roles = roles,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun removeUser(groupId: String, targetPubkey: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.removeUser(
            groupId = groupId,
            targetPubkey = targetPubkey,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun rejectJoinRequest(groupId: String, joinRequestEventId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.rejectJoinRequest(
            groupId = groupId,
            joinRequestEventId = joinRequestEventId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun createInviteCode(groupId: String): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.createInviteCode(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun revokeInviteCode(groupId: String, eventId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.revokeInviteCode(
            groupId = groupId,
            eventId = eventId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun deleteMessage(groupId: String, messageId: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.deleteMessage(
            groupId = groupId,
            messageId = messageId,
            pubKey = pubKey,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun sendReaction(groupId: String, targetEventId: String, targetPubkey: String, emoji: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendReaction(
            groupId = groupId,
            targetEventId = targetEventId,
            targetPubkey = targetPubkey,
            emoji = emoji,
            pubKey = pubKey,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun requestZapInvoice(
        recipientPubkey: String,
        amountSats: Long,
        comment: String,
        eventId: String?,
    ): Result<ZapManager.ZapInvoice> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return zapManager.requestInvoice(
            recipientPubkey = recipientPubkey,
            amountSats = amountSats,
            comment = comment,
            eventId = eventId,
            senderPubkey = pubKey,
            signEvent = { sessionManager.signEvent(it) },
        )
    }

    override suspend fun watchZapPayment(
        bolt11: String,
        recipientPubkey: String,
        eventId: String?,
    ): Boolean {
        // Poll the receipt relays for the matching kind:9735 while awaiting the flow.
        val matched = withTimeoutOrNull(ZAP_PAYMENT_WATCH_MS) {
            coroutineScope {
                val poller = launch {
                    while (isActive) {
                        pollZapReceipts(recipientPubkey, eventId)
                        delay(ZAP_PAYMENT_POLL_MS)
                    }
                }
                try {
                    zapManager.paidInvoices.first { it.equals(bolt11, ignoreCase = true) }
                } finally {
                    poller.cancel()
                }
            }
        }
        return matched != null
    }

    /** One poll cycle: re-request the zap receipt from connected + general relays. */
    private suspend fun pollZapReceipts(recipientPubkey: String, eventId: String?) {
        // Same relays the receipt was asked to be published to (see ZapManager.receiptRelays).
        zapManager.receiptRelays().forEach { url ->
            try {
                val client = connectionManager.getClientForRelay(url)
                    ?: connectionManager.getOrConnectRelay(url) { msg, c -> enqueueToRelayPipeline(msg, c) }
                if (client != null && client.isConnected()) {
                    if (eventId != null) {
                        client.requestZapReceipts(listOf(eventId))
                    } else {
                        client.requestZapReceiptsForRecipient(recipientPubkey)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
    }

    override fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> = groupManager.getMessagesForGroup(groupId)

    // Unread message operations
    override fun markGroupAsRead(groupId: String) {
        unreadManager.markAsRead(groupId)
    }

    override fun markGroupAsReadUpTo(groupId: String, timestamp: Long) {
        unreadManager.markAsReadUpTo(groupId, timestamp)
    }

    override fun getUnreadCount(groupId: String): Int = unreadManager.getUnreadCount(groupId)

    override fun getLastReadTimestamp(groupId: String): Long? = unreadManager.getLastReadTimestamp(groupId)

    // Metadata operations
    private val metadataMessageHandler: (String, NostrGroupClient) -> Unit = { msg, client ->
        handleRelayMessage(msg, client)
    }

    override suspend fun requestUserMetadata(pubkeys: Set<String>, forceStale: Boolean) {
        metadataManager.requestUserMetadata(pubkeys, metadataMessageHandler, forceStale)
    }

    override suspend fun requestUserGroupList(pubkey: String) {
        // Own list flows through the joined-groups state; nothing to fetch here.
        if (pubkey.isBlank() || pubkey == sessionManager.getPublicKey()) return
        // kind:10009 lives on general-purpose relays (author outbox + bootstrap).
        // Connect on demand: when a discovery tab opens, those may not be connected
        // yet, and a connected-only filter would silently send nothing.
        val targets = outboxManager.selectOutboxRelays(authors = listOf(pubkey))
        targets.forEach { relayUrl ->
            runCatching {
                val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                    ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)
                client?.takeIf { it.isConnected() }?.requestUserGroupList(pubkey)
            }
        }
        // The primary NIP-29 relay often hosts members' lists too.
        val primary = connectionManager.getPrimaryClient()
        if (primary != null && primary.isConnected()) {
            runCatching { primary.requestUserGroupList(pubkey) }
        }
    }

    // Serialize check-and-set: the Home and Profile view models both fire this on
    // startup, and without the lock both pass the `contactListRequested` guard
    // before either latches it (the guard is set only after the suspending relay
    // send), doubling the kind:3 REQ to every relay.
    override suspend fun requestContactList() {
        val sentOrCached = contactListMutex.withLock {
            requestContactListLocked()
            contactListRequested
        }
        // Only declare the list "loaded" once a REQ actually went out (or we already
        // had it): offline, leave it unloaded so the UI keeps showing the cache rather
        // than a false "follows nobody". Mark loaded after the fetch resolves, even
        // when it comes back empty.
        if (!sentOrCached) return
        withTimeoutOrNull(4_000L) { following.first { contactListCreatedAt > 0L } }
        _contactListLoaded.value = true
    }

    /** Body of [requestContactList]; caller must hold [contactListMutex]. */
    private suspend fun requestContactListLocked() {
        val pubKey = sessionManager.getPublicKey() ?: return
        if (contactListRequested) return

        // kind:3 lives on general-purpose relays, never the NIP-29 group relay
        // (NIP-29 relays don't serve kind:0/3), so exclude them and the primary.
        val nip29Relays = outboxManager.kind10009Relays.value + connectionManager.currentRelayUrl.value
        val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
            .distinct()
            .filter { it !in nip29Relays }

        // Connect bootstrap relays on demand: at cold start they are not yet up, so
        // a connected-only filter would send nothing. We only latch the request once
        // at least one relay actually received the REQ — otherwise the one-shot VM
        // call would permanently suppress the fetch after a restart.
        var sent = 0
        targets.forEach { relayUrl ->
            runCatching {
                val client = connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                    ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)
                if (client != null && client.isConnected()) {
                    client.requestContactList(pubKey)
                    sent++
                }
            }
        }
        if (sent > 0) contactListRequested = true
    }

    override suspend fun followUser(pubkey: String): Result<Unit> {
        if (pubkey.isNotBlank()) applyFollowingChange { it + pubkey }
        return Result.Success(Unit)
    }

    override suspend fun unfollowUser(pubkey: String): Result<Unit> {
        applyFollowingChange { it - pubkey }
        return Result.Success(Unit)
    }

    override suspend fun followUsers(pubkeys: Set<String>): Result<Unit> {
        val clean = pubkeys.filter { it.isNotBlank() }.toSet()
        if (clean.isNotEmpty()) applyFollowingChange { it + clean }
        return Result.Success(Unit)
    }

    /**
     * Flips [following] to the new desired set immediately (no relay round-trip, so
     * rapid sequential follow taps never block on each other) and schedules a single
     * debounced kind:3 publish. [following] is the source of truth for the user's
     * intent; the publish rebuilds the list from it.
     */
    private fun applyFollowingChange(transform: (Set<String>) -> Set<String>) {
        _following.value = transform(_following.value)
        hasUnpublishedContactChanges = true
        schedulePublishContactList()
    }

    /**
     * Coalesces rapid follow/unfollow taps into one publish: each tap cancels the
     * previous pending job and restarts the debounce, so N taps in a row produce a
     * single kind:3 instead of N racing events. Runs on the app scope so it outlives
     * the screen that triggered it (navigating away never loses a follow).
     */
    private fun schedulePublishContactList() {
        pendingContactListPublish?.cancel()
        pendingContactListPublish =
            scope.launch {
                delay(contactListPublishDebounceMs)
                publishContactList()
            }
    }

    /**
     * Builds, signs and publishes a fresh kind:3 from the current [following] set,
     * preserving non-"p" tags (relay hints, petnames) and content from the last known
     * list. Serialized by [contactListMutex]. On the first run it best-effort fetches
     * the existing list so we don't replace a list built on another client.
     */
    private suspend fun publishContactList(): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return contactListMutex.withLock {
            if (!contactListRequested) {
                requestContactListLocked() // already holding contactListMutex
                // Wait briefly for a relay to return the existing list before we
                // overwrite it; absence is treated as "no prior list" after this.
                withTimeoutOrNull(3_000L) { following.first { contactListCreatedAt > 0L } }
            }

            // Rebuild "p" tags from the desired set; keep any other tags + content.
            val newTags = contactListTags.filterNot { it.firstOrNull() == "p" } +
                _following.value.map { listOf("p", it) }
            try {
                val event = org.nostr.nostrord.nostr.Event(
                    pubkey = pubKey,
                    createdAt = org.nostr.nostrord.utils.epochSeconds(),
                    kind = 3,
                    tags = newTags,
                    content = contactListContent,
                )
                val signedEvent = sessionManager.signEvent(event)
                val eventId = signedEvent.id
                    ?: return@withLock Result.Error(AppError.Unknown("Event has no id after signing", null))
                val message = buildJsonArray {
                    add("EVENT")
                    add(signedEvent.toJsonObject())
                }.toString()

                // Publish kind:3 only to general-purpose relays (write + bootstrap),
                // never the NIP-29 group relay: it doesn't serve kind:3 back, so a
                // list written only there would read as empty on the next start.
                val nip29Relays = outboxManager.kind10009Relays.value + connectionManager.currentRelayUrl.value
                val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays)
                    .distinct()
                    .filter { it !in nip29Relays }
                val clients = targets.mapNotNull { relayUrl ->
                    connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
                        ?: connectionManager.getOrConnectRelay(relayUrl, metadataMessageHandler)?.takeIf { it.isConnected() }
                }
                if (clients.isEmpty()) {
                    return@withLock Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
                }
                val results = clients.map { client ->
                    scope.async { client.sendAndAwaitOkOrError(message, eventId) }
                }.awaitAll()
                if (results.none { it is PublishResult.Success }) {
                    return@withLock Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
                }

                contactListCreatedAt = signedEvent.createdAt
                contactListContent = signedEvent.content
                contactListTags = newTags
                contactListRequested = true
                _contactListLoaded.value = true
                // The desired set is now on the relays; later relay echoes may adopt freely.
                hasUnpublishedContactChanges = false
                Result.Success(Unit)
            } catch (e: Exception) {
                Result.Error(AppError.Unknown(e.message ?: "Failed to update contact list", e))
            }
        }
    }

    private suspend fun refreshVisibleUserMetadata() {
        // Wait for resubscribeAllGroups REQs to deliver events before collecting pubkeys.
        // Without this delay, messages/members may still be empty from the previous session.
        delay(3_000)

        val openedGroups = groupManager.getOpenedGroupIds()
        val groupPubkeys = openedGroups.flatMap { groupId ->
            val messagePubkeys = groupManager.messages.value[groupId]
                ?.takeLast(50)?.map { it.pubkey } ?: emptyList()
            val memberPubkeys = groupManager.getMembersForGroup(groupId)
            messagePubkeys + memberPubkeys
        }
        // Also revalidate the followed users shown in the home sidebar so a friend's new
        // name/avatar appears without having to open a group. Only stale entries are
        // refetched (forceStale), and the request batches them into one REQ per relay.
        val pubkeys = (groupPubkeys + _following.value).toSet().filter { metadataManager.isStale(it) }.toSet()
        if (pubkeys.isNotEmpty()) {
            metadataManager.requestUserMetadata(pubkeys, metadataMessageHandler, forceStale = true)
        }
    }

    /**
     * Build a NIP-98 Authorization header value for HTTP requests.
     * Returns "Nostr <base64>" or null if not authenticated.
     */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun buildNip98AuthHeader(url: String, method: String): String? {
        val pubKey = sessionManager.getPublicKey() ?: return null
        val event = org.nostr.nostrord.nostr.Event(
            pubkey = pubKey,
            createdAt = org.nostr.nostrord.utils.epochMillis() / 1000,
            kind = 27235,
            tags = listOf(listOf("u", url), listOf("method", method)),
            content = "",
        )
        val signed = try {
            sessionManager.signEvent(event)
        } catch (_: Throwable) {
            return null
        }
        val json = signed.toJsonObject().toString()
        val encoded = kotlin.io.encoding.Base64.encode(json.encodeToByteArray())
        return "Nostr $encoded"
    }

    /**
     * Update the current user's profile metadata (kind 0 event).
     */
    override suspend fun updateProfileMetadata(
        displayName: String?,
        name: String?,
        about: String?,
        picture: String?,
        banner: String?,
        nip05: String?,
        lud16: String?,
        website: String?,
    ): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)

        return try {
            // Start from the existing raw JSON so unknown fields are preserved.
            var existing = metadataManager.getMetadata(pubKey)

            // If cache is empty, try to fetch fresh metadata before saving.
            // This avoids losing unknown fields when the cache was evicted or not yet loaded.
            // If nothing comes back after the timeout, treat as a new user (no prior kind:0).
            if (existing == null) {
                requestUserMetadata(setOf(pubKey))
                existing = withTimeoutOrNull(5_000L) {
                    metadataManager.userMetadata.first { it.containsKey(pubKey) }
                }?.get(pubKey)
            }

            val base: Map<String, JsonElement> = existing?.rawContentJson?.let { raw ->
                try {
                    Json.parseToJsonElement(raw).jsonObject.toMap()
                } catch (_: Exception) {
                    null
                }
            } ?: emptyMap()

            val merged = buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                displayName?.let { put("display_name", JsonPrimitive(it)) }
                name?.let { put("name", JsonPrimitive(it)) }
                about?.let { put("about", JsonPrimitive(it)) }
                picture?.let { put("picture", JsonPrimitive(it)) }
                banner?.let { put("banner", JsonPrimitive(it)) }
                nip05?.let { put("nip05", JsonPrimitive(it)) }
                lud16?.let { put("lud16", JsonPrimitive(it)) }
                website?.let { put("website", JsonPrimitive(it)) }
            }
            val content = merged.toString()

            val event = org.nostr.nostrord.nostr.Event(
                pubkey = pubKey,
                createdAt = org.nostr.nostrord.utils.epochSeconds(),
                kind = 0,
                tags = emptyList(),
                content = content,
            )

            // Sign the event
            val signedEvent = sessionManager.signEvent(event)

            // Build event message in correct format
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to write relays + bootstrap relays so other clients can discover
            // the updated profile (kind:0) via general-purpose relays.
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))
            val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
            val clients = targets.mapNotNull { relayUrl ->
                connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
            }.ifEmpty {
                listOfNotNull(connectionManager.getPrimaryClient())
            }
            if (clients.isEmpty()) {
                return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
            }
            val results = clients.map { client ->
                scope.async { client.sendAndAwaitOkOrError(message, eventId) }
            }.awaitAll()
            if (results.none { it is PublishResult.Success }) {
                return Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
            }

            val updatedMetadata = UserMetadata(
                pubkey = pubKey,
                name = name ?: existing?.name,
                displayName = displayName ?: existing?.displayName,
                picture = picture ?: existing?.picture,
                about = about ?: existing?.about,
                nip05 = nip05 ?: existing?.nip05,
                banner = existing?.banner,
                rawContentJson = content,
            )
            metadataManager.updateLocalMetadata(pubKey, updatedMetadata, signedEvent.createdAt)

            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to update profile", e))
        }
    }

    override suspend fun publishRelayList(relays: List<org.nostr.nostrord.network.outbox.Nip65Relay>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return try {
            val event = org.nostr.nostrord.nostr.Event(
                pubkey = pubKey,
                createdAt = org.nostr.nostrord.utils.epochSeconds(),
                kind = 10002,
                tags = relays.map { it.toTag() },
                content = "",
            )
            val signedEvent = sessionManager.signEvent(event)
            val eventId = signedEvent.id ?: return Result.Error(AppError.Unknown("Event has no id after signing", null))
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to current write relays + bootstrap to ensure the new list is
            // discoverable even when switching relay sets entirely.
            val targets = (outboxManager.getWriteRelays() + outboxManager.bootstrapRelays).distinct()
            val clients = targets.mapNotNull { relayUrl ->
                connectionManager.getClientForRelay(relayUrl)?.takeIf { it.isConnected() }
            }.ifEmpty {
                listOfNotNull(connectionManager.getPrimaryClient())
            }
            if (clients.isEmpty()) {
                return Result.Error(AppError.Network.Disconnected(connectionManager.currentRelayUrl.value))
            }
            val results = clients.map { client ->
                scope.async { client.sendAndAwaitOkOrError(message, eventId) }
            }.awaitAll()
            if (results.none { it is PublishResult.Success }) {
                return Result.Error(AppError.Network.PublishRejected(results.summarizeFailures()))
            }

            outboxManager.updateMyRelayList(pubKey, relays)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Failed to publish relay list", e))
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
        relays: List<String>,
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

    override fun getRelayListForPubkey(pubkey: String): List<Nip65Relay> = outboxManager.getCachedRelayList(pubkey)

    override fun selectOutboxRelays(
        authors: List<String>,
        taggedPubkeys: List<String>,
        explicitRelays: List<String>,
    ): List<String> = outboxManager.selectOutboxRelays(
        authors = authors,
        taggedPubkeys = taggedPubkeys,
        explicitRelays = explicitRelays,
        currentNip29Relay = connectionManager.currentRelayUrl.value,
    )

    private suspend fun publishJoinedGroupsList() {
        val pubKey = sessionManager.getPublicKey() ?: return
        publishJoinedGroupsListWith(pubKey)
    }

    /**
     * Publish kind:10009 with an optional custom relay list.
     * Returns the result so callers can check if the signer approved.
     */
    private suspend fun publishJoinedGroupsListWith(
        pubKey: String,
        nip29Relays: List<String> = outboxManager.kind10009Relays.value.toList(),
    ): Result<Unit> {
        // The in-memory map can be partial early in a session (the storage restore and
        // the kind:10009 fetch are async). An event published from a partial map drops
        // the missing groups FOR GOOD: on restart the persisted timestamp guard ignores
        // our own event, leaving only the (then clobbered) storage slots. Merge memory
        // with the persisted per-relay slots so the published list is always a
        // superset; removals stay correct because leave/removeRelay update storage
        // before publishing.
        val memory = groupManager.joinedGroupsByRelay.value
        val storedRelays = SecureStorage.loadRelayListFor(pubKey).map { it.normalizeRelayUrl() }
        val relays = (storedRelays + memory.keys.map { it.normalizeRelayUrl() }).distinct()
        val perRelay =
            relays
                .associateWith { relay ->
                    SecureStorage.getJoinedGroupsForRelay(pubKey, relay) + memory[relay].orEmpty()
                }.filterValues { it.isNotEmpty() }
        return outboxManager.publishJoinedGroupsList(
            pubKey = pubKey,
            joinedGroupsByRelay = perRelay,
            nip29Relays = nip29Relays,
            signEvent = { sessionManager.signEvent(it) },
            messageHandler = { msg, client -> handleRelayMessage(msg, client) },
        )
    }

    // Groups whose subscriptions the relay CLOSED (typically auth-required).
    // StateFlow + atomic `.update` is required: parallel AUTH handlers across
    // joined relays mutate this concurrently from Dispatchers.Default.
    private val _closedGroupSubscriptions = MutableStateFlow<Set<String>>(emptySet())

    // Message IDs collected per msg_ subscription, used to fetch reactions after EOSE.
    private val pendingReactionFetch = mutableMapOf<String, MutableList<String>>()

    // Pool relay URLs that have been actively connected during this session (were primary at
    // some point or were reconnected). Only these are eligible for reconnection on drop.
    private val connectedPoolRelays = mutableSetOf<String>()

    // Per-relay debug counters: relayUrl -> event count since last connect
    private val relayEventCounts = mutableMapOf<String, Int>()
    private val relayEoseReceived = mutableSetOf<String>()

    /**
     * Unified message handler for all relay pool connections.
     *
     * Problem this solves: getOrConnectRelay() always returns the *existing* pool client
     * when a relay is already connected, ignoring the new onMessage callback. This means
     * whichever handler was used on the first connection wins permanently.
     *
     * If initializeOutboxModel() connected relay A with handleRelayMessage first, then
     * connectToRelayBackground() got that same client. requestGroups() was sent, but
     * kind 39000 responses went to handleRelayMessage which drops them — the relay
     * appeared "connected but silent" from the group perspective.
     *
     * Fix: both initializeOutboxModel and connectToRelayBackground now use this unified
     * handler so the first-connection handler always handles both NIP-29 and outbox events.
     */
    /**
     * Fetch NIP-57 zap receipts (kind 9735) for [messageIds] from general-purpose relays.
     * NIP-29 group relays don't serve zap receipts (they carry no `h` tag), so we query
     * connected outbox/bootstrap relays — connecting a couple if none are open. Responses
     * route through the relay pipeline → kind 9735 → ZapManager aggregation.
     */
    private fun fetchZapReceiptsFromGeneralRelays(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        scope.launch {
            // Query the exact relays the zap request asked the receipt to be published to —
            // not the NIP-29 group relay, which rejects the (h-less) 9735.
            zapManager.receiptRelays().forEach { url ->
                try {
                    val client = connectionManager.getClientForRelay(url)
                        ?: connectionManager.getOrConnectRelay(url) { msg, c -> enqueueToRelayPipeline(msg, c) }
                    if (client != null && client.isConnected()) client.requestZapReceipts(messageIds)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    private fun handleUnifiedMessage(msg: String, client: NostrGroupClient) {
        // Parse once — every downstream handler reuses this JsonArray.
        val arr = try {
            json.parseToJsonElement(msg).jsonArray
        } catch (_: Exception) {
            return
        }

        val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

        // Outbox routing for EOSE
        if (msgType == "EOSE" && arr.size >= 2) {
            val subId = arr[1].jsonPrimitive.content
            val url = client.getRelayUrl()
            if (subId.startsWith("mux_chat_")) {
                relayEventCounts.remove(url)
            }
            outboxManager.handleEose(subId)
            // Fall through — handleMessage also needs EOSE for group pagination
        }

        // Outbox routing for EVENT kinds 10009/10002
        if (msgType == "EVENT" && arr.size >= 3) {
            val event = arr[2].jsonObject
            val kind = event["kind"]?.jsonPrimitive?.int
            val url = client.getRelayUrl()
            relayEventCounts[url] = (relayEventCounts[url] ?: 0) + 1
            connStats.onEventReceived(url)
            if (kind == 10009) {
                val pubKey = sessionManager.getPublicKey() ?: ""
                val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
                if (eventPubkey != null && eventPubkey != pubKey) {
                    // Another user's public group list (profile page fetch).
                    val createdAt = event["created_at"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (createdAt >= (userGroupListCreatedAt[eventPubkey] ?: 0L)) {
                        userGroupListCreatedAt[eventPubkey] = createdAt
                        val refs =
                            event["tags"]?.jsonArray.orEmpty().mapNotNull { tag ->
                                val t = tag.jsonArray
                                if (t.getOrNull(0)?.jsonPrimitive?.content != "group") return@mapNotNull null
                                val gid = t.getOrNull(1)?.jsonPrimitive?.content ?: return@mapNotNull null
                                val relay =
                                    (t.getOrNull(2)?.jsonPrimitive?.content ?: "")
                                        .normalizeRelayUrl()
                                        .ifBlank { connectionManager.currentRelayUrl.value }
                                UserGroupRef(relayUrl = relay, groupId = gid)
                            }.distinct()
                        _userGroupLists.value = _userGroupLists.value + (eventPubkey to refs)
                        scheduleUserGroupListsPersist()
                    }
                    return
                }
                scope.launch {
                    outboxManager.handleKind10009Event(
                        event = event,
                        currentRelayUrl = connectionManager.currentRelayUrl.value,
                        pubKey = pubKey,
                        onGroupsUpdated = { groups -> groupManager.setJoinedGroups(groups) },
                        onRelaysRestored = { newRelays ->
                            groupManager.prePopulateRelayList(newRelays)
                            _relayMetadataManager.fetchAll(newRelays)
                            autoConnectFirstRelay(newRelays)
                        },
                        onRelayGroupsUpdated = { relayGroups ->
                            groupManager.updateAllRelayJoinedGroups(relayGroups)
                            // Prune relays from the rail that are not in the authoritative
                            // kind:10009 event — prevents stale relays from a previous
                            // session's SecureStorage from staying in _groupsByRelay.
                            val authoritativeRelays = outboxManager.kind10009Relays.value +
                                relayGroups.keys
                            if (authoritativeRelays.isNotEmpty()) {
                                groupManager.pruneRelaysNotIn(authoritativeRelays)
                            }
                        },
                        messageHandler = { m, c -> enqueueToRelayPipeline(m, c) },
                        isGroupDropped = { groupManager.isLocallyDropped(it) },
                    )
                }
                return
            }
            if (kind == 10002) {
                outboxManager.handleKind10002Event(event, sessionManager.getPublicKey())
                return
            }
            if (kind == 3) {
                handleKind3Event(event)
                return
            }
            // NIP-17 DM gift wrap addressed to us: decrypt with the active signer.
            if (kind == Nip17.KIND_GIFT_WRAP) {
                val giftWrap = runCatching { parseSignedEventJson(event.toString()) }.getOrNull() ?: return
                val myPub = sessionManager.getPublicKey() ?: return
                val signer = ActiveAccountManager.session.value?.signer ?: return
                scope.launch {
                    // A bunker signer pays a remote round-trip per NIP-44 decrypt, and a gift
                    // wrap needs two (wrap then seal). A cold start with a large DM backlog
                    // would flood the single signer connection and starve the kind:22242 AUTH
                    // sign that private NIP-29 relays need, leaving private groups stuck on
                    // "auth-required". Hold the backlog until the active relay's AUTH is signed
                    // (awaitAuthOrTimeout returns the moment AUTH completes, or after the grace
                    // when the relay needs no AUTH). Local/NIP-07 decrypt in-process and skip it.
                    if (signer is NostrSigner.Bunker) {
                        connectionManager.getPrimaryClient()?.awaitAuthOrTimeout(DM_INGEST_AUTH_GRACE_MS)
                    }
                    dmDecryptSemaphore.withPermit { dmManager.ingestGiftWrap(giftWrap, myPub, signer) }
                }
                return
            }
            // A user's NIP-17 DM relay list (kind:10050).
            if (kind == Nip17.KIND_DM_RELAYS) {
                runCatching { parseSignedEventJson(event.toString()) }.getOrNull()?.let {
                    dmManager.ingestDmRelays(it)
                    if (it.pubkey == sessionManager.getPublicKey()) _myDmRelays.value = dmRelaysFor(it.pubkey)
                }
                return
            }
        }

        // Delegate all other events (39000, 39001, 39002, 9, 7, 5, 0, AUTH, OK, CLOSED…)
        handleMessage(msg, arr, msgType, client)
    }

    private fun handleMessage(msg: String, arr: JsonArray, msgType: String, client: NostrGroupClient) {
        when (msgType) {
            "AUTH" -> {
                val authChallenge = client.parseAuthChallenge(arr) ?: return
                // Remember this relay gates reads behind auth, so pagination can
                // await re-AUTH after a reconnect instead of racing ahead and
                // getting CLOSED "auth-required".
                client.markAuthChallengeSeen()
                scope.launch {
                    // Only run post-AUTH side effects when we actually signed and sent
                    // the response. handleAuthChallenge dedupes per relay; a chatty
                    // relay that resends AUTH frames in tight succession (observed on
                    // chat.wisp.talk) would otherwise re-fire notifyAuthCompleted and
                    // resubscribeAfterAuth for every duplicate frame, flooding the
                    // relay with redundant subscriptions.
                    if (sessionManager.handleAuthChallenge(client, authChallenge)) {
                        // Signal that AUTH is done so connect()/switchRelay() can
                        // proceed with requestGroups() after the relay accepted auth.
                        client.notifyAuthCompleted()
                        resubscribeAfterAuth(client)
                    }
                }
            }

            "OK" -> {
                val (eventId, success, message) = client.parseOkMessage(arr) ?: return
                scope.launch {
                    client.handleOkResponse(eventId, success, message)
                }
            }

            "EOSE" -> {
                if (arr.size < 2) return
                val subId = arr[1].jsonPrimitive.content
                // CRITICAL: EOSE handling must be async to allow pending message tracking to complete
                // yield() before EOSE so pending message-tracking coroutines (scope.launch
                // blocks queued by handleMessage) get scheduled first, reducing the race
                // window where messageCount is read before all tracking completes.
                val sourceRelayUrl = client.getRelayUrl()
                scope.launch {
                    yield()
                    // A msg_ initial load that EOSEs while the relay still needs AUTH (some
                    // relays empty-EOSE instead of CLOSED auth-required) is not a real empty
                    // result: hold it in InitialLoading so the UI keeps skeletons until
                    // resubscribeAfterAuth replays it post-AUTH, instead of flashing empty.
                    val authBlocked = client.requiresAuth() && !client.hasAuthSucceeded()
                    val held = authBlocked && subId.startsWith("msg_") &&
                        groupManager.holdInitialLoadForReauth(subId)
                    if (!held) {
                        groupManager.handleEoseSuspend(subId, sourceRelayUrl)
                    }
                    // Wake any pending batchFetch waiting on EOSE for this metadata sub.
                    metadataManager.notifyMetadataEose(subId, sourceRelayUrl)
                    // After the mux chat subscription delivers its backlog, detect any gaps
                    // (groups whose cursor expected events that never arrived from this relay).
                    if (subId.startsWith("mux_chat_")) {
                        detectAndFillGaps(client.getRelayUrl())
                    }
                    // Fetch reactions for message IDs received in this msg_ subscription
                    if (subId.startsWith("msg_")) {
                        val messageIds = pendingReactionFetch.remove(subId)
                        if (!messageIds.isNullOrEmpty()) {
                            try {
                                val reactSubId = client.requestReactionsForMessages(messageIds)
                                // Auto-close the reactions sub after EOSE
                                if (reactSubId != null) {
                                    // Will be closed when its own EOSE arrives via reactions_ prefix
                                }
                            } catch (_: Exception) {}
                            // Zap receipts (kind 9735) carry no `h` tag, so they live on
                            // general relays, not the NIP-29 group relay — fetch them there.
                            fetchZapReceiptsFromGeneralRelays(messageIds)
                        }
                    }
                    closeOneShotSubAfterEose(subId, client)
                }
            }

            "CLOSED" -> {
                if (arr.size < 2) return
                val subId = arr[1].jsonPrimitive.content
                val reason = if (arr.size >= 3) arr[2].jsonPrimitive.contentOrNull ?: "" else ""
                // communities.nos.social caps subscriptions at 50 and rejects the overflow
                // with "restricted: Subscription quota exceeded: 50/50" — a rate limit, NOT
                // NIP-29 access control. Treating it as a private-group restriction marked the
                // group private and PERSISTED it for 7 days, so the group rendered the "Private
                // group" placeholder for groups the user belongs to (the relay where this bites
                // is the one with the most joined groups, i.e. the user's busiest). Exclude
                // quota/rate-limit reasons; that sub just needs reopening when a slot frees.
                val isQuotaOrRateLimit = reason.contains("quota", ignoreCase = true) ||
                    reason.contains("rate-limit", ignoreCase = true) ||
                    reason.contains("rate limit", ignoreCase = true) ||
                    reason.contains("too many", ignoreCase = true)
                val isRestricted = reason.contains("restricted") && !isQuotaOrRateLimit
                val isAuthRequired = reason.contains("auth-required")

                // "restricted" on the group-list subscription: distinguish between
                // (a) unfiltered requestGroups() failing — relay genuinely denies access,
                // and (b) #d-filtered requestGroupsForIds() failing because one of the
                // joined groups in the batch is restricted. Only (a) marks the whole
                // relay. (b) is silent — OTHER GROUPS is collapsed by design, so no
                // fallback unfiltered fetch (that would leak OTHER groups into the
                // homescreen). Per-group meta_/msg_ CLOSEDs from requestPrivateGroupData
                // and resubscribeAfterAuth identify the specific offender.
                if (isRestricted && subId.startsWith("group-list")) {
                    val relayUrl = client.getRelayUrl()
                    val wasFullFetch = groupManager.hasPendingFullFetch(relayUrl)
                    if (wasFullFetch) {
                        _restrictedRelays.value = _restrictedRelays.value + (relayUrl to reason)
                        groupManager.cancelPendingFullFetch(relayUrl)
                        groupManager.markRelayLoaded(relayUrl)
                        connectionManager.setError(reason)
                    } else {
                        groupManager.markRelayLoaded(relayUrl)
                    }
                    return
                }

                if (isAuthRequired) {
                    // Only record groups that belong to THIS relay so resubscribeAfterAuth
                    // doesn't send cross-relay metadata requests to the authed client.
                    val relayUrl = client.getRelayUrl()
                    val activeGroupIds = groupManager.getGroupIdsForMux(relayUrl)
                    if (activeGroupIds.isNotEmpty()) {
                        _closedGroupSubscriptions.update { it + activeGroupIds }
                    }
                    // If the relay denied the unfiltered group-list REQ with auth-required,
                    // clear the pending-full-fetch marker. Otherwise the flag stays set
                    // forever (EOSE never arrives), and any later attempt (including the
                    // user expanding OTHER GROUPS) is silently dropped by the dedup guard
                    // in requestFullGroupListForRelay, leaving the panel empty until restart.
                    if (subId.startsWith("group-list")) {
                        groupManager.cancelPendingFullFetch(relayUrl)
                        groupManager.markRelayLoaded(relayUrl)
                    }
                }

                // Track per-group "restricted" status so the UI can show a private
                // group placeholder with invite code input. Any per-group sub whose
                // ID embeds an 8-char group prefix is a reliable signal when it
                // closes with "restricted" — msg_, meta_, members_, admins_ all
                // isolate exactly one group.
                if (isRestricted) {
                    val prefix = when {
                        subId.startsWith("msg_") -> subId.removePrefix("msg_").substringBefore("_")
                        subId.startsWith("meta_") -> subId.removePrefix("meta_")
                        subId.startsWith("members_") -> subId.removePrefix("members_")
                        subId.startsWith("admins_") -> subId.removePrefix("admins_")
                        else -> null
                    }
                    if (prefix != null) {
                        val groupId = groupManager.getGroupIdByPrefix(prefix)
                            ?: groupManager.activeGroupId?.takeIf { it.startsWith(prefix) }
                        if (groupId != null) {
                            groupManager.markGroupRestricted(groupId, reason)
                        }
                    }
                }

                // Unblock any pending state-machine load (transitions InitialLoading → Exhausted).
                // Exception: a pre-AUTH auth-required CLOSE on a msg_ initial load is not a real
                // "no results" — resubscribeAfterAuth replays the read once AUTH completes. Hold
                // the load in InitialLoading (skeletons) instead of settling it to a false empty
                // state, which is the "open a private group from the homepage" flicker.
                val sourceRelayUrl = client.getRelayUrl()
                scope.launch {
                    val held = isAuthRequired && subId.startsWith("msg_") &&
                        groupManager.holdInitialLoadForReauth(subId)
                    if (!held) {
                        groupManager.handleEoseSuspend(subId, sourceRelayUrl)
                    }
                }

                // Re-open the mux subscription when the relay closes it for non-auth reasons.
                // pyramid.fiatjaf.com and similar relays drop idle subs without closing the WS.
                if (!isAuthRequired &&
                    !isRestricted &&
                    (
                        subId.startsWith("mux_chat_") ||
                            subId.startsWith("mux_reactions_") ||
                            subId.startsWith("mux_meta_")
                        )
                ) {
                    val relayUrl = client.getRelayUrl()
                    scope.launch {
                        delay(2_000) // brief back-off before re-opening
                        groupManager.refreshMuxDebounced(relayUrl)
                    }
                    metadataRefreshJob?.cancel()
                    metadataRefreshJob = scope.launch {
                        delay(3_000)
                        refreshVisibleUserMetadata()
                    }
                }
            }

            "EVENT" -> {
                if (arr.size < 3) return
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int

                // Handle event subscriptions (event_* or e_*) for quotes
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

                // Dispatch by kind — each parser works on the pre-extracted JsonObject
                when (kind) {
                    39000 -> {
                        val groupMetadata = client.parseGroupMetadata(event) ?: return
                        groupManager.handleGroupMetadata(groupMetadata, client.getRelayUrl())
                        scope.launch { client.handleGroupCreationEvent(subId, groupMetadata) }
                    }

                    39002 -> {
                        val groupMembers = client.parseGroupMembers(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        val memberPubkeys = groupManager.handleGroupMembers(groupMembers, createdAt)
                        val pubkeysNeedingMetadata = memberPubkeys.filter { !metadataManager.hasMetadata(it) }
                        if (pubkeysNeedingMetadata.isNotEmpty()) {
                            scope.launch {
                                // forceStale bypasses the 5-minute negative cache: a member
                                // whose kind:0 we failed to fetch on first open (slow relay,
                                // EOSE timeout) would otherwise stay blank until the cache
                                // expires, even when the user switches back to the group. A
                                // fresh 39002 is the signal to retry those profiles; already
                                // cached ones stay filtered, so this does not refetch them.
                                requestUserMetadata(pubkeysNeedingMetadata.toSet(), forceStale = true)
                            }
                        }
                    }

                    39001 -> {
                        val groupAdmins = client.parseGroupAdmins(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        groupManager.handleGroupAdmins(groupAdmins, createdAt)
                    }

                    39003 -> {
                        val groupRoles = client.parseGroupRoles(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        groupManager.handleGroupRoles(groupRoles, createdAt)
                    }

                    9008 -> {
                        val tags = event["tags"]?.jsonArray ?: return
                        val groupId = tags.firstOrNull {
                            it.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull == "h"
                        }?.jsonArray?.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return
                        val relayUrl = client.getRelayUrl()
                        val pubKey = sessionManager.getPublicKey()
                        scope.launch {
                            val changed = groupManager.handleRemoteDeleteGroup(groupId, relayUrl, pubKey)
                            if (changed) publishJoinedGroupsList()
                        }
                    }

                    0 -> {
                        val parsed = client.parseUserMetadata(event) ?: return
                        metadataManager.handleMetadataEvent(parsed.pubkey, parsed.metadata, parsed.createdAt)
                    }

                    9735 -> {
                        // NIP-57 zap receipt — aggregate per zapped event id for UI totals.
                        zapManager.handleZapReceipt(event)
                    }

                    7 -> {
                        val reaction = client.parseReaction(event) ?: return
                        val reactorPubkey = groupManager.handleReaction(reaction)
                        if (reactorPubkey != null && !metadataManager.hasMetadata(reactorPubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(reactorPubkey))
                            }
                        }
                        val currentPubkey = sessionManager.getPublicKey()
                        if (currentPubkey != null && reaction.pubkey != currentPubkey) {
                            // Prefer the NIP-25 `p` tag — it tells us the target author
                            // directly, so we don't have to wait for the target message
                            // to clear the EventOrderingBuffer. Fall back to the cross-
                            // group cache lookup when the reactor omitted the `p` tag.
                            val groupId = reaction.groupId
                                ?: groupManager.findMessageByIdAcrossGroups(reaction.targetEventId)?.first
                            val isForSelf = when {
                                reaction.targetAuthorPubkey != null ->
                                    reaction.targetAuthorPubkey == currentPubkey
                                else ->
                                    groupManager
                                        .findMessageByIdAcrossGroups(reaction.targetEventId)
                                        ?.second?.pubkey == currentPubkey
                            }
                            if (isForSelf && groupId != null) {
                                unreadManager.onReactionReceived(groupId, reaction)
                            }
                        }
                    }

                    else -> {
                        // Chat messages (kind 9, 5, 9000-9003, 9021-9022, etc.)
                        val message = client.parseMessage(event) ?: return
                        val senderPubkey = groupManager.handleMessage(message, msg, subId, client.getRelayUrl())
                        if (senderPubkey != null && (!metadataManager.hasMetadata(senderPubkey) || metadataManager.isStale(senderPubkey))) {
                            scope.launch {
                                requestUserMetadata(setOf(senderPubkey))
                            }
                        }
                        // Track message ID for reaction fetch after EOSE
                        if (subId.startsWith("msg_") && message.id.isNotBlank()) {
                            pendingReactionFetch.getOrPut(subId) { mutableListOf() }.add(message.id)
                        }
                    }
                }
            }
        }
    }

    /**
     * CLOSE one-shot subscriptions once their EOSE arrives so the relay frees the
     * slot. Live subscriptions (mux_*, group-list) are intentionally absent and
     * stay open. Shared by both message handlers so the light metadata/outbox
     * handler ([handleRelayMessage]) cleans up the same way the full one does.
     */
    private fun closeOneShotSubAfterEose(subId: String, client: NostrGroupClient) {
        if (subId.startsWith("meta_") ||
            subId.startsWith("admins_") ||
            subId.startsWith("members_") ||
            subId.startsWith("metadata_") ||
            subId.startsWith("msg_") ||
            subId.startsWith("e_") ||
            subId.startsWith("a_") ||
            subId.startsWith("reactions_") ||
            subId.startsWith("zaps_") ||
            subId.startsWith("event_")
        ) {
            scope.launch {
                try {
                    client.send("""["CLOSE","$subId"]""")
                } catch (_: Exception) {}
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
                // Relays connected via the metadata/outbox path route here and
                // would otherwise never close their one-shot fetches (observed:
                // zap-receipt subs leaking on nos.lol/damus).
                closeOneShotSubAfterEose(subId, client)
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
                            onGroupsUpdated = { groups -> groupManager.setJoinedGroups(groups) },
                            onRelaysRestored = { newRelays ->
                                groupManager.prePopulateRelayList(newRelays)
                                _relayMetadataManager.fetchAll(newRelays)
                                // Auto-connect to the first relay if no primary is connected yet
                                autoConnectFirstRelay(newRelays)
                            },
                            onRelayGroupsUpdated = { relayGroups ->
                                groupManager.updateAllRelayJoinedGroups(relayGroups)
                                val authoritativeRelays = outboxManager.kind10009Relays.value +
                                    relayGroups.keys
                                if (authoritativeRelays.isNotEmpty()) {
                                    groupManager.pruneRelaysNotIn(authoritativeRelays)
                                }
                            },
                            messageHandler = { m, c -> enqueueToRelayPipeline(m, c) },
                            isGroupDropped = { groupManager.isLocallyDropped(it) },
                        )
                    }
                    return
                }

                // Handle kind:10002 (NIP-65 relay list)
                if (kind == 10002) {
                    outboxManager.handleKind10002Event(event, sessionManager.getPublicKey())
                    return
                }

                // Handle kind:3 (NIP-02 contact list) for the active account
                if (kind == 3) {
                    handleKind3Event(event)
                    return
                }
            }
        } catch (_: Exception) {}

        // Handle user metadata
        val parsed = client.parseUserMetadata(msg)
        if (parsed != null) {
            metadataManager.handleMetadataEvent(parsed.pubkey, parsed.metadata, parsed.createdAt)
        }
    }

    /**
     * Re-subscribe to all groups on a pool relay after it reconnects.
     * Called by [relayReconnectScheduler]'s doReconnect lambda and [resubscribeAllGroups].
     */
    private suspend fun resubscribePoolRelay(relayUrl: String, client: NostrGroupClient) {
        val groupsOnRelay = groupManager.getGroupsForRelay(relayUrl)
        if (groupsOnRelay.isNotEmpty()) {
            groupManager.handleConnectionLostForGroups(groupsOnRelay.map { it.id })
        }
        // Fast-lane: prioritize the active group on pool relay reconnect.
        val activeGroupId = groupManager.activeGroupId
        if (activeGroupId != null && groupManager.getRelayForGroup(activeGroupId) == relayUrl) {
            scope.launch {
                groupManager.requestGroupMessages(activeGroupId)
                groupManager.requestGroupMembers(activeGroupId)
                groupManager.requestGroupAdmins(activeGroupId)
                groupManager.requestGroupRoles(activeGroupId)
            }
        }

        // Mux subs cover all kinds: 39000/39001/39002 (metadata/members/admins) +
        // chat/reactions for opened groups.
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)

        // If this revived relay is one of our DM relays, re-arm the gift-wrap inbox on it.
        if (relayUrl.normalizeRelayUrl() in _myDmRelays.value) resubscribeDmInbox()
    }

    /**
     * Revive NIP-29 pool relays that were previously connected (had been primary at some point)
     * and dropped during an internet outage.
     *
     * Only reconnects relays in [connectedPoolRelays] — relays that were never selected by
     * the user remain dormant (lazy connection model).
     */
    private fun reconnectDroppedNip29PoolRelays() {
        val primaryUrl = connectionManager.currentRelayUrl.value
        for (relayUrl in connectedPoolRelays.toList()) {
            if (relayUrl == primaryUrl) continue
            scope.launch {
                val existing = connectionManager.getClientForRelay(relayUrl)
                if (existing == null) {
                    val priority = if (relayUrl == activeRelayUrl) {
                        RelayReconnectScheduler.Priority.ACTIVE
                    } else {
                        RelayReconnectScheduler.Priority.BACKGROUND
                    }
                    relayReconnectScheduler.schedule(relayUrl, priority = priority)
                }
            }
        }
    }

    /**
     * Re-establish all group subscriptions after a connection (re-)connect.
     *
     * Called from both auto-reconnect (onReconnected) and manual reconnect (reconnect()).
     * Explicitly resets group loading states to Idle before re-subscribing so
     * startInitialLoad() always succeeds regardless of timing — groups might still be
     * in InitialLoading/Exhausted/HasMore if the previous disconnect was very fast.
     *
     * Relay separation: only touches NIP-29 groups stored in groupManager; bootstrap
     * and fallback relays connect on-demand and are NOT re-subscribed here.
     */
    private suspend fun resubscribeAllGroups(client: NostrGroupClient) {
        val relayUrl = connectionManager.currentRelayUrl.value
        // Restore cache so the UI shows groups immediately while the re-fetch is in flight.
        groupManager.restoreGroupsForRelay(relayUrl)
        // Wait for the relay's NIP-42 AUTH challenge to complete before sending REQs;
        // otherwise the group-list races ahead and is rejected with auth-required
        // CLOSED. Then always re-fetch (mirroring switchRelay) — resubscribeAfterAuth's
        // 10s guard would otherwise suppress this call when the previous identity's
        // timestamp on this relay is still fresh (warm-swap between accounts).
        client.awaitAuthOrTimeout()
        // Always re-fetch on reconnect. restoreGroupsForRelay already populated the UI;
        // the fresh EOSE will prune any stale groups the relay no longer serves
        // (e.g. an ephemeral relay that was restarted and lost its group list).
        lastRequestGroupsAt[relayUrl] = epochSeconds()
        groupManager.markRelayLoading(relayUrl)
        requestGroupsForRelay(client, relayUrl)

        // Re-subscribe opened groups, PRESERVING pagination cursors. A reconnect must
        // not reset a mid-pagination group to Idle: the fast-lane initial load below
        // would then re-fire with `until = oldest message - 1`, and when the oldest is a
        // bulk-delivered moderation event (an old join far older than the chat frontier)
        // it jumps to the floor and marks the group Exhausted, skipping all un-paginated
        // middle history. The cursor is a timestamp bookmark; the mux refresh re-sends
        // the live feed, so keeping it loses nothing.
        val openedGroupIds = groupManager.getOpenedGroupIds()
            .filter { groupManager.getRelayForGroup(it) == relayUrl }
        if (openedGroupIds.isNotEmpty()) {
            groupManager.handleReconnectForGroups(openedGroupIds.toList())
        }

        // Fast-lane: direct requests for the ACTIVE group so it renders first.
        // Mux provides breadth for all groups; direct requests provide speed for the
        // group the user is currently looking at. Deduplicator handles overlap.
        val activeGroupId = groupManager.activeGroupId
        if (activeGroupId != null && groupManager.getRelayForGroup(activeGroupId) == relayUrl) {
            scope.launch {
                groupManager.requestGroupMessages(activeGroupId)
                groupManager.requestGroupMembers(activeGroupId)
                groupManager.requestGroupAdmins(activeGroupId)
                groupManager.requestGroupRoles(activeGroupId)
            }
        }

        // Mux refresh covers all live subscriptions:
        // mux_meta (kinds 39000/39001/39002) for all joined groups,
        // mux_chat + mux_reactions for opened groups with cursor-based since.
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
    }

    /**
     * Re-subscribe for group messages after a successful NIP-42 AUTH.
     * Covers groups whose subscriptions were closed with auth-required,
     * as well as all currently loaded groups on THIS relay.
     *
     * Only groups that belong to [client]'s relay are handled here.
     * Sending requestGroupMetadata for groups on a different relay causes
     * "Not connected" crashes and wastes relay bandwidth.
     */
    private suspend fun resubscribeAfterAuth(client: NostrGroupClient) {
        val relayUrl = client.getRelayUrl()
        if (!client.isConnected()) return

        if (connectionManager.getPrimaryClient() === client) {
            val now = epochSeconds()
            val lastAt = lastRequestGroupsAt[relayUrl] ?: 0L
            val normalized = relayUrl.normalizeRelayUrl()
            // On the FIRST auth completion for this relay this session, any full-list
            // result captured so far is untrustworthy: an auth-required relay may
            // answer the unauthenticated pre-AUTH group-list REQ with an empty EOSE
            // (rather than CLOSED auth-required), which marks the relay "fully
            // fetched" and would make sessionFetched below skip this authed fetch —
            // leaving OTHER GROUPS empty even when expanded (observed on hzrd149's
            // relay). Invalidate only once: after the first authed fetch the marker
            // is trustworthy, so relays that re-issue AUTH periodically fall back to
            // the 10s dedup instead of re-fetching the full list on every challenge.
            if (normalized !in authedGroupListFetchedRelays) {
                groupManager.invalidateFullGroupListFetch(relayUrl)
            }
            // Bypass the 10s dedup when THIS SESSION hasn't received an EOSE for
            // the full group list yet. The previous REQ likely raced AUTH and was
            // CLOSED auth-required, so skipping the retry leaves OTHER GROUPS
            // permanently empty until the user switches relays or restarts. We
            // deliberately read the in-memory set (not hasFullGroupListBeenFetched)
            // so a fresh re-login doesn't get fooled by the still-fresh persisted
            // cache from a previous session; that cache predates the auth-required
            // CLOSED on this socket.
            val sessionFetched = normalized in groupManager.fullGroupListFetchedRelays.value
            if (!sessionFetched || now - lastAt > 10L) {
                lastRequestGroupsAt[relayUrl] = now
                authedGroupListFetchedRelays.add(normalized)
                groupManager.markRelayLoading(relayUrl)
                requestGroupsForRelay(client, relayUrl)
            }
            drainFullFetchRequest(client, relayUrl)
        }

        // Reset loading states for opened + auth-closed groups.
        // Uses resetLoadingForGroups (NOT handleConnectionLostForGroups) to avoid
        // clearing the mux tracker — resubscribeAllGroups already sent the mux refresh,
        // so the tracker correctly reflects the current state.
        val openedOnRelay = groupManager.getOpenedGroupIds()
            .filter { groupManager.getRelayForGroup(it) == relayUrl }
        val closedOnRelay = _closedGroupSubscriptions.value.filter {
            groupManager.getRelayForGroup(it) == relayUrl
        }
        if (closedOnRelay.isNotEmpty()) {
            val drop = closedOnRelay.toSet()
            _closedGroupSubscriptions.update { it - drop }
        }

        // Include joined groups from kind 10009 — essential for private groups
        // that don't appear in the general kind 39000 listing.
        val joinedOnRelay = groupManager.getGroupIdsForMux(relayUrl)

        val groupIds = (openedOnRelay + closedOnRelay + joinedOnRelay).distinct()
        if (groupIds.isNotEmpty()) {
            // PRESERVE pagination cursors: a periodic AUTH re-challenge (0xchat does this)
            // must not reset an actively-scrolled group to Idle. Doing so re-fired the
            // initial load below with `until = oldest - 1`, which jumps to the floor,
            // injects ancient events at the top (visible scroll shift) and marks the group
            // Exhausted, dropping all un-paginated middle history. Groups that never
            // paginated (page 0) still fall back to Idle and re-init via the loop below.
            groupManager.resetLoadingForGroupsPreservingCursor(groupIds)
        }

        // Force-clear the mux tracker so the refresh always re-sends subscriptions.
        // The relay may have dropped active subs when it sent the AUTH challenge
        // (e.g. communities.nos.social re-challenges AUTH periodically).
        groupManager.clearMuxTrackerForRelay(relayUrl)
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)

        // Request metadata/members/admins for private groups that are not in the
        // group cache. The relay hides these from the general listing but returns
        // them on targeted #d requests after AUTH.
        groupManager.requestPrivateGroupData(relayUrl)
        // Also fetch metadata for the active group if the user navigated via URL
        // and the group isn't in the cache yet (e.g. invite link to a private group).
        groupManager.requestActiveGroupMetadataIfMissing(relayUrl)

        // Re-request historical messages for groups that were CLOSED with
        // auth-required. The mux only delivers live messages (since cursor),
        // so without this the chat stays empty after AUTH completes.
        for (groupId in groupIds) {
            // AUTH just succeeded: clear any "restricted" set by a pre-AUTH CLOSED (or a
            // persisted one from a prior session) so the chat unblocks instead of staying
            // stuck on the "Private group" placeholder. If the relay still denies this group
            // post-AUTH, the fresh CLOSED re-marks it.
            groupManager.clearGroupRestricted(groupId)
            groupManager.requestGroupMessages(groupId)
        }
    }
}

// Helper function for parsing bunker URLs
data class BunkerInfo(
    val pubkey: String,
    val relays: List<String>,
    val secret: String?,
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
