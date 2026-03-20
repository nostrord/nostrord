package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage

/**
 * Manages relay connections and connection pooling.
 * Handles primary NIP-29 relay connection and auxiliary relay pool.
 * Includes automatic reconnection with exponential backoff.
 */
class ConnectionManager(
    private val scope: CoroutineScope
) {
    private var primaryClient: NostrGroupClient? = null
    private var reconnectJob: Job? = null
    private var currentMessageHandler: ((String, NostrGroupClient) -> Unit)? = null

    // Serialises connectPrimary so two coroutines cannot both pass the "already connecting"
    // guard simultaneously (Dispatchers.Default is multi-threaded).
    private val connectMutex = Mutex()

    private val _currentRelayUrl = MutableStateFlow("wss://groups.fiatjaf.com")
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
     * Called when the primary connection drops unexpectedly.
     * Set by NostrRepository to also notify GroupManager.
     */
    var onConnectionDropped: (() -> Unit)? = null

    /**
     * Called after a successful auto-reconnect with the new client.
     * Set by NostrRepository to re-auth and re-subscribe.
     */
    var onReconnected: (suspend (NostrGroupClient) -> Unit)? = null

    companion object {
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
        data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState()
    }

    /**
     * Load saved relay URL from storage
     */
    suspend fun loadSavedRelay() {
        val savedRelayUrl = SecureStorage.getCurrentRelayUrl()
        if (savedRelayUrl != null) {
            _currentRelayUrl.value = savedRelayUrl
        }
    }

    /**
     * Get the primary NIP-29 client
     */
    fun getPrimaryClient(): NostrGroupClient? = primaryClient

    /**
     * Returns the client for a specific relay URL.
     * Checks the primary first, then the pool.
     * Does not create new connections — returns null if relay is not connected.
     */
    suspend fun getClientForRelay(relayUrl: String): NostrGroupClient? {
        if (_currentRelayUrl.value == relayUrl) return primaryClient
        return poolMutex.withLock { relayPool[relayUrl] }
    }

    /**
     * Connect to the primary NIP-29 relay.
     * Serialised by [connectMutex] — concurrent callers block until the in-flight
     * attempt finishes, then return false immediately if a client is already up.
     */
    suspend fun connectPrimary(
        relayUrl: String = _currentRelayUrl.value,
        onMessage: (String, NostrGroupClient) -> Unit
    ): Boolean = connectMutex.withLock {
        if (primaryClient != null) return@withLock false

        currentMessageHandler = onMessage
        _connectionState.value = ConnectionState.Connecting

        try {
            val newClient = NostrGroupClient(relayUrl)
            primaryClient = newClient

            newClient.onConnectionLost = {
                scope.launch { handleConnectionLost() }
            }

            newClient.connect { msg -> onMessage(msg, newClient) }
            newClient.waitForConnection()
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0
            true
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            primaryClient = null
            false
        }
    }

    /**
     * Handle connection lost - notify observers then attempt auto-reconnection.
     */
    private suspend fun handleConnectionLost() {
        onConnectionDropped?.invoke()

        if (!autoReconnectEnabled) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        primaryClient = null
        startReconnection()
    }

    /**
     * Start reconnection with exponential backoff
     */
    private fun startReconnection() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && autoReconnectEnabled) {
                reconnectAttempts++
                _connectionState.value = ConnectionState.Reconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)

                // Calculate delay with exponential backoff
                val delayMs = minOf(
                    INITIAL_RECONNECT_DELAY_MS * (1L shl (reconnectAttempts - 1)),
                    MAX_RECONNECT_DELAY_MS
                )
                delay(delayMs)

                val handler = currentMessageHandler ?: break
                val success = connectPrimary(_currentRelayUrl.value, handler)

                if (success) {
                    reconnectAttempts = 0
                    primaryClient?.let { client -> scope.launch { onReconnected?.invoke(client) } }
                    return@launch
                }
            }

            // Max attempts reached
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                _connectionState.value = ConnectionState.Error("Connection lost. Tap to retry.")
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

        primaryClient?.disconnect()
        primaryClient = null

        val handler = currentMessageHandler ?: return false
        return connectPrimary(_currentRelayUrl.value, handler)
    }

    /**
     * Disconnect the primary relay
     */
    suspend fun disconnectPrimary() {
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null

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
    suspend fun switchRelay(newRelayUrl: String, onMessage: (String, NostrGroupClient) -> Unit): Boolean {
        // Move the current primary into the pool instead of disconnecting it.
        val oldPrimary = primaryClient
        val oldUrl = _currentRelayUrl.value
        if (oldPrimary != null && oldUrl != newRelayUrl) {
            poolMutex.withLock {
                if (!relayPool.containsKey(oldUrl)) {
                    relayPool[oldUrl] = oldPrimary
                }
            }
        }

        // If the new relay is already in the pool, promote it to primary.
        val existingPoolClient = poolMutex.withLock { relayPool.remove(newRelayUrl) }

        // Clear the primary slot (without disconnecting the old client).
        primaryClient = null
        autoReconnectEnabled = true
        reconnectJob?.cancel()
        reconnectJob = null

        _currentRelayUrl.value = newRelayUrl
        SecureStorage.saveCurrentRelayUrl(newRelayUrl)

        if (existingPoolClient != null) {
            // Re-use the already-connected pool client as the new primary.
            primaryClient = existingPoolClient
            existingPoolClient.onConnectionLost = {
                scope.launch { handleConnectionLost() }
            }
            currentMessageHandler = onMessage
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0
            return true
        }

        return connectPrimary(newRelayUrl, onMessage)
    }

    /**
     * Disconnect and remove a specific relay, whether it's in the pool or is the primary.
     */
    suspend fun disconnectRelay(url: String) {
        poolMutex.withLock {
            relayPool.remove(url)?.disconnect()
        }
        if (_currentRelayUrl.value == url) {
            disconnectPrimary()
        }
    }

    /**
     * Get or create a connection to a relay in the pool
     */
    suspend fun getOrConnectRelay(
        relayUrl: String,
        onMessage: (String, NostrGroupClient) -> Unit
    ): NostrGroupClient? {
        // Check if we already have a connection
        poolMutex.withLock {
            relayPool[relayUrl]?.let { return it }
        }

        // Create new connection outside the lock to avoid blocking other operations
        return try {
            val newClient = NostrGroupClient(relayUrl)
            newClient.connect { msg ->
                onMessage(msg, newClient)
            }
            val connected = newClient.waitForConnection()
            if (!connected) {
                newClient.disconnect()
                return null
            }

            // Add to pool with lock, but check again in case another coroutine added it
            poolMutex.withLock {
                relayPool[relayUrl]?.let {
                    // Another coroutine already added a connection, disconnect ours
                    newClient.disconnect()
                    return it
                }
                relayPool[relayUrl] = newClient
            }
            newClient
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Send a message to multiple relays in the pool
     */
    fun sendToRelays(relayUrls: List<String>, message: String, onMessage: (String, NostrGroupClient) -> Unit) {
        relayUrls.forEach { relayUrl ->
            scope.launch {
                try {
                    val client = getOrConnectRelay(relayUrl, onMessage)
                    client?.send(message)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Connect to multiple relays in parallel
     */
    suspend fun connectToRelaysParallel(
        relayUrls: List<String>,
        onMessage: (String, NostrGroupClient) -> Unit,
        timeoutMs: Long = 2000
    ) {
        relayUrls.map { relayUrl ->
            scope.launch {
                try {
                    getOrConnectRelay(relayUrl, onMessage)
                } catch (_: Exception) {}
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
     * Clear all connections
     */
    suspend fun clearAll() {
        scope.coroutineContext.cancelChildren()
        disconnectPrimary()

        // Get clients to disconnect while holding lock, then disconnect outside lock
        val clientsToDisconnect = poolMutex.withLock {
            val clients = relayPool.values.toList()
            relayPool.clear()
            clients
        }
        clientsToDisconnect.forEach { it.disconnect() }
    }

    /**
     * Get all connected relay URLs
     */
    suspend fun getConnectedRelays(): List<String> = poolMutex.withLock {
        relayPool.keys.toList()
    }
}
