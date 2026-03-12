package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages authentication lifecycle.
 * Handles login, logout, session restoration, and NIP-42 AUTH challenges.
 */
class SessionManager(
    private val authManager: AuthManager,
    private val scope: CoroutineScope
) {
    // Tracks relay URLs for which an AUTH challenge is already being processed,
    // to prevent double-signing when the relay sends a second challenge because
    // a REQ arrived before the first AUTH completed.
    private val authInProgress = mutableSetOf<String>()
    // Delegate auth state to AuthManager
    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn
    val isBunkerConnected: StateFlow<Boolean> = authManager.isBunkerConnected
    val authUrl: StateFlow<String?> = authManager.authUrl

    /**
     * Restore session from storage
     */
    suspend fun restoreSession(): Boolean {
        return authManager.restoreSession()
    }

    /**
     * Login with NIP-46 bunker URL
     */
    suspend fun loginWithBunker(bunkerUrl: String): String {
        return authManager.loginWithBunker(bunkerUrl)
    }

    /**
     * Login with private key
     */
    suspend fun loginWithPrivateKey(privKey: String, pubKey: String) {
        authManager.loginWithPrivateKey(privKey, pubKey)
    }

    /**
     * Login via NIP-07 browser extension
     */
    fun loginWithNip07(pubkey: String) {
        authManager.loginWithNip07(pubkey)
    }

    /**
     * Set logged in state
     */
    fun setLoggedIn(value: Boolean) {
        authManager.setLoggedIn(value)
    }

    /**
     * Logout
     */
    fun logout() {
        authManager.logout()
    }

    /**
     * Clear auth URL
     */
    fun clearAuthUrl() {
        authManager.clearAuthUrl()
    }

    /**
     * Create a nostrconnect:// session for QR code login
     */
    suspend fun createNostrConnectSession(relays: List<String> = authManager.defaultNostrConnectRelays): Pair<String, Nip46Client> {
        return authManager.createNostrConnectSession(relays)
    }

    /**
     * Complete the nostrconnect:// QR code login
     */
    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = authManager.defaultNostrConnectRelays
    ): String {
        return authManager.completeNostrConnectLogin(client, relays)
    }

    /**
     * Forget bunker connection
     */
    fun forgetBunkerConnection() {
        authManager.forgetBunkerConnection()
    }

    /**
     * Get public key
     */
    fun getPublicKey(): String? = authManager.getPublicKey()

    /**
     * Get private key
     */
    fun getPrivateKey(): String? = authManager.getPrivateKey()

    /**
     * Check if using bunker
     */
    fun isUsingBunker(): Boolean = authManager.isUsingBunker()

    /**
     * Check if bunker is ready
     */
    fun isBunkerReady(): Boolean = authManager.isBunkerReady()

    /**
     * Ensure bunker is connected
     */
    suspend fun ensureBunkerConnected(): Boolean {
        return authManager.ensureBunkerConnected()
    }

    /**
     * Sign an event
     */
    suspend fun signEvent(event: Event): Event {
        return authManager.signEvent(event)
    }

    /**
     * Handle NIP-42 AUTH challenge from relay.
     * Deduplicates: if we're already processing a challenge for this relay, the new one is ignored.
     */
    suspend fun handleAuthChallenge(client: NostrGroupClient, challenge: String) {
        val relayUrl = client.getRelayUrl()
        if (!authInProgress.add(relayUrl)) return  // already in progress for this relay

        val pubKey = getPublicKey() ?: run { authInProgress.remove(relayUrl); return }

        try {
            val authEvent = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 22242,
                tags = listOf(
                    listOf("relay", relayUrl),
                    listOf("challenge", challenge)
                ),
                content = ""
            )

            val signedEvent = signEvent(authEvent)

            val message = buildJsonArray {
                add("AUTH")
                add(signedEvent.toJsonObject())
            }.toString()

            client.send(message)

            // Re-request groups after authentication
            kotlinx.coroutines.delay(500)
            client.requestGroups()
        } catch (_: Exception) {
        } finally {
            authInProgress.remove(relayUrl)
        }
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

}
