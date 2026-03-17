package org.nostr.nostrord.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.storage.SecureStorage

/**
 * Manages authentication state and signing operations.
 * Handles both local keypair and NIP-46 bunker authentication.
 */
object AuthManager {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var keyPair: KeyPair? = null
    private var nip46Client: Nip46Client? = null
    private var isBunkerLogin = false
    private var bunkerUserPubkey: String? = null
    private var isNip07Login = false
    private var nip07UserPubkey: String? = null

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isBunkerConnected = MutableStateFlow(false)
    val isBunkerConnected: StateFlow<Boolean> = _isBunkerConnected.asStateFlow()

    private val _isBunkerVerifying = MutableStateFlow(false)
    val isBunkerVerifying: StateFlow<Boolean> = _isBunkerVerifying.asStateFlow()

    private val _authUrl = MutableStateFlow<String?>(null)
    val authUrl: StateFlow<String?> = _authUrl.asStateFlow()

    // Default relays for nostrconnect:// QR code flow
    val defaultNostrConnectRelays = listOf("wss://relay.damus.io", "wss://nos.lol")

    fun clearAuthUrl() {
        _authUrl.value = null
    }

    /**
     * Get the current user's public key (hex)
     */
    fun getPublicKey(): String? {
        return when {
            isBunkerLogin -> bunkerUserPubkey
            isNip07Login -> nip07UserPubkey
            keyPair != null -> keyPair?.publicKeyHex
            else -> null
        }
    }

    /**
     * Get the current user's private key (hex) - only for local login
     */
    fun getPrivateKey(): String? {
        return if (isBunkerLogin || isNip07Login) null else keyPair?.privateKeyHex
    }

    fun isUsingBunker(): Boolean = isBunkerLogin

    fun isBunkerReady(): Boolean = isBunkerLogin && nip46Client != null

    /**
     * Login with NIP-46 bunker URL
     * Returns the user's public key on success
     */
    suspend fun loginWithBunker(bunkerUrl: String): String {
        val bunkerInfo = parseBunkerUrl(bunkerUrl)

        // Check if we have an existing client key (from previous session)
        val existingClientKey = SecureStorage.getBunkerClientPrivateKey()
        val newNip46Client = if (existingClientKey != null) {
            Nip46Client(existingClientKey)
        } else {
            Nip46Client(null)
        }

        // Set up auth URL callback
        newNip46Client.onAuthUrl = { url ->
            _authUrl.value = url
        }

        try {
            newNip46Client.connect(
                remoteSignerPubkey = bunkerInfo.pubkey,
                relays = bunkerInfo.relays,
                secret = bunkerInfo.secret
            )
        } catch (e: Exception) {
            // "already connected" means the signer remembers us - success!
            if (e.message?.contains("already connected", ignoreCase = true) == true) {
            } else {
                throw e
            }
        }

        val userPubkey = newNip46Client.getPublicKey()

        nip46Client = newNip46Client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        keyPair = null

        // Save bunker credentials for session persistence
        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
        SecureStorage.clearPrivateKey()

        _isBunkerConnected.value = true
        _authUrl.value = null

        return userPubkey
    }

    /**
     * Login via nostrconnect:// QR code flow.
     * Connects to relays and starts listening BEFORE returning the URI,
     * so no events are missed when the signer scans the QR code.
     */
    suspend fun createNostrConnectSession(relays: List<String> = defaultNostrConnectRelays): Pair<String, Nip46Client> {
        val newNip46Client = Nip46Client(null)
        newNip46Client.onAuthUrl = { url -> _authUrl.value = url }
        // Connect to relays and subscribe FIRST
        newNip46Client.startListeningForConnection(relays, null)
        // Only then generate the URI for QR display
        val uri = newNip46Client.generateNostrConnectUri(relays)
        return uri to newNip46Client
    }

