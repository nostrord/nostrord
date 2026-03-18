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
    private var isConnecting = false
    private var reconnectJob: Job? = null
    private var currentMessageHandler: ((String, NostrGroupClient) -> Unit)? = null

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
     * Connect to the primary NIP-29 relay
     */
    suspend fun connectPrimary(
        relayUrl: String = _currentRelayUrl.value,
        onMessage: (String, NostrGroupClient) -> Unit
    ): Boolean {
        if (primaryClient != null || isConnecting) {
            return false
        }

        // Store message handler for reconnection
        currentMessageHandler = onMessage
        isConnecting = true
        _connectionState.value = ConnectionState.Connecting

        return try {
            val newClient = NostrGroupClient(relayUrl)
            primaryClient = newClient

            // Set up connection lost callback for auto-reconnection
            newClient.onConnectionLost = {
                scope.launch {
                    handleConnectionLost()
                }
            }

            newClient.connect { msg ->
                onMessage(msg, newClient)
            }

            newClient.waitForConnection()
            _connectionState.value = ConnectionState.Connected
            reconnectAttempts = 0 // Reset on successful connection
            true
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            primaryClient = null
            false
        } finally {
            isConnecting = false
        }
    }

    /**
     * Handle connection lost - attempt auto-reconnection with exponential backoff
     */
    private suspend fun handleConnectionLost() {
        if (!autoReconnectEnabled) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        // Clean up old client
        primaryClient = null

        // Start reconnection attempts
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

                // Attempt to reconnect
                val handler = currentMessageHandler ?: break
                isConnecting = false // Reset so connectPrimary will work
                val success = connectPrimary(_currentRelayUrl.value, handler)

                if (success) {
                    reconnectAttempts = 0
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

        // Disconnect any stale client
        primaryClient?.disconnect()
        primaryClient = null
        isConnecting = false

        val handler = currentMessageHandler ?: return false
        return connectPrimary(_currentRelayUrl.value, handler)
    }

    /**
     * Disconnect the primary relay
     */
    suspend fun disconnectPrimary() {
        // Stop any ongoing reconnection attempts
        autoReconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null

        primaryClient?.disconnect()
        primaryClient = null
        _connectionState.value = ConnectionState.Disconnected
        isConnecting = false
    }

    /**
     * Switch to a new relay
     */
    suspend fun switchRelay(newRelayUrl: String, onMessage: (String, NostrGroupClient) -> Unit): Boolean {
        disconnectPrimary()
        _currentRelayUrl.value = newRelayUrl
        SecureStorage.saveCurrentRelayUrl(newRelayUrl)
        return connectPrimary(newRelayUrl, onMessage)
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
