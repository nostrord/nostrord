package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getCurrentRelayUrlFor
import org.nostr.nostrord.storage.saveCurrentRelayUrlFor
import org.nostr.nostrord.utils.normalizeRelayUrl
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * Manages relay connections and connection pooling.
 * Handles focused NIP-29 relay connection and auxiliary relay pool.
 * Includes automatic reconnection with exponential backoff.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val connStats: ConnectionStats? = null,
    private val adaptiveConfig: AdaptiveConfig? = null,
) {
    private var reconnectJob: Job? = null
    private var currentMessageHandler: ((String, NostrGroupClient) -> Unit)? = null

    // Serialises connectFocused so two coroutines cannot both pass the "already connecting"
    // guard simultaneously (Dispatchers.Default is multi-threaded).
    private val connectMutex = Mutex()

    private val _currentRelayUrl = MutableStateFlow("")
    val currentRelayUrl: StateFlow<String> = _currentRelayUrl.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Track reconnection attempts for exponential backoff
    private var reconnectAttempts = 0
    private var autoReconnectEnabled = true

    // Shared relay pool for all auxiliary connections (outbox, metadata, NIP-65)
    private val poolMutex = Mutex()
    private val relayPool = mutableMapOf<String, NostrGroupClient>()

    /**
     * The client for [_currentRelayUrl], if connected. It lives in [relayPool] like
     * every other relay — there is no separate "focused" storage slot.
     */
    private fun focusedClient(): NostrGroupClient? = relayPool[_currentRelayUrl.value]

    // Gate for opening NEW pool sockets. clearAll() (logout) flips it off so
    // background jobs can't reconnect relays during/after teardown and leak them;
    // resumeConnections() (a session activating) flips it back on. Default on so
    // the normal logged-in path is unaffected.
    @Volatile
    private var acceptingConnections = true

    /** Re-allow pool connections after a logout gated them off. Idempotent. */
    fun resumeConnections() {
        acceptingConnections = true
    }

    // Per-relay (normalized URL) outcome of the most recent WebSocket connect:
    // true = opened, false = failed. Drives reachability filtering on discovery
    // surfaces. A relay that connected then dropped stays `true` (it is reachable,
    // just reconnecting) so flaky relays are not hidden.
    private val _relayReachability = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val relayReachability: StateFlow<Map<String, Boolean>> = _relayReachability.asStateFlow()

    private fun markReachability(relayUrl: String, reachable: Boolean) {
        _relayReachability.update { current ->
            if (current[relayUrl] == reachable) current else current + (relayUrl to reachable)
        }
    }

    // Singleflight: in-flight connection attempts per URL. Without this,
    // concurrent callers of getOrConnectRelay for the same URL each create
    // their own NostrGroupClient (the existing double-check pattern released
    // the lock during the actual WebSocket handshake), opening N parallel
    // sockets and then closing all-but-one as they each lose the race-to-
    // insert. The browser network panel shows the full N sockets and the
    // relay sees N connects/disconnects in quick succession.
    private val pendingConnects = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<NostrGroupClient?>>()

    /**
     * Called when the focused connection drops unexpectedly.
     * Set by NostrRepository to also notify GroupManager.
     */
    var onConnectionDropped: (() -> Unit)? = null

    /**
     * Called after a successful auto-reconnect with the new client.
     * Set by NostrRepository to re-auth and re-subscribe.
     */
    var onReconnected: (suspend (NostrGroupClient) -> Unit)? = null

    /**
     * Called when a pool relay drops unexpectedly.
     * Set by NostrRepository to trigger reconnection with backoff.
     */
    var onPoolRelayLost: ((relayUrl: String) -> Unit)? = null

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()

        data object Connecting : ConnectionState()

        data object Connected : ConnectionState()

        data class Error(
            val message: String,
        ) : ConnectionState()

        data class Reconnecting(
            val attempt: Int,
            val maxAttempts: Int,
        ) : ConnectionState()
    }

    private var networkMonitorJob: Job? = null

    /**
     * Start listening for platform network change events.
     * Call once after construction (e.g. from NostrRepository.initialize).
     */
    fun startNetworkMonitor() {
        networkMonitorJob?.cancel()
        networkMonitorJob =
            scope.launch {
                createNetworkMonitorFlow().collect { event ->
                    when (event) {
                        NetworkEvent.CHANGED -> {
                            // IP changed — kill stale socket and reconnect immediately.
                            if (focusedClient() != null) {
                                reconnectImmediate()
                            }
                        }
                        NetworkEvent.DISCONNECTED -> {
                            // No point retrying while offline — save battery.
                            autoReconnectEnabled = false
                            reconnectJob?.cancel()
                        }
                        NetworkEvent.CONNECTED -> {
                            autoReconnectEnabled = true
                            if (focusedClient() == null && _currentRelayUrl.value.isNotBlank()) {
                                reconnectImmediate()
                            }
                        }
                    }
                }
            }
    }

    /**
     * Fast-path reconnect for network change events.
     * Bypasses exponential backoff — the network is available, just different.
     */
    private suspend fun reconnectImmediate() {
        reconnectJob?.cancel()
        reconnectAttempts = 0

        val url = _currentRelayUrl.value
        val dead = focusedClient()
        dead?.onConnectionLost = null
        if (dead != null) poolMutex.withLock { relayPool.remove(url) }
        dead?.cancelAndClose()

        onConnectionDropped?.invoke()

        val handler = currentMessageHandler ?: return
        val success = connectFocused(url, handler)
        if (success) {
            adaptiveConfig?.recordReconnect()
            focusedClient()?.let { client -> scope.launch { onReconnected?.invoke(client) } }
        } else {
            startReconnection()
        }
    }

    /**
     * Load the active account's saved relay URL into [currentRelayUrl].
     *
     * Pubkey-scoped: without an active account we leave the StateFlow blank
     * so the rail starts empty for a freshly added (yet-to-be-activated)
     * identity instead of inheriting the previous account's relay.
     */
    suspend fun loadSavedRelay() {
        val pubkey = ActiveAccountManager.currentPubkey
        val savedRelayUrl =
            if (pubkey.isNullOrBlank()) {
                null
            } else {
                SecureStorage.getCurrentRelayUrlFor(pubkey)
            }
        _currentRelayUrl.value = savedRelayUrl?.normalizeRelayUrl().orEmpty()
    }

    fun clearCurrentRelay() {
        _currentRelayUrl.value = ""
    }

    /**
     * Get the focused NIP-29 client
     */
    fun getFocusedClient(): NostrGroupClient? = focusedClient()

    /**
     * Returns the client for a specific relay URL: the pool (focused included), then
     * any in-flight connect.
     * Does not START a new connection, but if one is already in flight for this
     * URL (the [getOrConnectRelay] singleflight, e.g. the bootstrap fan-out)
     * this awaits it instead of reporting "not connected".
     *
     * Awaiting the pending connect is what keeps cold start from opening
     * duplicate sockets: the metadata / nip65 / contacts readers all call this
     * the instant the app boots, while the bootstrap relays are still
     * connecting. Returning null there made each reader treat the relay as
     * down and open its own socket (observed: 8 concurrent sockets to nos.lol).
     * Converging on the shared singleflight deferred collapses that to one.
     */
    suspend fun getClientForRelay(relayUrl: String): NostrGroupClient? {
        // A blank URL (or, via a platform-type leak in a relay list, a runtime null) has no
        // client. Guard before normalizeRelayUrl, whose non-null receiver check would otherwise
        // crash the caller; this is a suspend fun, so the parameter null-check is not emitted.
        if (relayUrl.isNullOrBlank()) return null
        val normalized = relayUrl.normalizeRelayUrl()
        // The pool holds every connected relay, focused included, so a single lookup covers it.
        val (pooled, pending) =
            poolMutex.withLock { relayPool[normalized] to pendingConnects[normalized] }
        if (pooled != null) return pooled
        return pending?.await()
    }

    /**
     * Every currently-connected client (focused + pool). Used to broadcast a by-id REQ
     * when a relay hint is wrong or missing: a NIP-29 event lives only on its group's
     * relay, and the hint can point elsewhere, so querying just the hint misses it.
     */
    suspend fun getAllConnectedClients(): List<NostrGroupClient> = poolMutex.withLock { relayPool.values.toList() }.filter { it.isConnected() }

    /**
     * Connect to the focused NIP-29 relay.
     * Serialised by [connectMutex] — concurrent callers block until the in-flight
     * attempt finishes, then return false immediately if a client is already up.
     */
    suspend fun connectFocused(
        relayUrl: String = _currentRelayUrl.value,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): Boolean = connectMutex.withLock {
        if (relayUrl.isBlank()) return@withLock false
        val normalized = relayUrl.normalizeRelayUrl()
        if (focusedClient() != null) return@withLock false

        currentMessageHandler = onMessage
        _connectionState.value = ConnectionState.Connecting
        connStats?.onConnecting(relayUrl)

        try {
            val newClient = NostrGroupClient(relayUrl)
            poolMutex.withLock { relayPool[normalized] = newClient }
            wireConnectionLost(newClient, normalized)

            newClient.connect { msg -> onMessage(msg, newClient) }
            val opened = newClient.waitForConnection()
            if (!opened) {
                // CRITICAL: detach onConnectionLost BEFORE removing from the pool.
                // Without this, the orphaned client's WebSocket may open then close later
                // and fire handleClientLost(), killing any focused that was connected
                // in the meantime by the reconnect loop.
                newClient.onConnectionLost = null
                newClient.cancelAndClose()
                poolMutex.withLock { relayPool.remove(normalized) }
                if (_currentRelayUrl.value == normalized) {
                    _connectionState.value = ConnectionState.Error("Connection timed out")
                }
                connStats?.onConnectFailed(relayUrl)
                markReachability(normalized, false)
                return@withLock false
            }
            _connectionState.value = ConnectionState.Connected
            connStats?.onConnected(relayUrl)
            markReachability(normalized, true)
            // Feed adaptive config: relay connection latency
            connStats?.getStats()?.get(relayUrl)?.lastReconnectMs?.let { latency ->
                adaptiveConfig?.recordRelayLatency(relayUrl, latency)
            }
            reconnectAttempts = 0
            true
        } catch (e: Exception) {
            if (_currentRelayUrl.value == normalized) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
            focusedClient()?.let {
                it.onConnectionLost = null
                it.cancelAndClose()
            }
            poolMutex.withLock { relayPool.remove(normalized) }
            connStats?.onConnectFailed(relayUrl)
            false
        }
    }

    /**
     * Every pooled client — focused or not — is wired through this one handler. It
     * re-derives the client's role from [_currentRelayUrl] at drop time instead of at
     * wiring time, so moving focus ([setFocusedRelay]) never has to re-wire a client's
     * callback: the same closure resolves to the fast focused-reconnect path or the
     * background pool-reconnect path depending on who currently holds focus.
     */
    private fun wireConnectionLost(client: NostrGroupClient, url: String) {
        client.onConnectionLost = {
            scope.launch { handleClientLost(client, url) }
        }
    }

    private suspend fun handleClientLost(client: NostrGroupClient, url: String) {
        // Detach the callback first to prevent re-entrant calls if cancelAndClose()
        // somehow triggers another connection-lost event on the dying client.
        client.onConnectionLost = null
        poolMutex.withLock { relayPool.remove(url) }
        connStats?.onDisconnected(url)

        if (_currentRelayUrl.value != url) {
            // A pool relay (not focused): hand off to the caller's own reconnect scheduler.
            client.cancelAndClose()
            onPoolRelayLost?.invoke(url)
            return
        }

        onConnectionDropped?.invoke()
        client.cancelAndClose() // release HttpClient threads/pool, cancel lingering coroutines
        if (!autoReconnectEnabled) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }
        startReconnection()
    }

    /**
     * Start reconnection with exponential backoff, then persistent slow retry.
     *
     * Phase 1 (fast): exponential back-off from 1 s up to 30 s, MAX_RECONNECT_ATTEMPTS times.
     * Phase 2 (slow): retry every 30 s indefinitely until success or explicit disconnect.
     *
     * Phase 2 is what makes the app self-heal after an extended internet outage — the loop
     * never exits on its own, so when connectivity returns the next 30-second tick reconnects
     * automatically without requiring the user to restart the app.
     */
    private fun startReconnection() {
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                // Phase 1: fast retries with exponential back-off
                while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && autoReconnectEnabled) {
                    reconnectAttempts++
                    _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)

                    val baseMs =
                        minOf(
                            INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1)),
                            MAX_RECONNECT_DELAY_MS,
                        )
                    val jitter = (baseMs * Random.nextDouble(0.0, 0.25)).toLong()
                    delay(baseMs + jitter)

                    val handler = currentMessageHandler ?: break
                    val success = connectFocused(_currentRelayUrl.value, handler)

                    if (success) {
                        reconnectAttempts = 0
                        adaptiveConfig?.recordReconnect()
                        focusedClient()?.let { client -> scope.launch { onReconnected?.invoke(client) } }
                        return@launch
                    }
                }

                // Phase 2: persistent slow retry every 30 s — never give up.
                // The UI already shows "Tap to retry" so the user can force an immediate attempt,
                // but the background loop ensures automatic recovery without any user action.
                _connectionState.value = ConnectionState.Error("Connection lost. Tap to retry.")

                while (autoReconnectEnabled) {
                    delay(MAX_RECONNECT_DELAY_MS)
                    if (!autoReconnectEnabled) break

                    val handler = currentMessageHandler ?: break
                    val success = connectFocused(_currentRelayUrl.value, handler)

                    if (success) {
                        reconnectAttempts = 0
                        adaptiveConfig?.recordReconnect()
                        focusedClient()?.let { client -> scope.launch { onReconnected?.invoke(client) } }
                        return@launch
                    }
                }
            }
    }

    /**
     * Manually trigger reconnection (e.g., from a retry button)
     */
    suspend fun reconnect(): Boolean {
        reconnectJob?.cancel()
        reconnectAttempts = 0
        autoReconnectEnabled = true

        // Detach onConnectionLost BEFORE disconnecting: the dying socket's close
        // event fires asynchronously and would otherwise run handleClientLost
        // AFTER connectFocused installs the replacement client — killing the fresh
        // connection and flipping the banner to "Unable to connect" (seen on every
        // account switch, which reconnects through here).
        val url = _currentRelayUrl.value
        val dying = focusedClient()
        dying?.onConnectionLost = null
        dying?.disconnect()
        if (dying != null) poolMutex.withLock { relayPool.remove(url) }

        val handler = currentMessageHandler ?: return false
        return connectFocused(url, handler)
    }

    /**
     * Set the connection state to Error and stop auto-reconnect.
     * Used when the relay actively rejects access (e.g. "restricted").
     */
    fun setError(message: String) {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        _connectionState.value = ConnectionState.Error(message)
    }

    /**
     * Disconnect the focused relay
     */
    suspend fun disconnectFocused() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null

        // Same orphan-callback hazard as reconnect(): a late close event from this
        // socket must not tear down whatever focused connects next.
        val url = _currentRelayUrl.value
        val client = focusedClient()
        client?.onConnectionLost = null
        client?.disconnect()
        if (client != null) poolMutex.withLock { relayPool.remove(url) }
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Move focus to [newRelayUrl]. Every pooled client (the previously-focused relay
     * included) is already wired through the same role-agnostic [wireConnectionLost]
     * handler, so there is no promote/demote: the old relay simply keeps its pool
     * entry and starts reconnecting via the background path the next time it drops,
     * and the new relay's entry (if any) becomes focused just by updating
     * [_currentRelayUrl].
     */
    suspend fun setFocusedRelay(
        newRelayUrl: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): Boolean {
        val normalizedNewUrl = newRelayUrl.normalizeRelayUrl()
        // Already focused on this relay: keep the existing socket. Falling through would
        // treat it as a fresh connect, which on a deep link to a group whose relay
        // initialize() already connected shows as OPEN/CLOSE/OPEN churn and, on a private
        // relay, throws away the AUTH'd socket. Just refresh the handler.
        if (_currentRelayUrl.value == normalizedNewUrl && focusedClient() != null) {
            currentMessageHandler = onMessage
            return focusedClient()?.isConnected() == true
        }

        // Capture an in-flight pool connect (the singleflight deferred in
        // pendingConnects) for the new relay before moving focus. Without this,
        // setFocusedRelay misses a connection that is mid-handshake and connectFocused
        // opens a SECOND socket to the same relay. On a private relay that splits the
        // NIP-42 handshake across two sockets (challenge on one, reads on the other),
        // so the group never authenticates. Observed as OPEN/CLOSE/OPEN churn on
        // cold-start deep links.
        val pendingConnect = poolMutex.withLock { pendingConnects[normalizedNewUrl] }

        autoReconnectEnabled = true
        reconnectJob?.cancel()
        reconnectJob = null

        _currentRelayUrl.value = normalizedNewUrl
        ActiveAccountManager.currentPubkey?.takeIf { it.isNotBlank() }?.let { pubkey ->
            SecureStorage.saveCurrentRelayUrlFor(pubkey, normalizedNewUrl)
        }

        // Reclaim the in-flight connect's client once it lands (its leader parks it in
        // relayPool on success) so it's visible as focused instead of opening a duplicate.
        pendingConnect?.await()

        if (focusedClient() != null) {
            // Already pooled (either it was already connected, or the pending connect
            // above just landed it) — just adopt it as focused.
            currentMessageHandler = onMessage
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0
            return true
        }

        return connectFocused(normalizedNewUrl, onMessage)
    }

    /**
     * Disconnect and remove a specific relay, whether it's in the pool or is the focused.
     */
    suspend fun disconnectRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        if (_currentRelayUrl.value == normalized) {
            disconnectFocused()
            return
        }
        poolMutex.withLock {
            relayPool.remove(normalized)?.disconnect()
        }
    }

    /**
     * Get or create a connection to a relay in the pool
     */
    suspend fun getOrConnectRelay(
        relayUrl: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): NostrGroupClient? {
        val normalized = relayUrl.normalizeRelayUrl()
        // Fast-path: already pooled. Covers the focused relay too — it lives in the
        // same map since the connection pool fold.
        poolMutex.withLock {
            relayPool[normalized]?.let { return it }
        }

        // Logged-out gate: clearAll() flips this off at the start of a logout, so
        // background discovery / DM / metadata jobs on app-lifetime scopes that
        // keep calling here for a beat after the pool is drained cannot reconnect
        // (and leak) every relay. resumeConnections() turns it back on when a
        // session activates. Existing pooled clients are still returned above.
        if (!acceptingConnections) return null

        // Singleflight gate: at most one in-flight connect per URL. Concurrent
        // callers attach to the same CompletableDeferred and share its result
        // instead of each opening their own socket.
        val deferred: kotlinx.coroutines.CompletableDeferred<NostrGroupClient?>
        val isLeader: Boolean
        poolMutex.withLock {
            // Re-check the pool under the lock — a sibling caller may have
            // resolved during our handoff between mutex acquisitions.
            relayPool[normalized]?.let { return it }
            val existing = pendingConnects[normalized]
            if (existing != null) {
                deferred = existing
                isLeader = false
            } else {
                deferred = kotlinx.coroutines.CompletableDeferred()
                pendingConnects[normalized] = deferred
                isLeader = true
            }
        }
        if (!isLeader) {
            return deferred.await()
        }

        // We're the leader. Open the socket, then publish the result.
        connStats?.onConnecting(normalized)
        var result: NostrGroupClient? = null
        try {
            val newClient = NostrGroupClient(normalized)
            wireConnectionLost(newClient, normalized)
            newClient.connect { msg -> onMessage(msg, newClient) }
            val connected = newClient.waitForConnection()
            if (!connected) {
                newClient.disconnect()
                connStats?.onConnectFailed(normalized)
                markReachability(normalized, false)
                result = null
            } else {
                poolMutex.withLock { relayPool[normalized] = newClient }
                connStats?.onConnected(normalized)
                markReachability(normalized, true)
                connStats?.getStats()?.get(normalized)?.lastReconnectMs?.let { latency ->
                    adaptiveConfig?.recordRelayLatency(normalized, latency)
                }
                result = newClient
            }
        } catch (e: Exception) {
            connStats?.onConnectFailed(normalized)
            markReachability(normalized, false)
            result = null
        } finally {
            poolMutex.withLock { pendingConnects.remove(normalized) }
            deferred.complete(result)
        }
        return result
    }

    /**
     * Send a message to multiple relays in the pool
     */
    fun sendToRelays(
        relayUrls: List<String>,
        message: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ) {
        relayUrls.forEach { relayUrl ->
            scope.launch {
                try {
                    val client = getOrConnectRelay(relayUrl, onMessage)
                    client?.send(message)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Connect to multiple relays in parallel
     */
    suspend fun connectToRelaysParallel(
        relayUrls: List<String>,
        onMessage: (String, NostrGroupClient) -> Unit,
        timeoutMs: Long = 2000,
    ) {
        relayUrls.map { relayUrl ->
            scope.launch {
                try {
                    getOrConnectRelay(relayUrl, onMessage)
                } catch (_: Exception) {
                }
            }
        }

        // Wait briefly for at least one connection
        withTimeoutOrNull(timeoutMs) {
            while (!hasPoolConnections()) {
                delay(50)
            }
        }
    }

    /**
     * Check if any relay is connected in the pool
     */
    suspend fun hasPoolConnections(): Boolean = poolMutex.withLock {
        relayPool.isNotEmpty()
    }

    /**
     * CLOSE every live subscription on every open socket (focused + pool). Called on account
     * switch so the outgoing account's mux chat/meta and dm_inbox REQs stop pushing events
     * into the incoming account's freshly-cleared state (those subs are group/kind-filtered,
     * not pubkey-filtered, so they would otherwise re-populate the new account's rail/DM list
     * with the old account's data). Sockets stay connected; the new account re-subscribes.
     */
    suspend fun closeAllSubscriptionsOnAllClients() {
        val clients = poolMutex.withLock { relayPool.values.toList() }
        for (client in clients) {
            try {
                client.closeAllSubscriptions()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Clear all connections, disconnecting pool clients in parallel.
     */
    suspend fun clearAll() {
        // Stop accepting NEW pool connects for the rest of the teardown so a
        // discovery/DM/metadata job on a surviving scope can't reconnect a relay
        // right after we drain the pool. resumeConnections() re-opens on login.
        acceptingConnections = false
        scope.coroutineContext.cancelChildren()
        disconnectFocused()

        // Get clients to disconnect while holding lock, then disconnect outside lock
        val clientsToDisconnect =
            poolMutex.withLock {
                val clients = relayPool.values.toList()
                relayPool.clear()
                clients
            }
        // Parallel disconnect — avoids multiplying per-relay close latency
        clientsToDisconnect
            .map { client ->
                scope.launch {
                    try {
                        client.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }.forEach { it.join() }
    }
}