    /**
     * Complete the nostrconnect:// flow by waiting for the signer and fetching the public key.
     * The client must already be listening (via createNostrConnectSession).
     */
    suspend fun completeNostrConnectLogin(
        client: Nip46Client,
        relays: List<String> = defaultNostrConnectRelays
    ): String {
        val signerPubkey = client.awaitIncomingConnection()

        val userPubkey = client.getPublicKey()

        nip46Client = client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        keyPair = null

        // Build a bunker:// URL for session persistence
        val relayParams = relays.joinToString("&") { "relay=$it" }
        val bunkerUrl = "bunker://$signerPubkey?$relayParams"

        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(client.clientPrivateKey)
        SecureStorage.clearPrivateKey()

        _isBunkerConnected.value = true
        _authUrl.value = null

        return userPubkey
    }

    /**
     * Login via NIP-07 browser extension.
     * The public key has already been obtained from window.nostr.getPublicKey().
     */
    fun loginWithNip07(pubkey: String) {
        nip07UserPubkey = pubkey
        isNip07Login = true
        isBunkerLogin = false
        keyPair = null
        nip46Client = null

        SecureStorage.saveNip07UserPubkey(pubkey)
        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
    }

    /**
     * Login with local private key
     */
    fun loginWithPrivateKey(privateKeyHex: String, publicKeyHex: String) {
        keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        nip46Client = null

        SecureStorage.savePrivateKey(privateKeyHex)
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        SecureStorage.clearNip07UserPubkey()
    }

    /**
     * Restore session from saved credentials
     * Returns true if session was restored
     */
    suspend fun restoreSession(): Boolean {
        // Try NIP-07 first (if available and previously used)
        val savedNip07Pubkey = SecureStorage.getNip07UserPubkey()
        if (savedNip07Pubkey != null && Nip07.isAvailable()) {
            isNip07Login = true
            nip07UserPubkey = savedNip07Pubkey
            _isLoggedIn.value = true
            return true
        }

        // Try bunker
        val savedBunkerUrl = SecureStorage.getBunkerUrl()
        val savedUserPubkey = SecureStorage.getBunkerUserPubkey()

        if (savedBunkerUrl != null && savedUserPubkey != null) {
            return restoreBunkerSession(savedBunkerUrl, savedUserPubkey)
        }

        // Try private key
        val savedPrivateKey = SecureStorage.getPrivateKey()
        if (savedPrivateKey != null) {
            return restorePrivateKeySession(savedPrivateKey)
        }

        return false
    }

