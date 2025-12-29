package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage

/**
 * Manages relay connections and connection pooling.
 * Handles primary NIP-29 relay connection and auxiliary relay pool.
 */
class ConnectionManager(
    private val scope: CoroutineScope
) {
    private var primaryClient: NostrGroupClient? = null
    private var isConnecting = false

    private val _currentRelayUrl = MutableStateFlow("wss://groups.fiatjaf.com")
    val currentRelayUrl: StateFlow<String> = _currentRelayUrl.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Shared relay pool for all auxiliary connections (outbox, metadata, NIP-65)
    private val relayPool = mutableMapOf<String, NostrGroupClient>()

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
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

        isConnecting = true
        _connectionState.value = ConnectionState.Connecting

        return try {
            val newClient = NostrGroupClient(relayUrl)
            primaryClient = newClient

            newClient.connect { msg ->
                onMessage(msg, newClient)
            }

            newClient.waitForConnection()
            _connectionState.value = ConnectionState.Connected
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
     * Disconnect the primary relay
     */
    suspend fun disconnectPrimary() {
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
        relayPool[relayUrl]?.let { return it }

        return try {
            val newClient = NostrGroupClient(relayUrl)
            newClient.connect { msg ->
                onMessage(msg, newClient)
            }
            newClient.waitForConnection()
            relayPool[relayUrl] = newClient
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
            while (relayPool.isEmpty()) {
                delay(50)
            }
        }
    }

    /**
     * Check if any relay is connected in the pool
     */
    fun hasPoolConnections(): Boolean = relayPool.isNotEmpty()

    /**
     * Clear all connections
     */
    suspend fun clearAll() {
        scope.coroutineContext.cancelChildren()
        disconnectPrimary()
        relayPool.values.forEach { it.disconnect() }
        relayPool.clear()
    }

    /**
     * Get all connected relay URLs
     */
    fun getConnectedRelays(): List<String> = relayPool.keys.toList()
}
