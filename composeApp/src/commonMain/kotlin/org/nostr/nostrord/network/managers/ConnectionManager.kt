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
import kotlin.random.Random

/**
 * Manages relay connections and connection pooling.
 * Handles primary NIP-29 relay connection and auxiliary relay pool.
 * Includes automatic reconnection with exponential backoff.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val connStats: ConnectionStats? = null,
    private val adaptiveConfig: AdaptiveConfig? = null,
) {
    private var primaryClient: NostrGroupClient? = null
    private var reconnectJob: Job? = null
    private var currentMessageHandler: ((String, NostrGroupClient) -> Unit)? = null

    // Serialises connectPrimary so two coroutines cannot both pass the "already connecting"
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
     * Called when the primary connection drops unexpectedly.
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
                            if (primaryClient != null) {
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
                            if (primaryClient == null && _currentRelayUrl.value.isNotBlank()) {
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

        val dead = primaryClient
        dead?.onConnectionLost = null
        primaryClient = null
        dead?.cancelAndClose()

        onConnectionDropped?.invoke()

        val handler = currentMessageHandler ?: return
        val success = connectPrimary(_currentRelayUrl.value, handler)
        if (success) {
            adaptiveConfig?.recordReconnect()
            primaryClient?.let { client -> scope.launch { onReconnected?.invoke(client) } }
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
     * Get the primary NIP-29 client
     */
    fun getPrimaryClient(): NostrGroupClient? = primaryClient

    /**
     * Returns the client for a specific relay URL.
     * Checks the primary first, then the pool, then any in-flight connect.
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
        val normalized = relayUrl.normalizeRelayUrl()
        if (_currentRelayUrl.value == normalized) return primaryClient
        val (pooled, pending) =
            poolMutex.withLock { relayPool[normalized] to pendingConnects[normalized] }
        if (pooled != null) return pooled
        return pending?.await()
    }

    /**
     * Connect to the primary NIP-29 relay.
     * Serialised by [connectMutex] — concurrent callers block until the in-flight
     * attempt finishes, then return false immediately if a client is already up.
     */
    suspend fun connectPrimary(
        relayUrl: String = _currentRelayUrl.value,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): Boolean = connectMutex.withLock {
        if (relayUrl.isBlank()) return@withLock false
        if (primaryClient != null) return@withLock false

        currentMessageHandler = onMessage
        _connectionState.value = ConnectionState.Connecting
        connStats?.onConnecting(relayUrl)

        try {
            val newClient = NostrGroupClient(relayUrl)
            primaryClient = newClient

            newClient.onConnectionLost = {
                scope.launch { handleConnectionLost() }
            }

            newClient.connect { msg -> onMessage(msg, newClient) }
            val opened = newClient.waitForConnection()
            if (!opened) {
                // CRITICAL: detach onConnectionLost BEFORE nulling primaryClient.
                // Without this, the orphaned client's WebSocket may open then close later
                // and fire handleConnectionLost(), killing any primary that was connected
                // in the meantime by the reconnect loop.
                newClient.onConnectionLost = null
                newClient.cancelAndClose()
                primaryClient = null
                if (_currentRelayUrl.value == relayUrl.normalizeRelayUrl()) {
                    _connectionState.value = ConnectionState.Error("Connection timed out")
                }
                connStats?.onConnectFailed(relayUrl)
                markReachability(relayUrl.normalizeRelayUrl(), false)
                return@withLock false
            }
            _connectionState.value = ConnectionState.Connected
            connStats?.onConnected(relayUrl)
            markReachability(relayUrl.normalizeRelayUrl(), true)
            // Feed adaptive config: relay connection latency
            connStats?.getStats()?.get(relayUrl)?.lastReconnectMs?.let { latency ->
                adaptiveConfig?.recordRelayLatency(relayUrl, latency)
            }
            reconnectAttempts = 0
            true
        } catch (e: Exception) {
            if (_currentRelayUrl.value == relayUrl.normalizeRelayUrl()) {
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
            primaryClient?.let {
                it.onConnectionLost = null
                it.cancelAndClose()
            }
            primaryClient = null
            connStats?.onConnectFailed(relayUrl)
            false
        }
    }

    /**
     * Handle connection lost - notify observers then attempt auto-reconnection.
     */
    private suspend fun handleConnectionLost() {
        // Detach the callback first to prevent re-entrant calls if cancelAndClose()
        // somehow triggers another connection-lost event on the dying client.
        val dead = primaryClient
        dead?.onConnectionLost = null

        connStats?.onDisconnected(_currentRelayUrl.value)
        onConnectionDropped?.invoke()

        if (!autoReconnectEnabled) {
            primaryClient = null
            dead?.cancelAndClose()
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        primaryClient = null
        dead?.cancelAndClose() // release HttpClient threads/pool, cancel lingering coroutines
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
                    val success = connectPrimary(_currentRelayUrl.value, handler)

                    if (success) {
                        reconnectAttempts = 0
                        adaptiveConfig?.recordReconnect()
                        primaryClient?.let { client -> scope.launch { onReconnected?.invoke(client) } }
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
                    val success = connectPrimary(_currentRelayUrl.value, handler)

                    if (success) {
                        reconnectAttempts = 0
                        adaptiveConfig?.recordReconnect()
                        primaryClient?.let { client -> scope.launch { onReconnected?.invoke(client) } }
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
        // event fires asynchronously and would otherwise run handleConnectionLost
        // AFTER connectPrimary installs the replacement client — killing the fresh
        // connection and flipping the banner to "Unable to connect" (seen on every
        // account switch, which reconnects through here).
        primaryClient?.onConnectionLost = null
        primaryClient?.disconnect()
        primaryClient = null

        val handler = currentMessageHandler ?: return false
        return connectPrimary(_currentRelayUrl.value, handler)
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
     * Disconnect the primary relay
     */
    suspend fun disconnectPrimary() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null

        // Same orphan-callback hazard as reconnect(): a late close event from this
        // socket must not tear down whatever primary connects next.
        primaryClient?.onConnectionLost = null
        primaryClient?.disconnect()
        primaryClient = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Switch to a new relay while keeping the old primary alive in the pool.
     *
     * The old primary is moved into [relayPool] (if not already present) so it
     * continues receiving messages and contributing group metadata to the unified
     * state.  The new relay is then connected as the primary.  If the new relay
     * URL is already in the pool, that existing connection is promoted to primary.
     */
    suspend fun switchRelay(
        newRelayUrl: String,
        onMessage: (String, NostrGroupClient) -> Unit,
    ): Boolean {
        val normalizedNewUrl = newRelayUrl.normalizeRelayUrl()
        // Already primary on this relay: keep the existing socket. Falling through would
        // null primaryClient and connectPrimary a second one (the same-URL branch below
        // skips moving it to the pool, so it gets orphaned), which on a deep link to a
        // group whose relay initialize() already connected shows as OPEN/CLOSE/OPEN churn
        // and, on a private relay, throws away the AUTH'd socket. Just refresh the handler.
        if (_currentRelayUrl.value == normalizedNewUrl && primaryClient != null) {
            currentMessageHandler = onMessage
            return primaryClient?.isConnected() == true
        }
        // Move the current primary into the pool instead of disconnecting it.
        val oldPrimary = primaryClient
        val oldUrl = _currentRelayUrl.value
        if (oldPrimary != null && oldUrl != normalizedNewUrl) {
            // Re-wire onConnectionLost to the pool handler before adding to pool.
            // Without this, the demoted primary fires handleConnectionLost() on drop,
            // which reconnects the WRONG relay (_currentRelayUrl, already the new one).
            oldPrimary.onConnectionLost = {
                scope.launch {
                    poolMutex.withLock { relayPool.remove(oldUrl) }
                    onPoolRelayLost?.invoke(oldUrl)
                }
            }
            poolMutex.withLock {
                if (!relayPool.containsKey(oldUrl)) {
                    relayPool[oldUrl] = oldPrimary
                }
            }
        }

        // If the new relay is already in the pool, promote it to primary. Also capture an
        // in-flight pool connect (the singleflight deferred in pendingConnects): without
        // this, switchRelay misses a connection that is mid-handshake and connectPrimary
        // opens a SECOND socket to the same relay. On a private relay that splits the NIP-42
        // handshake across two sockets (challenge on one, reads on the other), so the group
        // never authenticates. Observed as OPEN/CLOSE/OPEN churn on cold-start deep links.
        val (existingPoolClient, pendingConnect) =
            poolMutex.withLock { relayPool.remove(normalizedNewUrl) to pendingConnects[normalizedNewUrl] }

        // Clear the primary slot (without disconnecting the old client).
        primaryClient = null
        autoReconnectEnabled = true
        reconnectJob?.cancel()
        reconnectJob = null

        _currentRelayUrl.value = normalizedNewUrl
        ActiveAccountManager.currentPubkey?.takeIf { it.isNotBlank() }?.let { pubkey ->
            SecureStorage.saveCurrentRelayUrlFor(pubkey, normalizedNewUrl)
        }

        // Reclaim the in-flight connect's client once it lands (its leader parks it in
        // relayPool on success), so the live socket becomes primary instead of a duplicate.
        val promoted = existingPoolClient
            ?: pendingConnect?.await()?.also { poolMutex.withLock { relayPool.remove(normalizedNewUrl) } }

        if (promoted != null) {
            // Re-use the already-connected pool client as the new primary.
            primaryClient = promoted
            promoted.onConnectionLost = {
                scope.launch { handleConnectionLost() }
            }
            currentMessageHandler = onMessage
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0
            return true
        }

        return connectPrimary(normalizedNewUrl, onMessage)
    }

    /**
     * Disconnect and remove a specific relay, whether it's in the pool or is the primary.
     */
    suspend fun disconnectRelay(url: String) {
        val normalized = url.normalizeRelayUrl()
        poolMutex.withLock {
            relayPool.remove(normalized)?.disconnect()
        }
        if (_currentRelayUrl.value == normalized) {
            disconnectPrimary()
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
        // The primary NIP-29 relay lives in primaryClient, not relayPool. Without
        // this check a caller asking for the primary's URL (e.g. the mux refresh
        // for a group hosted on the primary) misses the pool, becomes a connect
        // leader, and opens a SECOND socket to a relay we are already connected
        // to. getClientForRelay already short-circuits the primary the same way.
        if (_currentRelayUrl.value == normalized) {
            primaryClient?.let { return it }
        }
        // Fast-path: already pooled.
        poolMutex.withLock {
            relayPool[normalized]?.let { return it }
        }

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
            newClient.onConnectionLost = {
                scope.launch {
                    connStats?.onDisconnected(normalized)
                    poolMutex.withLock { relayPool.remove(normalized) }
                    onPoolRelayLost?.invoke(normalized)
                }
            }
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
     * Close the relay-scoped group-list subscription on every open socket
     * (primary + pool). Called on account switch so a late EOSE from the
     * previous account's REQ cannot poison the new account's full-fetch
     * bookkeeping after the sub ID is reused.
     */
    suspend fun closeGroupListSubscriptionsOnAllClients() {
        val clients =
            poolMutex.withLock { relayPool.values.toList() } +
                listOfNotNull(primaryClient)
        for (client in clients) {
            val subId = client.groupListSubscriptionId()
            try {
                client.send("""["CLOSE","$subId"]""")
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Clear all connections, disconnecting pool clients in parallel.
     */
    suspend fun clearAll() {
        scope.coroutineContext.cancelChildren()
        disconnectPrimary()

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

    /**
     * Get all connected relay URLs
     */
    suspend fun getConnectedRelays(): List<String> = poolMutex.withLock {
        relayPool.keys.toList()
    }
}