    private suspend fun restoreBunkerSession(bunkerUrl: String, savedUserPubkey: String): Boolean {
        try {
            val bunkerInfo = parseBunkerUrl(bunkerUrl)
            val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKey()

            val newNip46Client = if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }

            bunkerUserPubkey = savedUserPubkey
            isBunkerLogin = true

            newNip46Client.onAuthUrl = { url ->
                _authUrl.value = url
            }

            // Set logged-in and verifying states BEFORE the slow relay connection so the UI
            // immediately shows "Reconnecting to signer..." instead of a blank spinner.
            _isLoggedIn.value = true
            _isBunkerVerifying.value = true

            newNip46Client.connectRelaysOnly(bunkerInfo.pubkey, bunkerInfo.relays)
            newNip46Client.backgroundConnect(
                secret = bunkerInfo.secret,
                onSuccess = { _isBunkerVerifying.value = false },
                onRevoked = {
                    // Set isLoggedIn=false FIRST (keeps loading screen visible, prevents auth flash),
                    // then delay briefly to show "Logging out..." before releasing the loading screen.
                    handlePermissionDenied()
                    authScope.launch {
                        delay(1500)
                        _isBunkerVerifying.value = false
                    }
                }
            )

            nip46Client = newNip46Client
            _isBunkerConnected.value = true

            if (savedClientPrivateKey == null) {
                SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
            }

            return true

        } catch (e: Exception) {
            _isLoggedIn.value = false
            _isBunkerVerifying.value = false
            clearBunkerCredentials()
            return false
        }
    }

    private fun restorePrivateKeySession(privateKeyHex: String): Boolean {
        return try {
            keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
            isBunkerLogin = false
            _isLoggedIn.value = true
            true
        } catch (e: Exception) {
            SecureStorage.clearPrivateKey()
            false
        }
    }

    /**
     * Reconnect bunker if disconnected
     */
    suspend fun ensureBunkerConnected(): Boolean {
        if (!isBunkerLogin) return true
        if (nip46Client != null && _isBunkerConnected.value) return true

        return reconnectBunker()
    }

    private suspend fun reconnectBunker(): Boolean {
        val savedBunkerUrl = SecureStorage.getBunkerUrl() ?: return false
        val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKey()

        try {
            val bunkerInfo = parseBunkerUrl(savedBunkerUrl)

            val newNip46Client = if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }

            newNip46Client.onAuthUrl = { url ->
                _authUrl.value = url
            }

            try {
                newNip46Client.connect(
                    remoteSignerPubkey = bunkerInfo.pubkey,
                    relays = bunkerInfo.relays,
                    secret = bunkerInfo.secret
                )
            } catch (e: Exception) {
                if (e.message?.contains("already connected", ignoreCase = true) == true) {
                } else {
                    throw e
                }
            }

            nip46Client = newNip46Client
            _isBunkerConnected.value = true

            if (savedClientPrivateKey == null) {
                SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Sign an event using the active auth method (NIP-07, bunker, or local keypair)
     */
    suspend fun signEvent(event: Event): Event {
        return when {
            isNip07Login -> signWithNip07(event)
            isBunkerLogin -> signWithBunker(event)
            else -> signWithKeyPair(event)
        }
    }

    private suspend fun signWithNip07(event: Event): Event {
        val eventJson = event.toJsonString()
        val signedJson = Nip07.signEvent(eventJson)
        return parseSignedEvent(signedJson)
    }

    private suspend fun signWithBunker(event: Event): Event {
        if (nip46Client == null) {
            val reconnected = reconnectBunker()
            if (!reconnected) {
                throw Exception("Bunker not connected and reconnection failed")
            }
        }

        val bunker = nip46Client ?: throw Exception("Bunker not connected")

        try {
            val eventJson = event.toJsonString()
            val signedEventJson = bunker.signEvent(eventJson)
            return parseSignedEvent(signedEventJson)
        } catch (e: Exception) {
            if (isPermissionError(e)) {
                handlePermissionDenied()
                throw Exception("Signing permission denied. Please login again.")
            }
            throw e
        }
    }

    private fun signWithKeyPair(event: Event): Event {
        val kp = keyPair ?: throw Exception("Not logged in")
        return event.sign(kp)
    }

    private fun isPermissionError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return false
        return msg.contains("no permission") ||
               msg.contains("not authorized") ||
               msg.contains("permission denied")
    }

    private fun handlePermissionDenied() {
        nip46Client?.disconnect()
        nip46Client = null
        _isBunkerConnected.value = false
        clearBunkerCredentials()
        isBunkerLogin = false
        bunkerUserPubkey = null
        _isLoggedIn.value = false
    }

    private fun parseSignedEvent(jsonString: String): Event {
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(jsonString).jsonObject

        return Event(
            id = obj["id"]?.jsonPrimitive?.content,
            pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: "",
            createdAt = obj["created_at"]?.jsonPrimitive?.long ?: 0L,
            kind = obj["kind"]?.jsonPrimitive?.int ?: 0,
            tags = obj["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList(),
            content = obj["content"]?.jsonPrimitive?.content ?: "",
            sig = obj["sig"]?.jsonPrimitive?.content
        )
    }

    /**
     * Logout - clear all auth state
     */
    fun logout() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        keyPair = null

        _isLoggedIn.value = false
        _isBunkerConnected.value = false

        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearNip07UserPubkey()
        // Keep client key for re-login
    }

    /**
     * Completely forget bunker connection
     */
    fun forgetBunkerConnection() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        _isBunkerConnected.value = false
        clearBunkerCredentials()
        SecureStorage.clearBunkerClientPrivateKey()
    }

    private fun clearBunkerCredentials() {
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
    }

    /**
     * Set logged in state (called by repository after connections established)
     */
    fun setLoggedIn(value: Boolean) {
        _isLoggedIn.value = value
    }
}
