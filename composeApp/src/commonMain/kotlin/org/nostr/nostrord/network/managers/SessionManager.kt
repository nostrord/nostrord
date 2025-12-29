package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages authentication lifecycle.
 * Handles login, logout, session restoration, and NIP-42 AUTH challenges.
 */
class SessionManager(
    private val connectionManager: ConnectionManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Delegate auth state to AuthManager
    val isLoggedIn: StateFlow<Boolean> = AuthManager.isLoggedIn
    val isBunkerConnected: StateFlow<Boolean> = AuthManager.isBunkerConnected
    val authUrl: StateFlow<String?> = AuthManager.authUrl

    /**
     * Restore session from storage
     */
    suspend fun restoreSession(): Boolean {
        return AuthManager.restoreSession()
    }

    /**
     * Login with NIP-46 bunker URL
     */
    suspend fun loginWithBunker(bunkerUrl: String): String {
        return AuthManager.loginWithBunker(bunkerUrl)
    }

    /**
     * Login with private key
     */
    suspend fun loginWithPrivateKey(privKey: String, pubKey: String) {
        AuthManager.loginWithPrivateKey(privKey, pubKey)
    }

    /**
     * Set logged in state
     */
    fun setLoggedIn(value: Boolean) {
        AuthManager.setLoggedIn(value)
    }

    /**
     * Logout
     */
    fun logout() {
        AuthManager.logout()
    }

    /**
     * Clear auth URL
     */
    fun clearAuthUrl() {
        AuthManager.clearAuthUrl()
    }

    /**
     * Forget bunker connection
     */
    fun forgetBunkerConnection() {
        AuthManager.forgetBunkerConnection()
    }

    /**
     * Get public key
     */
    fun getPublicKey(): String? = AuthManager.getPublicKey()

    /**
     * Get private key
     */
    fun getPrivateKey(): String? = AuthManager.getPrivateKey()

    /**
     * Check if using bunker
     */
    fun isUsingBunker(): Boolean = AuthManager.isUsingBunker()

    /**
     * Check if bunker is ready
     */
    fun isBunkerReady(): Boolean = AuthManager.isBunkerReady()

    /**
     * Ensure bunker is connected
     */
    suspend fun ensureBunkerConnected(): Boolean {
        return AuthManager.ensureBunkerConnected()
    }

    /**
     * Sign an event
     */
    suspend fun signEvent(event: Event): Event {
        return AuthManager.signEvent(event)
    }

    /**
     * Handle NIP-42 AUTH challenge from relay
     */
    suspend fun handleAuthChallenge(client: NostrGroupClient, challenge: String) {
        val pubKey = getPublicKey() ?: return

        try {
            // Create AUTH event (kind 22242)
            val authEvent = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 22242,
                tags = listOf(
                    listOf("relay", client.getRelayUrl()),
                    listOf("challenge", challenge)
                ),
                content = ""
            )

            val signedEvent = signEvent(authEvent)

            // Send AUTH response
            val message = buildJsonArray {
                add("AUTH")
                add(signedEvent.toJsonObject())
            }.toString()

            client.send(message)

            // Re-request groups after authentication
            kotlinx.coroutines.delay(500)
            client.requestGroups()
        } catch (_: Exception) {}
    }

    /**
     * Send AUTH to relay if using local keypair
     */
    suspend fun sendAuthIfNeeded(client: NostrGroupClient) {
        if (!isUsingBunker()) {
            getPrivateKey()?.let { privateKey ->
                client.sendAuth(privateKey)
            }
        }
    }

    /**
     * Parse a signed event JSON string
     */
    fun parseSignedEvent(jsonString: String): Event {
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(jsonString).jsonObject

        return Event(
            id = obj["id"]?.jsonPrimitive?.content,
            pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
            createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
            kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
            tags = obj["tags"]?.jsonArray?.map { tagArray ->
                tagArray.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList(),
            content = obj["content"]?.jsonPrimitive?.content ?: "",
            sig = obj["sig"]?.jsonPrimitive?.content
        )
    }
}
