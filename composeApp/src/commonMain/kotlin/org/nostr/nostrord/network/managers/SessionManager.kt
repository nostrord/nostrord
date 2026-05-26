package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.AuthManager
import org.nostr.nostrord.network.BunkerState
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
    private val scope: CoroutineScope,
) {
    // Tracks relay URLs for which an AUTH challenge is already being processed,
    // to prevent double-signing when the relay sends a second challenge because
    // a REQ arrived before the first AUTH completed.
    private val authInProgress = mutableSetOf<String>()

    // Delegate auth state to AuthManager
    val isLoggedIn: StateFlow<Boolean> = authManager.isLoggedIn
    val isBunkerConnected: StateFlow<Boolean> = authManager.isBunkerConnected
    val isBunkerVerifying: StateFlow<Boolean> = authManager.isBunkerVerifying
    val bunkerState: StateFlow<BunkerState> = authManager.bunkerState
    val authUrl: StateFlow<String?> = authManager.authUrl

    /**
     * Restore session from storage
     */
    suspend fun restoreSession(): Boolean = authManager.restoreSession()

    /**
     * Login with NIP-46 bunker URL
     */
    suspend fun loginWithBunker(bunkerUrl: String): String = authManager.loginWithBunker(bunkerUrl)

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
        // Clear AUTH dedup so a logout→re-login on the same process doesn't
        // skip the first AUTH for a relay whose entry was left behind by a
        // coroutine cancelled mid-flight (scope.cancelChildren in
        // NostrRepository.logout). A stale entry would make handleAuthChallenge
        // return false, suppressing the resubscribeAfterAuth that normally
        // re-requests group messages.
        authInProgress.clear()
        authManager.logout()
    }

    /**
     * Clear auth URL
     */
    fun clearAuthUrl() {
        authManager.clearAuthUrl()
    }

    /** Default relays seeding the nostrconnect:// QR login (user-overridable). */
    val defaultNostrConnectRelays: List<String> = authManager.defaultNostrConnectRelays

    /**
     * Create a nostrconnect:// session for QR code login
     */
    suspend fun createNostrConnectSession(relays: List<String> = authManager.defaultNostrConnectRelays): Pair<String, Nip46Client> = authManager.createNostrConnectSession(relays)

    /**
     * Complete the nostrconnect:// QR code login
     */
    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = authManager.defaultNostrConnectRelays,
    ): String = authManager.completeNostrConnectLogin(client, relays)

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
    suspend fun ensureBunkerConnected(): Boolean = authManager.ensureBunkerConnected()

    /**
     * Sign an event
     */
    suspend fun signEvent(
        event: Event,
        interactive: Boolean = true,
    ): Event = authManager.signEvent(event, interactive)

    /**
     * Handle NIP-42 AUTH challenge from relay.
     * Deduplicates: if we're already processing a challenge for this relay, the new one is ignored.
     */
    /**
     * Sign the relay's NIP-42 challenge and send the response.
     *
     * Returns true when this call actually drove the AUTH cycle (signed and
     * sent), false when it was deduped (another coroutine is already handling
     * AUTH for this relay) or the client disconnected mid-way. The caller uses
     * the return value to decide whether to fire the post-AUTH side effects
     * (notifyAuthCompleted, resubscribeAfterAuth): chatty/broken relays that
     * re-send AUTH frames in tight loops would otherwise multiply the
     * resubscribe storm across every duplicate frame.
     */
    suspend fun handleAuthChallenge(client: NostrGroupClient, challenge: String): Boolean {
        val relayUrl = client.getRelayUrl()
        if (!client.isConnected()) return false // race-condition loser: already disconnected
        if (!authInProgress.add(relayUrl)) return false // already in progress for this relay

        val pubKey = getPublicKey() ?: run {
            authInProgress.remove(relayUrl)
            return false
        }

        return try {
            val authEvent = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 22242,
                tags = listOf(
                    listOf("relay", relayUrl),
                    listOf("challenge", challenge),
                ),
                content = "",
            )

            // interactive=false: a failed background AUTH sign must not flip the
            // bunker banner to "can't reach your signer". (banner-flicker fix)
            val signedEvent = signEvent(authEvent, interactive = false)

            val message = buildJsonArray {
                add("AUTH")
                add(signedEvent.toJsonObject())
            }.toString()

            client.send(message)

            // Give the relay 500 ms to process the AUTH before we send subscriptions.
            // requestGroups() is handled by the caller (resubscribeAfterAuth) so it only
            // fires when this client is the primary relay.
            kotlinx.coroutines.delay(500)
            true
        } catch (_: Throwable) {
            false
        } finally {
            authInProgress.remove(relayUrl)
        }
    }
}
