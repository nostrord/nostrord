package org.nostr.nostrord.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.managers.LiveCursorStore
import org.nostr.nostrord.network.managers.MetadataManager
import org.nostr.nostrord.network.managers.ConnectionStats
import org.nostr.nostrord.network.managers.OutboxManager
import org.nostr.nostrord.network.managers.RelayMetadataManager
import org.nostr.nostrord.network.managers.RelayReconnectScheduler
import org.nostr.nostrord.network.managers.SessionManager
import org.nostr.nostrord.network.managers.UnreadManager
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.AppError
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.normalizeRelayUrl
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
    private val relayMetadataManager: RelayMetadataManager? = null,
    private val liveCursorStore: LiveCursorStore? = null,
    private val connStats: ConnectionStats = ConnectionStats(),
    private val scope: CoroutineScope
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

    /**
     * Epoch-seconds of the last requestGroups() call per relay.
     * Prevents resubscribeAfterAuth from sending a duplicate group-list REQ when
     * connect() already sent one within the last 10 seconds.
     */
    private val lastRequestGroupsAt = mutableMapOf<String, Long>()

    /**
     * Relay URL of the group currently open on screen — used to promote reconnect priority.
     * Null when no group is focused (Home screen, settings, etc.).
     */
    private var activeRelayUrl: String? = null

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
        }
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
    override val joinedGroups: StateFlow<Set<String>> = groupManager.joinedGroups
    override val joinedGroupsByRelay: StateFlow<Map<String, Set<String>>> = groupManager.joinedGroupsByRelay
    override val loadingRelays: StateFlow<Set<String>> = groupManager.loadingRelays
    private val _restrictedRelays = MutableStateFlow<Map<String, String>>(emptyMap())
    override val restrictedRelays: StateFlow<Map<String, String>> = _restrictedRelays.asStateFlow()
    override val isLoadingMore: StateFlow<Map<String, Boolean>> = groupManager.isLoadingMore
    override val hasMoreMessages: StateFlow<Map<String, Boolean>> = groupManager.hasMoreMessages
    override val reactions: StateFlow<Map<String, Map<String, GroupManager.ReactionInfo>>> = groupManager.reactions
    override val groupMembers: StateFlow<Map<String, List<String>>> = groupManager.groupMembers
    override val groupAdmins: StateFlow<Map<String, List<String>>> = groupManager.groupAdmins
    override val loadingMembers: StateFlow<Set<String>> = groupManager.loadingMembers

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

    // Expose NIP-11 relay metadata
    private val _relayMetadataManager = relayMetadataManager ?: RelayMetadataManager(scope)
    override val relayMetadata: StateFlow<Map<String, Nip11RelayInfo>> = _relayMetadataManager.relayMetadata
    override val kind10009Relays: StateFlow<Set<String>> = outboxManager.kind10009Relays
    override val groupTagRelays: StateFlow<Set<String>> = outboxManager.groupTagRelays

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
            sessionManager.sendAuthIfNeeded(client)
            resubscribeAllGroups(client)
            pendingEventManager?.onConnectionRestored()
            reconnectDroppedNip29PoolRelays()
            scope.launch { refreshVisibleUserMetadata() }
        }

        metadataManager.messageHandler = { msg, client -> enqueueToRelayPipeline(msg, client) }

        connectionManager.startNetworkMonitor()
        connectionManager.loadSavedRelay()

        // Deep link relay from URL query params (web) — merge into relay list
        val deepLinkRelay = StartupResolver.deepLinkRelayUrl

        val restored = sessionManager.restoreSession()
        if (restored) {
            val pubkey = sessionManager.getPublicKey()
            val activeRelay = connectionManager.currentRelayUrl.value

            // Load saved relay list and pre-populate the rail before connecting
            val savedRelays = SecureStorage.loadRelayList()

            // No NIP-29 relays saved locally — fetch kind:10009 from bootstrap
            // relays so "r" tags can restore the user's relay list automatically.
            // Once relays are discovered, connect to the first one as primary.
            if (activeRelay.isBlank() && savedRelays.isEmpty() && deepLinkRelay == null) {
                if (pubkey != null) {
                    unreadManager.initialize(pubkey)
                    initializeOutboxModel()
                    scope.launch {
                        outboxManager.loadJoinedGroupsFromNostr(pubkey) { msg, c ->
                            enqueueToRelayPipeline(msg, c)
                        }
                        // After kind:10009 is fetched, check if relays were restored.
                        // If so, connect to the first one so the app doesn't stay in empty state.
                        val restoredRelays = SecureStorage.loadRelayList()
                        if (restoredRelays.isNotEmpty()) {
                            val primaryRelay = restoredRelays.first()
                            // Persist and set as active so the UI picks it up immediately.
                            SecureStorage.saveCurrentRelayUrl(primaryRelay)
                            connectionManager.loadSavedRelay()

                            groupManager.prePopulateRelayList(restoredRelays)
                            groupManager.restoreAllGroupsFromStorage(restoredRelays)
                            _relayMetadataManager.fetchAll(restoredRelays)
                            liveCursorStore?.loadAll(restoredRelays)
                            groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
                            groupManager.loadAllJoinedGroupsFromStorage(pubkey, restoredRelays)
                            connect(primaryRelay)
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
                SecureStorage.saveCurrentRelayUrl(primaryRelay)
                connectionManager.loadSavedRelay()
                // Signal UI to offer adding this relay if it's not already saved
                if (deepLinkRelay !in baseRelays) {
                    _pendingDeepLinkRelay.value = deepLinkRelay
                }
            }
            liveCursorStore?.loadAll(allRelays)
            groupManager.prePopulateRelayList(allRelays)
            groupManager.restoreAllGroupsFromStorage(allRelays)
            _relayMetadataManager.fetchAll(allRelays)

            if (pubkey != null) {
                groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
                groupManager.loadAllJoinedGroupsFromStorage(pubkey, allRelays)
                unreadManager.initialize(pubkey)
            }
            initializeOutboxModel()

            // Local data loaded — show UI while connect() runs in the background
            _isInitialized.value = true

            connect(primaryRelay)
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

    override fun clearAuthUrl() {
        sessionManager.clearAuthUrl()
    }

    override suspend fun loginWithBunker(bunkerUrl: String): Result<String> {
        return try {
            if (connectionManager.currentRelayUrl.value.isBlank()) {
                _isDiscoveringRelays.value = true
            }
            val userPubkey = sessionManager.loginWithBunker(bunkerUrl)
            unreadManager.initialize(userPubkey)
            initializeOutboxModel()
            connect()
            sessionManager.setLoggedIn(true)
            scope.launch { requestUserMetadata(setOf(userPubkey)) }
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
        if (connectionManager.currentRelayUrl.value.isBlank()) {
            _isDiscoveringRelays.value = true
        }
        val userPubkey = sessionManager.completeNostrConnectLogin(client, relays)
        unreadManager.initialize(userPubkey)
        initializeOutboxModel()
        connect()
        sessionManager.setLoggedIn(true)
        scope.launch { requestUserMetadata(setOf(userPubkey)) }
        return userPubkey
    }

    override suspend fun loginSuspend(privKey: String, pubKey: String): Result<Unit> {
        return try {
            if (connectionManager.currentRelayUrl.value.isBlank()) {
                _isDiscoveringRelays.value = true
            }
            sessionManager.loginWithPrivateKey(privKey, pubKey)
            unreadManager.initialize(pubKey)
            initializeOutboxModel()
            sessionManager.setLoggedIn(true)
            scope.launch { connect() }
            scope.launch { requestUserMetadata(setOf(pubKey)) }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(AppError.Unknown(e.message ?: "Login failed", e))
        }
    }

    override suspend fun loginWithNip07(pubkey: String): Result<Unit> {
        return try {
            if (connectionManager.currentRelayUrl.value.isBlank()) {
                _isDiscoveringRelays.value = true
            }
            sessionManager.loginWithNip07(pubkey)
            unreadManager.initialize(pubkey)
            initializeOutboxModel()
            sessionManager.setLoggedIn(true)
            scope.launch { connect() }
            scope.launch { requestUserMetadata(setOf(pubkey)) }
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
        _isDiscoveringRelays.value = false
        outboxManager.clear()
        groupManager.clear()
        unreadManager.clear()
        liveCursorStore?.clear()
        relayPipelines.values.forEach { (_, pipeline) -> pipeline.close() }
        relayPipelines.clear()
        SecureStorage.saveRelayList(emptyList())
        SecureStorage.clearCurrentRelayUrl()
        connectionManager.clearCurrentRelay()

        try { connectionManager.clearAll() } catch (_: Exception) {}
        sessionManager.logout()
    }

    override fun forgetBunkerConnection() {
        sessionManager.forgetBunkerConnection()
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
            }
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
        val connected = connectionManager.reconnect()
        if (connected) {
            val client = connectionManager.getPrimaryClient()
            if (client != null) {
                sessionManager.sendAuthIfNeeded(client)
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
                is ConnectionManager.ConnectionState.Error -> reconnect()

                // Auto-reconnect or initial connect in progress — don't interrupt.
                is ConnectionManager.ConnectionState.Reconnecting,
                is ConnectionManager.ConnectionState.Connecting -> {
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
                latestInMemory == null -> false  // no messages loaded yet — normal cold start
                latestInMemory < lastKnown - GAP_THRESHOLD_S -> true  // memory is stale
                else -> false
            }

            if (gapDetected) {
                val gapSec = lastKnown - (latestInMemory ?: 0)
                scope.launch {
                    try { groupManager.requestGroupMessages(groupId) } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun connect(relayUrl: String) {
        if (relayUrl.isBlank()) return
        _relayMetadataManager.fetch(relayUrl)

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
                        client.requestGroups()
                    }
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
        SecureStorage.saveCurrentRelayUrl(primaryRelay)
        connectionManager.loadSavedRelay()

        val pubkey = sessionManager.getPublicKey()
        groupManager.restoreAllGroupsFromStorage(relays)
        liveCursorStore?.loadAll(relays)
        if (pubkey != null) {
            groupManager.loadJoinedGroupsFromStorage(pubkey, primaryRelay)
            groupManager.loadAllJoinedGroupsFromStorage(pubkey, relays)
        }
        connect(primaryRelay)
    }

    override suspend fun switchRelay(newRelayUrl: String) {
        // Skip if already on this relay — avoids unnecessary disconnect/reconnect/AUTH cycle.
        if (newRelayUrl == connectionManager.currentRelayUrl.value &&
            connectionManager.getPrimaryClient()?.isConnected() == true) {
            return
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

        connectionManager.switchRelay(newRelayUrl) { msg, client ->
            enqueueToRelayPipeline(msg, client)
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
            // Wait for a potential NIP-42 AUTH challenge before sending any REQ.
            val authHandled = client.awaitAuthOrTimeout()
            // Skip re-fetch if cached; re-fetching races against restored state.
            if (!groupManager.hasCachedGroupsForRelay(newRelayUrl)) {
                // Always request groups here. Even if AUTH was already handled
                // (authHandled == true), resubscribeAfterAuth only calls
                // requestGroups() for the primary client — if this client was
                // promoted from the pool (e.g. connected by a link preview),
                // AUTH happened while it was a pool client and requestGroups()
                // was never sent.
                client.requestGroups()
            } else {
                // Cache was restored — no EOSE will arrive, so unmark loading now.
                groupManager.markRelayLoaded(newRelayUrl)
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

    }

    override suspend fun removeRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        val existing = outboxManager.kind10009Relays.value.toList()
        val remaining = existing.filter { it != normalized }
        val pubKey = sessionManager.getPublicKey()

        // Publish kind:10009 first — only persist removal on success
        if (pubKey != null) {
            val result = publishJoinedGroupsListWith(pubKey, nip29Relays = remaining)
            if (result !is Result.Success) {
                return // signer denied or publish failed — don't remove
            }
        }

        SecureStorage.saveRelayList(remaining)

        // Clean up persisted joined groups for this relay
        if (pubKey != null) {
            SecureStorage.clearJoinedGroupsForRelay(pubKey, normalized)
        }

        // Remove from in-memory maps so the rail and kind:10009 cache update immediately
        groupManager.removeRelayEntry(normalized)
        outboxManager.removeRelayFromCache(normalized)
        // Switch to first remaining relay, or clear persisted relay if none left
        val fallback = remaining.firstOrNull()
        if (fallback != null && fallback != connectionManager.currentRelayUrl.value.normalizeRelayUrl()) {
            switchRelay(fallback)
        } else if (fallback == null) {
            SecureStorage.clearCurrentRelayUrl()
            connectionManager.clearCurrentRelay()
        }
        // Disconnect the removed relay (pool or primary)
        connectionManager.disconnectRelay(normalized)
        connectedPoolRelays.remove(normalized)
    }

    override suspend fun disconnect() {
        connectionManager.disconnectPrimary()
        groupManager.clear()
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
                    SecureStorage.saveRelayList(newList)
                } else {
                    return // signer denied or publish failed — don't save
                }
            } else {
                SecureStorage.saveRelayList(newList)
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
        return groupManager.joinGroup(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) },
            publishJoinedGroups = { publishJoinedGroupsList() },
            inviteCode = inviteCode
        )
    }

    override suspend fun createGroup(
        name: String,
        about: String?,
        relayUrl: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        picture: String?,
        customGroupId: String?
    ): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        if (relayUrl != connectionManager.currentRelayUrl.value) {
            switchRelay(relayUrl)
        }
        return groupManager.createGroup(
            name = name,
            about = about,
            picture = picture,
            isPrivate = isPrivate,
            isClosed = isClosed,
            customGroupId = customGroupId,
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
        val relayUrl = groupManager.getRelayForGroup(groupId)
        val metaClient = if (relayUrl != null) connectionManager.getClientForRelay(relayUrl) else null
        (metaClient ?: connectionManager.getPrimaryClient())?.requestGroupMetadata(groupId)
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

    override suspend fun editGroup(
        groupId: String,
        name: String,
        about: String?,
        isPrivate: Boolean,
        isClosed: Boolean,
        picture: String?
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
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) }
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
            publishJoinedGroups = { publishJoinedGroupsList() }
        )
    }

    override suspend fun loadMoreMessages(groupId: String, channel: String?): Boolean {
        return groupManager.loadMoreMessages(groupId, channel)
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
            signEvent = { sessionManager.signEvent(it) }
        )
    }

    override suspend fun addUser(groupId: String, targetPubkey: String, roles: List<String>): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.addUser(
            groupId = groupId,
            targetPubkey = targetPubkey,
            roles = roles,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) }
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
            signEvent = { sessionManager.signEvent(it) }
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
            signEvent = { sessionManager.signEvent(it) }
        )
    }

    override suspend fun createInviteCode(groupId: String): Result<String> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.createInviteCode(
            groupId = groupId,
            pubKey = pubKey,
            currentRelayUrl = connectionManager.currentRelayUrl.value,
            signEvent = { sessionManager.signEvent(it) }
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

    override suspend fun sendReaction(groupId: String, targetEventId: String, targetPubkey: String, emoji: String): Result<Unit> {
        val pubKey = sessionManager.getPublicKey()
            ?: return Result.Error(AppError.Auth.NotAuthenticated)
        return groupManager.sendReaction(
            groupId = groupId,
            targetEventId = targetEventId,
            targetPubkey = targetPubkey,
            emoji = emoji,
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
    private val metadataMessageHandler: (String, NostrGroupClient) -> Unit = { msg, client ->
        handleRelayMessage(msg, client)
    }

    override suspend fun requestUserMetadata(pubkeys: Set<String>) {
        metadataManager.requestUserMetadata(pubkeys, metadataMessageHandler)
    }

    private suspend fun refreshVisibleUserMetadata() {
        // Wait for resubscribeAllGroups REQs to deliver events before collecting pubkeys.
        // Without this delay, messages/members may still be empty from the previous session.
        delay(3_000)

        val openedGroups = groupManager.getOpenedGroupIds()
        if (openedGroups.isEmpty()) return
        val pubkeys = openedGroups.flatMap { groupId ->
            val messagePubkeys = groupManager.messages.value[groupId]
                ?.takeLast(50)?.map { it.pubkey } ?: emptyList()
            val memberPubkeys = groupManager.getMembersForGroup(groupId)
            messagePubkeys + memberPubkeys
        }.toSet().filter { metadataManager.isStale(it) }.toSet()
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
            content = ""
        )
        val signed = try { sessionManager.signEvent(event) } catch (_: Throwable) { return null }
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
        website: String?
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
                try { Json.parseToJsonElement(raw).jsonObject.toMap() } catch (_: Exception) { null }
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
                content = content
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
            clients.forEach { client ->
                scope.launch {
                    try { client.sendAndAwaitOk(message, eventId) } catch (_: Exception) {}
                }
            }

            val updatedMetadata = UserMetadata(
                pubkey = pubKey,
                name = name ?: existing?.name,
                displayName = displayName ?: existing?.displayName,
                picture = picture ?: existing?.picture,
                about = about ?: existing?.about,
                nip05 = nip05 ?: existing?.nip05,
                banner = existing?.banner,
                rawContentJson = content
            )
            metadataManager.updateLocalMetadata(pubKey, updatedMetadata)

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
                content = ""
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
            clients.forEach { client ->
                scope.launch {
                    try { client.sendAndAwaitOk(message, eventId) } catch (_: Exception) {}
                }
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
        publishJoinedGroupsListWith(pubKey)
    }

    /**
     * Publish kind:10009 with an optional custom relay list.
     * Returns the result so callers can check if the signer approved.
     */
    private suspend fun publishJoinedGroupsListWith(
        pubKey: String,
        nip29Relays: List<String> = outboxManager.kind10009Relays.value.toList()
    ): Result<Unit> {
        // _joinedGroupsByRelay is the single authoritative per-relay membership map.
        // DO NOT merge _joinedGroups (the active-relay view) — it only reflects a
        // single relay and would overwrite correct per-relay data on cross-relay ops.
        val perRelay = groupManager.joinedGroupsByRelay.value
        return outboxManager.publishJoinedGroupsList(
            pubKey = pubKey,
            joinedGroupsByRelay = perRelay,
            nip29Relays = nip29Relays,
            signEvent = { sessionManager.signEvent(it) },
            messageHandler = { msg, client -> handleRelayMessage(msg, client) }
        )
    }

    // Message handling
    // Groups whose subscriptions were closed by the relay (e.g. auth-required)
    private val closedGroupSubscriptions = mutableSetOf<String>()

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
    private fun handleUnifiedMessage(msg: String, client: NostrGroupClient) {
        // Parse once — every downstream handler reuses this JsonArray.
        val arr = try {
            json.parseToJsonElement(msg).jsonArray
        } catch (_: Exception) { return }

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
                        messageHandler = { m, c -> enqueueToRelayPipeline(m, c) }
                    )
                }
                return
            }
            if (kind == 10002) {
                outboxManager.handleKind10002Event(event, sessionManager.getPublicKey())
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
                scope.launch {
                    sessionManager.handleAuthChallenge(client, authChallenge)
                    // Signal that AUTH is done so connect()/switchRelay() can
                    // proceed with requestGroups() after the relay accepted auth.
                    client.notifyAuthCompleted()
                    resubscribeAfterAuth(client)
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
                scope.launch {
                    yield()
                    groupManager.handleEoseSuspend(subId)
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
                        }
                    }
                    // Close one-shot subs after EOSE so the relay slot is freed.
                    if (subId.startsWith("meta_") ||
                        subId.startsWith("admins_") ||
                        subId.startsWith("members_") ||
                        subId.startsWith("metadata_") ||
                        subId.startsWith("e_") ||
                        subId.startsWith("a_") ||
                        subId.startsWith("reactions_") ||
                        subId.startsWith("event_")) {
                        try { client.send("""["CLOSE","$subId"]""") } catch (_: Exception) {}
                    }
                }
            }

            "CLOSED" -> {
                if (arr.size < 2) return
                val subId = arr[1].jsonPrimitive.content
                val reason = if (arr.size >= 3) arr[2].jsonPrimitive.contentOrNull ?: "" else ""
                val isRestricted = reason.contains("restricted")
                val isAuthRequired = reason.contains("auth-required")

                // "restricted" on the group-list subscription means the relay
                // actively denies access — stop loading, show error, disconnect.
                if (isRestricted && subId.startsWith("group-list")) {
                    val relayUrl = client.getRelayUrl()
                    _restrictedRelays.value = _restrictedRelays.value + (relayUrl to reason)
                    groupManager.markRelayLoaded(relayUrl)
                    connectionManager.setError(reason)
                    return
                }

                if (isAuthRequired) {
                    // Only record groups that belong to THIS relay so resubscribeAfterAuth
                    // doesn't send cross-relay metadata requests to the authed client.
                    val relayUrl = client.getRelayUrl()
                    val activeGroupIds = groupManager.getGroupIdsForMux(relayUrl)
                    closedGroupSubscriptions.addAll(activeGroupIds)
                }

                // Unblock any pending state-machine load (transitions InitialLoading → Exhausted)
                scope.launch { groupManager.handleEoseSuspend(subId) }

                // Re-open the mux subscription when the relay closes it for non-auth reasons.
                // pyramid.fiatjaf.com and similar relays drop idle subs without closing the WS.
                if (!isAuthRequired && !isRestricted &&
                    (subId.startsWith("mux_chat_") || subId.startsWith("mux_reactions_") ||
                     subId.startsWith("mux_meta_"))) {
                    val relayUrl = client.getRelayUrl()
                    scope.launch {
                        delay(2_000)  // brief back-off before re-opening
                        groupManager.refreshMuxDebounced(relayUrl)
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
                                requestUserMetadata(pubkeysNeedingMetadata.toSet())
                            }
                        }
                    }

                    39001 -> {
                        val groupAdmins = client.parseGroupAdmins(event) ?: return
                        val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                        groupManager.handleGroupAdmins(groupAdmins, createdAt)
                    }

                    0 -> {
                        val (pubkey, metadata) = client.parseUserMetadata(event) ?: return
                        metadataManager.handleMetadataEvent(pubkey, metadata)
                    }

                    7 -> {
                        val reaction = client.parseReaction(event) ?: return
                        val reactorPubkey = groupManager.handleReaction(reaction)
                        if (reactorPubkey != null && !metadataManager.hasMetadata(reactorPubkey)) {
                            scope.launch {
                                requestUserMetadata(setOf(reactorPubkey))
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
                            messageHandler = { m, c -> enqueueToRelayPipeline(m, c) }
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
            }
        }

        // Mux subs cover all kinds: 39000/39001/39002 (metadata/members/admins) +
        // chat/reactions for opened groups.
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)
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
                    val priority = if (relayUrl == activeRelayUrl)
                        RelayReconnectScheduler.Priority.ACTIVE
                    else
                        RelayReconnectScheduler.Priority.BACKGROUND
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
        groupManager.restoreGroupsForRelay(relayUrl)
        if (!groupManager.hasCachedGroupsForRelay(relayUrl)) {
            lastRequestGroupsAt[relayUrl] = epochSeconds()
            groupManager.markRelayLoading(relayUrl)
            client.requestGroups()
        }

        // Reset loading states for opened groups so pagination works if user scrolls up.
        val openedGroupIds = groupManager.getOpenedGroupIds()
            .filter { groupManager.getRelayForGroup(it) == relayUrl }
        if (openedGroupIds.isNotEmpty()) {
            groupManager.handleConnectionLostForGroups(openedGroupIds.toList())
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
            if (now - lastAt > 10L) {
                lastRequestGroupsAt[relayUrl] = now
                groupManager.markRelayLoading(relayUrl)
                client.requestGroups()
            }
        }

        // Reset loading states for opened + auth-closed groups.
        // Uses resetLoadingForGroups (NOT handleConnectionLostForGroups) to avoid
        // clearing the mux tracker — resubscribeAllGroups already sent the mux refresh,
        // so the tracker correctly reflects the current state.
        val openedOnRelay = groupManager.getOpenedGroupIds()
            .filter { groupManager.getRelayForGroup(it) == relayUrl }
        val closedOnRelay = closedGroupSubscriptions.filter {
            groupManager.getRelayForGroup(it) == relayUrl
        }
        closedGroupSubscriptions.removeAll(closedOnRelay.toSet())

        val groupIds = (openedOnRelay + closedOnRelay).distinct()
        if (groupIds.isNotEmpty()) {
            groupManager.resetLoadingForGroups(groupIds)
        }

        // Force-clear the mux tracker so the refresh always re-sends subscriptions.
        // The relay may have dropped active subs when it sent the AUTH challenge
        // (e.g. communities.nos.social re-challenges AUTH periodically).
        groupManager.clearMuxTrackerForRelay(relayUrl)
        groupManager.refreshMuxSubscriptionsForRelay(relayUrl)

        // Re-request historical messages for groups that were CLOSED with
        // auth-required. The mux only delivers live messages (since cursor),
        // so without this the chat stays empty after AUTH completes.
        for (groupId in groupIds) {
            groupManager.requestGroupMessages(groupId)
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
