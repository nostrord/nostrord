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
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AccountStore
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.nostr.Nip46Client
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearAllCredentialsForAccount
import org.nostr.nostrord.storage.getBunkerClientPrivateKeyFor
import org.nostr.nostrord.storage.getBunkerUrlFor
import org.nostr.nostrord.storage.getPrivateKeyFor
import org.nostr.nostrord.storage.saveBunkerClientPrivateKeyFor
import org.nostr.nostrord.storage.saveBunkerUrlFor
import org.nostr.nostrord.storage.savePrivateKeyFor
import org.nostr.nostrord.utils.epochMillis

/**
 * Manages authentication state and signing operations.
 *
 * One instance is alive at a time, holding state for the currently active
 * account. Switching accounts re-loads credentials in place rather than
 * creating new instances, so dependents (SessionManager, UI) can keep their
 * StateFlow references stable across switches.
 */
class AuthManager(
    private val accountStore: AccountStore,
) {

    private val authScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var keyPair: KeyPair? = null
    private var nip46Client: Nip46Client? = null
    private var isBunkerLogin = false
    private var bunkerUserPubkey: String? = null
    private var isNip07Login = false
    private var nip07UserPubkey: String? = null

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /**
     * Invoked when the active session is invalidated involuntarily (bunker
     * permission revoked, etc). Receives the pubkey that just lost its
     * session so the host can try to switch to another signed-in account
     * before falling back to a logged-out state. If unset, [handlePermissionDenied]
     * sets [_isLoggedIn] to false directly.
     */
    internal var onSessionInvalidated: (suspend (invalidatedPubkey: String?) -> Unit)? = null

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
     * Get the current user's public key (hex).
     *
     * Prefers [ActiveAccountManager]'s session so the value is always in sync
     * with the active session after a switch. Falls back to the inline fields
     * for compatibility during the login flow before the session is activated.
     */
    fun getPublicKey(): String? {
        return ActiveAccountManager.currentPubkey ?: when {
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
     * Live local key pair for [AccountSessionFactory]. Null for non-LOCAL
     * accounts. Shared instance: disposing the session's signer zeroes this
     * manager's bytes too (desired on switch).
     */
    internal fun activeKeyPair(): org.nostr.nostrord.nostr.KeyPair? = keyPair

    /**
     * Live NIP-46 client for [AccountSessionFactory]. Null for non-BUNKER
     * accounts. Shared instance: disposing the session's signer disconnects
     * this manager's client too (desired on switch).
     */
    internal fun activeNip46Client(): Nip46Client? = nip46Client

    private fun zeroAndClearKeyPair() {
        keyPair?.privateKey?.fill(0)
        keyPair = null
    }

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
        rejectIfBunkerSwap(userPubkey, bunkerUrl)

        nip46Client = newNip46Client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        zeroAndClearKeyPair()

        // Save bunker credentials for session persistence
        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(newNip46Client.clientPrivateKey)
        SecureStorage.saveBunkerUrlFor(userPubkey, bunkerUrl)
        SecureStorage.saveBunkerClientPrivateKeyFor(userPubkey, newNip46Client.clientPrivateKey)
        SecureStorage.clearPrivateKey()
        registerAccountAfterLogin(userPubkey, AuthMethod.BUNKER)

        _isBunkerConnected.value = true
        _authUrl.value = null

        return userPubkey
    }

    /**
     * Defense-in-depth against a signer claiming a pubkey we already track
     * under a different bunker URL. The pubkey-keyed credential slots
     * (SecureStorage.*For(pubkey)) trust this returned pubkey, so a swap here
     * would silently re-point an existing account at a foreign signer.
     */
    private fun rejectIfBunkerSwap(returnedPubkey: String, bunkerUrl: String) {
        val existing = accountStore.get(returnedPubkey) ?: return
        if (existing.authMethod != AuthMethod.BUNKER) {
            throw IllegalStateException(
                "Account $returnedPubkey is already registered with a different sign-in method."
            )
        }
        val savedUrl = SecureStorage.getBunkerUrlFor(returnedPubkey)
        if (!savedUrl.isNullOrBlank() && savedUrl != bunkerUrl) {
            throw IllegalStateException(
                "Account $returnedPubkey already exists with a different bunker. " +
                "Remove the existing account before reconnecting."
            )
        }
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

        // Build a bunker:// URL for session persistence (also used by the
        // swap-guard below to compare against any saved URL for this pubkey).
        val relayParams = relays.joinToString("&") { "relay=$it" }
        val bunkerUrl = "bunker://$signerPubkey?$relayParams"
        rejectIfBunkerSwap(userPubkey, bunkerUrl)

        nip46Client = client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        zeroAndClearKeyPair()

        SecureStorage.saveBunkerUrl(bunkerUrl)
        SecureStorage.saveBunkerUserPubkey(userPubkey)
        SecureStorage.saveBunkerClientPrivateKey(client.clientPrivateKey)
        SecureStorage.saveBunkerUrlFor(userPubkey, bunkerUrl)
        SecureStorage.saveBunkerClientPrivateKeyFor(userPubkey, client.clientPrivateKey)
        SecureStorage.clearPrivateKey()
        registerAccountAfterLogin(userPubkey, AuthMethod.BUNKER)

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
        zeroAndClearKeyPair()
        nip46Client = null

        SecureStorage.saveNip07UserPubkey(pubkey)
        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        registerAccountAfterLogin(pubkey, AuthMethod.NIP07)
    }

    /**
     * Login with local private key
     */
    fun loginWithPrivateKey(privateKeyHex: String, publicKeyHex: String) {
        zeroAndClearKeyPair()
        keyPair = KeyPair.fromPrivateKeyHex(privateKeyHex)
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        nip46Client = null

        SecureStorage.savePrivateKey(privateKeyHex)
        SecureStorage.savePrivateKeyFor(publicKeyHex, privateKeyHex)
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearBunkerClientPrivateKey()
        SecureStorage.clearNip07UserPubkey()
        registerAccountAfterLogin(publicKeyHex, AuthMethod.LOCAL)
    }

    /**
     * Insert or update the Account record for [pubkey] and set it as active.
     * Called by every login method after credentials are persisted.
     */
    private fun registerAccountAfterLogin(pubkey: String, method: AuthMethod) {
        if (pubkey.isBlank()) return
        val existing = accountStore.get(pubkey)
        val account = existing?.copy(authMethod = method) ?: Account(
            pubkey = pubkey,
            label = "Account ${accountStore.accounts.value.size + 1}",
            authMethod = method,
            addedAt = epochMillis(),
        )
        accountStore.upsert(account)
        accountStore.setActive(pubkey)
    }

    /**
     * Restore session from saved credentials.
     *
     * Prefers the AccountStore: if there's an active account, load credentials
     * from its pubkey-scoped slot. Falls back to legacy single-slot lookup so
     * an in-flight upgrade (Account record present but credentials still only
     * in legacy slot) doesn't lock the user out.
     */
    suspend fun restoreSession(): Boolean {
        val active = accountStore.active
        if (active != null && useAccount(active)) return true

        // Legacy fallback path. Order matches the original precedence
        // (NIP-07 > bunker > local key).
        val savedNip07Pubkey = SecureStorage.getNip07UserPubkey()
        if (savedNip07Pubkey != null && Nip07.isAvailable()) {
            isNip07Login = true
            nip07UserPubkey = savedNip07Pubkey
            _isLoggedIn.value = true
            return true
        }

        val savedBunkerUrl = SecureStorage.getBunkerUrl()
        val savedUserPubkey = SecureStorage.getBunkerUserPubkey()
        if (savedBunkerUrl != null && savedUserPubkey != null) {
            return restoreBunkerSession(savedBunkerUrl, savedUserPubkey)
        }

        val savedPrivateKey = SecureStorage.getPrivateKey()
        if (savedPrivateKey != null) {
            return restorePrivateKeySession(savedPrivateKey)
        }

        return false
    }

    /**
     * Load credentials for [account] into this AuthManager instance and emit
     * the resulting auth-state flows. Used by both restoreSession() and
     * AccountManager.switchAccount.
     *
     * Validates the new credentials BEFORE tearing down the current session,
     * so a missing/invalid slot does not leave the app in a half-logged-in
     * state. On success, [accountStore] is set to active.
     */
    suspend fun useAccount(account: Account): Boolean {
        // Build new credentials before touching the current session — a
        // BUNKER connect failure here leaves the existing session intact.
        val prepared: PreparedAccount = when (account.authMethod) {
            AuthMethod.LOCAL -> {
                val priv = SecureStorage.getPrivateKeyFor(account.pubkey)
                    ?: SecureStorage.getPrivateKey()
                    ?: return false
                val kp = try {
                    KeyPair.fromPrivateKeyHex(priv)
                } catch (_: Exception) {
                    return false
                }
                PreparedAccount.Local(kp)
            }
            AuthMethod.BUNKER -> {
                val bunkerUrl = SecureStorage.getBunkerUrlFor(account.pubkey)
                    ?: SecureStorage.getBunkerUrl()
                    ?: return false
                val client = buildBunkerClient(bunkerUrl, account.pubkey)
                    ?: return false  // connect failed — keep current session
                PreparedAccount.Bunker(bunkerUrl, client)
            }
            AuthMethod.NIP07 -> {
                if (!Nip07.isAvailable()) return false
                PreparedAccount.Nip07
            }
            AuthMethod.READ_ONLY, AuthMethod.GUEST -> {
                // Signing is fully delegated to the AccountSession's NostrSigner.
                // No inline credential state needed in AuthManager.
                PreparedAccount.NoInlineCredentials
            }
        }

        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        zeroAndClearKeyPair()
        _isBunkerConnected.value = false

        when (prepared) {
            is PreparedAccount.Local -> {
                keyPair = prepared.keyPair
                _isLoggedIn.value = true
            }
            is PreparedAccount.Bunker -> {
                installBunkerClient(prepared.client, prepared.bunkerUrl, account.pubkey)
            }
            PreparedAccount.Nip07 -> {
                isNip07Login = true
                nip07UserPubkey = account.pubkey
                _isLoggedIn.value = true
            }
            PreparedAccount.NoInlineCredentials -> {
                _isLoggedIn.value = true
            }
        }

        accountStore.setActive(account.pubkey)
        return true
    }

    /**
     * Build a [Nip46Client] object for [bunkerUrl]. Only validates that the
     * URL parses; no network I/O happens here.
     *
     * Returns null only when the bunker URL is malformed — i.e. a true
     * credential problem. Transient network failures during relay connect are
     * handled later in [installBunkerClient] (async), so a flaky relay does
     * not block the account switch.
     */
    private fun buildBunkerClient(bunkerUrl: String, userPubkey: String): Nip46Client? {
        return try {
            parseBunkerUrl(bunkerUrl)  // validate URL
            val savedClientPrivateKey =
                SecureStorage.getBunkerClientPrivateKeyFor(userPubkey)
                    ?: SecureStorage.getBunkerClientPrivateKey()
            val client = if (savedClientPrivateKey != null) {
                Nip46Client(savedClientPrivateKey)
            } else {
                Nip46Client()
            }
            client.onAuthUrl = { url -> _authUrl.value = url }
            client
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Install a [client] that was already prepared by [buildBunkerClient],
     * and start the asynchronous relay connect + NIP-46 connect RPC in
     * [authScope].
     *
     * Failures during the async connect mark the bunker as disconnected
     * but do NOT log the user out — they remain on this account and the
     * UI can offer a retry. Explicit signer rejection still routes through
     * the [onRevoked] callback (which calls [handlePermissionDenied]).
     *
     * Identity-guarded callbacks: every async callback checks
     * `nip46Client === client` so stale events from a swapped-out client
     * cannot affect the current account.
     */
    private fun installBunkerClient(client: Nip46Client, bunkerUrl: String, userPubkey: String) {
        nip46Client = client
        bunkerUserPubkey = userPubkey
        isBunkerLogin = true
        _isLoggedIn.value = true
        // Optimistic: assume reachable. The async block below flips this to
        // false if the WebSocket open fails so the UI can show "Disconnected".
        _isBunkerConnected.value = true
        _isBunkerVerifying.value = true

        val savedClientPrivateKey = SecureStorage.getBunkerClientPrivateKeyFor(userPubkey)
            ?: SecureStorage.getBunkerClientPrivateKey()

        authScope.launch {
            val info = try {
                parseBunkerUrl(bunkerUrl)
            } catch (_: Exception) {
                if (nip46Client === client) {
                    _isBunkerVerifying.value = false
                    _isBunkerConnected.value = false
                }
                return@launch
            }
            try {
                client.connectRelaysOnly(info.pubkey, info.relays)
            } catch (_: Exception) {
                // Relay unreachable — keep the user on this account, just
                // surface the disconnected state. They can retry later;
                // backgroundConnect below would just time out without sockets.
                if (nip46Client === client) {
                    _isBunkerConnected.value = false
                    _isBunkerVerifying.value = false
                }
                return@launch
            }
            client.backgroundConnect(
                secret = info.secret,
                onSuccess = {
                    if (nip46Client !== client) return@backgroundConnect
                    // Confirm the signer actually controls the pubkey this account
                    // claims. Without this, a malicious bunker (or a slot whose
                    // contents leaked across accounts) could sign under a key the
                    // user does not own. See SecureStorage credential slot fix.
                    authScope.launch {
                        val signerPubkey = try {
                            client.getPublicKey()
                        } catch (_: Exception) {
                            if (nip46Client === client) _isBunkerVerifying.value = false
                            return@launch
                        }
                        if (nip46Client !== client) return@launch
                        if (!signerPubkey.equals(userPubkey, ignoreCase = true)) {
                            // Signer identity does not match — refuse the session.
                            handlePermissionDenied()
                            delay(1500)
                            _isBunkerVerifying.value = false
                            return@launch
                        }
                        _isBunkerVerifying.value = false
                    }
                },
                onRevoked = {
                    if (nip46Client !== client) return@backgroundConnect
                    handlePermissionDenied()
                    authScope.launch {
                        delay(1500)
                        _isBunkerVerifying.value = false
                    }
                }
            )
        }

        if (savedClientPrivateKey == null) {
            SecureStorage.saveBunkerClientPrivateKey(client.clientPrivateKey)
            SecureStorage.saveBunkerClientPrivateKeyFor(userPubkey, client.clientPrivateKey)
        }
    }

    private sealed class PreparedAccount {
        data class Local(val keyPair: KeyPair) : PreparedAccount()
        data class Bunker(val bunkerUrl: String, val client: Nip46Client) : PreparedAccount()
        object Nip07 : PreparedAccount()
        /** READ_ONLY or GUEST — signing is delegated entirely to ActiveAccountManager. */
        object NoInlineCredentials : PreparedAccount()
    }

    private suspend fun restoreBunkerSession(bunkerUrl: String, savedUserPubkey: String): Boolean {
        try {
            val bunkerInfo = parseBunkerUrl(bunkerUrl)
            val savedClientPrivateKey =
                SecureStorage.getBunkerClientPrivateKeyFor(savedUserPubkey)
                    ?: SecureStorage.getBunkerClientPrivateKey()

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
                SecureStorage.saveBunkerClientPrivateKeyFor(
                    savedUserPubkey,
                    newNip46Client.clientPrivateKey,
                )
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
        val activePubkey = bunkerUserPubkey
        val savedBunkerUrl =
            activePubkey?.let { SecureStorage.getBunkerUrlFor(it) }
                ?: SecureStorage.getBunkerUrl()
                ?: return false
        val savedClientPrivateKey =
            activePubkey?.let { SecureStorage.getBunkerClientPrivateKeyFor(it) }
                ?: SecureStorage.getBunkerClientPrivateKey()

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
                activePubkey?.let {
                    SecureStorage.saveBunkerClientPrivateKeyFor(
                        it,
                        newNip46Client.clientPrivateKey,
                    )
                }
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Sign an event using the active account's isolated [NostrSigner].
     *
     * Routes through [ActiveAccountManager] first so the correct signer is
     * always used regardless of which account is active. Falls back to the
     * inline auth state for the initial login flow, before the session is
     * activated.
     */
    suspend fun signEvent(event: Event): Event {
        val sessionSigner = ActiveAccountManager.session.value?.signer
        if (sessionSigner != null) {
            return try {
                sessionSigner.signEvent(event)
            } catch (e: NostrSigner.SigningException) {
                if (isPermissionError(e)) {
                    handlePermissionDenied()
                    throw Exception("Signing permission denied. Please login again.")
                }
                throw e
            }
        }
        // Fallback: session not yet activated (e.g. initial login in progress).
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
        // Fast-fail when the bunker is known disconnected. Otherwise every
        // NIP-42 AUTH challenge from a NIP-29 relay piles up a 120s sign
        // attempt: N relays = N concurrent hung coroutines doing crypto on
        // the JS main thread, which freezes the browser tab.
        if (!_isBunkerConnected.value) {
            throw Exception("Bunker not connected")
        }
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
        // Capture the invalidated account's pubkey BEFORE clearing in-memory
        // state so the host's fallback can act on it.
        val invalidatedPubkey = bunkerUserPubkey ?: getPublicKey()

        nip46Client?.disconnect()
        nip46Client = null
        _isBunkerConnected.value = false
        clearBunkerCredentials()
        isBunkerLogin = false
        bunkerUserPubkey = null
        // Flip immediately so the resolve() in App.kt does not briefly land in
        // Authenticated (gray-screen flicker) while the async callback below is
        // still running. If the callback successfully activates a fallback
        // account, installBunkerClient / useAccount will set this back to true.
        _isLoggedIn.value = false

        val callback = onSessionInvalidated
        if (callback != null) {
            authScope.launch {
                try { callback(invalidatedPubkey) } catch (_: Throwable) {}
            }
        }
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
     * Logout - clear all auth state and forget the active account.
     *
     * Wipes credentials for the active account (both legacy slots and the
     * pubkey-scoped slot) and removes the Account record so re-login starts
     * fresh. Other accounts in the store, if any, are left intact.
     */
    fun logout() {
        val activePubkey = accountStore.active?.pubkey ?: getPublicKey()

        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        isNip07Login = false
        nip07UserPubkey = null
        zeroAndClearKeyPair()

        _isLoggedIn.value = false
        _isBunkerConnected.value = false
        // Reset the verifying flag so a hung backgroundConnect does not keep
        // the UI stuck on "Reconnecting to signer..." after logout completes.
        // The delay(1500) clearer in installBunkerClient / restoreBunkerSession
        // is best-effort; this is the deterministic path.
        _isBunkerVerifying.value = false

        SecureStorage.clearPrivateKey()
        SecureStorage.clearBunkerUrl()
        SecureStorage.clearBunkerUserPubkey()
        SecureStorage.clearNip07UserPubkey()
        // Keep legacy client key for re-login.

        if (!activePubkey.isNullOrBlank()) {
            SecureStorage.clearAllCredentialsForAccount(activePubkey)
            accountStore.remove(activePubkey)
        }
    }

    /**
     * Completely forget bunker connection
     */
    fun forgetBunkerConnection() {
        nip46Client?.disconnect()
        nip46Client = null
        isBunkerLogin = false
        bunkerUserPubkey = null
        zeroAndClearKeyPair()
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
