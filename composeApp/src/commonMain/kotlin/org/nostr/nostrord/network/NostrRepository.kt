package org.nostr.nostrord.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.outbox.EventDeduplicator
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.epochMillis
import org.nostr.nostrord.utils.urlDecode

/**
 * Repository for Nostr operations.
 * Manages relay connections, group operations, and coordinates with AuthManager for authentication.
 */
object NostrRepository {
    // Managed coroutine scope for background operations - cancelled on logout
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var client: NostrGroupClient? = null
    private var isConnecting = false

    private val _currentRelayUrl = MutableStateFlow("wss://groups.fiatjaf.com")
    val currentRelayUrl: StateFlow<String> = _currentRelayUrl.asStateFlow()
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _groups = MutableStateFlow<List<GroupMetadata>>(emptyList())
    val groups: StateFlow<List<GroupMetadata>> = _groups.asStateFlow()
    
    private val _messages = MutableStateFlow<Map<String, List<NostrGroupClient.NostrMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<NostrGroupClient.NostrMessage>>> = _messages.asStateFlow()
    
    private val _joinedGroups = MutableStateFlow<Set<String>>(emptySet())
    val joinedGroups: StateFlow<Set<String>> = _joinedGroups.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    fun forceInitialized() {
        _isInitialized.value = true
    }

    // Delegate auth state to AuthManager
    val isLoggedIn: StateFlow<Boolean> = AuthManager.isLoggedIn
    val isBunkerConnected: StateFlow<Boolean> = AuthManager.isBunkerConnected
    val authUrl: StateFlow<String?> = AuthManager.authUrl

    private val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata.asStateFlow()

    private val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents.asStateFlow()

    // NIP-65 Outbox Model: Relay list manager and event deduplicator
    private val relayListManager = RelayListManager(
        bootstrapRelays = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.nostr.net",
            "wss://purplepag.es"
        )
    )
    private val eventDeduplicator = EventDeduplicator()

    // Single relay pool for ALL auxiliary connections (outbox, metadata, NIP-65)
    private val relayPool = mutableMapOf<String, NostrGroupClient>()

    init {
        // Share relay pool with RelayListManager (no duplicate connections)
        relayListManager.setConnectionProvider { relayUrl ->
            getOrConnectRelay(relayUrl)
        }
    }

    // Expose user's relay list from RelayListManager
    val userRelayList: StateFlow<List<Nip65Relay>> = relayListManager.myRelayList

    private var kind10009SubId: String? = null
    private var kind10009Received = false
    private var eoseReceived = false
    private var kind10002SubId: String? = null
    private var kind10002Received = false
    
    private val allRelayGroups = mutableMapOf<String, MutableSet<String>>()
    
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    suspend fun initialize() {
        // Load saved relay URL
        val savedRelayUrl = SecureStorage.getCurrentRelayUrl()
        if (savedRelayUrl != null) {
            _currentRelayUrl.value = savedRelayUrl
        }

        // Try to restore auth session via AuthManager
        val restored = AuthManager.restoreSession()
        if (restored) {
            val pubkey = AuthManager.getPublicKey()
            if (pubkey != null) {
                _joinedGroups.value = SecureStorage.getJoinedGroupsForRelay(pubkey, _currentRelayUrl.value)
            }
            // Connect to relays
            initializeOutboxModel()
            connect()
        }

        _isInitialized.value = true
    }

    fun clearAuthUrl() {
        AuthManager.clearAuthUrl()
    }

    /**
     * Login with NIP-46 bunker URL
     */
    suspend fun loginWithBunker(bunkerUrl: String): String {
        val userPubkey = AuthManager.loginWithBunker(bunkerUrl)

        // Connect to relays BEFORE setting logged in
        initializeOutboxModel()
        connect()

        // Set logged in after connections established
        AuthManager.setLoggedIn(true)

        return userPubkey
    }

    /**
     * Sign event using AuthManager
     */
    private suspend fun signEvent(event: Event): Event {
        return AuthManager.signEvent(event)
    }

    private fun parseSignedEvent(jsonString: String): Event {
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
    
    // Load kind:10009 from user's WRITE relays (Outbox model)
    private suspend fun loadJoinedGroupsFromNostr() {
        val pubKey = getPublicKey() ?: return

        // Get user's WRITE relays (where they published kind:10009)
        val writeRelays = relayListManager.selectPublishRelays()
        if (writeRelays.isEmpty()) {
            return
        }

        // Use the first available relay from the pool
        val relayUrl = writeRelays.first()
        val currentClient = getOrConnectRelay(relayUrl) ?: run {
            return
        }

        try {
            kind10009Received = false
            eoseReceived = false

            val filter = buildJsonObject {
                putJsonArray("kinds") { add(10009) }
                putJsonArray("authors") { add(pubKey) }
                put("limit", 1)
            }

            val subId = "joined-groups-${epochMillis()}"
            kind10009SubId = subId

            val message = buildJsonArray {
                add("REQ")
                add(subId)
                add(filter)
            }.toString()

            currentClient.send(message)

            var waitTime = 0
            while (!eoseReceived && waitTime < 3000) {
                kotlinx.coroutines.delay(100)
                waitTime += 100
            }

            val closeMsg = buildJsonArray {
                add("CLOSE")
                add(subId)
            }.toString()
            currentClient.send(closeMsg)

            if (!kind10009Received) {
                val pubKey = getPublicKey() ?: ""
                val localGroups = SecureStorage.getJoinedGroupsForRelay(pubKey, _currentRelayUrl.value)
                if (localGroups.isNotEmpty()) {
                    _joinedGroups.value = localGroups
                    allRelayGroups[_currentRelayUrl.value] = localGroups.toMutableSet()
                    publishJoinedGroupsList()
                } else {
                }
            } else {
            }
        } catch (e: Exception) {
        }
    }

    // NIP-65: Load user's relay list (kind:10002) using RelayListManager
    private suspend fun loadUserRelayList(pubKey: String) {
        try {
            val relays = relayListManager.getRelayList(pubKey)

            if (relays.isNotEmpty()) {
                // Set as current user's relay list
                relayListManager.setMyRelayList(pubKey, relays)
            } else {
            }
        } catch (e: Exception) {
        }
    }

    // Publish kind:10009 to user's WRITE relays (Outbox model)
    private suspend fun publishJoinedGroupsList() {
        val pubKey = getPublicKey() ?: run {
            return
        }

        try {
            val currentRelayGroups = _joinedGroups.value
            allRelayGroups[_currentRelayUrl.value] = currentRelayGroups.toMutableSet()

            val tags = mutableListOf<List<String>>()
            allRelayGroups.forEach { (relayUrl, groupIds) ->
                groupIds.forEach { groupId ->
                    tags.add(listOf("group", groupId, relayUrl))
                }
            }

            allRelayGroups.forEach { (relay, groups) ->
            }

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 10009,
                tags = tags,
                content = ""
            )

            val signedEvent = signEvent(event)

            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

            // Publish to user's WRITE relays (Outbox model)
            val writeRelays = relayListManager.selectPublishRelays()
            sendToRelays(writeRelays, message)
            val totalGroups = tags.size
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Initialize the Outbox model for the current user.
     * Connects immediately to bootstrap relays, fetches NIP-65 in background.
     */
    private suspend fun initializeOutboxModel() {
        val pubKey = getPublicKey() ?: run {
            return
        }


        // Step 1: Connect to bootstrap relays immediately (don't wait for NIP-65)
        val bootstrapRelays = relayListManager.bootstrapRelays.take(3)

        // Connect to all bootstrap relays in parallel
        val connectionJobs = bootstrapRelays.map { relayUrl ->
            repositoryScope.launch {
                try {
                    val client = getOrConnectRelay(relayUrl)
                    client?.requestMetadata(listOf(pubKey))
                } catch (e: Exception) {
                }
            }
        }

        // Step 2: Fetch NIP-65 relay list in background (don't block)
        repositoryScope.launch {
            loadUserRelayList(pubKey)
        }

        // Step 3: Load joined groups in background (don't block)
        repositoryScope.launch {
            // Small delay to let at least one connection establish
            kotlinx.coroutines.delay(300)
            loadJoinedGroupsFromNostr()
        }

        // Wait briefly for at least one connection
        kotlinx.coroutines.withTimeoutOrNull(2000) {
            while (relayPool.isEmpty()) {
                kotlinx.coroutines.delay(50)
            }
        }

    }

    /**
     * Get or create a connection to a relay (shared pool for all auxiliary connections)
     */
    private suspend fun getOrConnectRelay(relayUrl: String): NostrGroupClient? {
        // Check if we already have a connection
        relayPool[relayUrl]?.let { return it }

        return try {
            val newClient = NostrGroupClient(relayUrl)
            newClient.connect { msg ->
                handleRelayMessage(msg, newClient)
            }
            newClient.waitForConnection()
            relayPool[relayUrl] = newClient
            newClient
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Send a message to multiple relays
     */
    private suspend fun sendToRelays(relayUrls: List<String>, message: String) {
        relayUrls.forEach { relayUrl ->
            repositoryScope.launch {
                try {
                    val client = getOrConnectRelay(relayUrl)
                    client?.send(message)
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Connect to relays discovered from kind:10009.
     * These are the actual relays where the user has groups.
     */
    private fun connectToKind10009Relays() {
        val relayUrls = allRelayGroups.keys.toList()
        if (relayUrls.isEmpty()) {
            return
        }


        // Connect to each relay in parallel
        relayUrls.forEach { relayUrl ->
            repositoryScope.launch {
                try {
                    val client = getOrConnectRelay(relayUrl)
                    if (client != null) {
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Handle messages from relay pool (metadata, events, etc.)
     */
    private fun handleRelayMessage(msg: String, client: NostrGroupClient) {
        // Delegate to existing handler
        handleMetadataMessage(msg, client)
    } 

    private fun handleMetadataMessage(msg: String, client: NostrGroupClient) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "EOSE") {
                val subId = arr[1].jsonPrimitive.content
                if (subId == kind10009SubId) {
                    eoseReceived = true
                }
                return
            }
            
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject
                val kind = event["kind"]?.jsonPrimitive?.int

                // Handle event_* subscriptions (fetched events by ID)
                if (subId.startsWith("event_")) {
                    val eventId = event["id"]?.jsonPrimitive?.content ?: return
                    val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                    val content = event["content"]?.jsonPrimitive?.content ?: ""
                    val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                    val eventKind = kind ?: 1
                    val tags = event["tags"]?.jsonArray?.map { tagArray ->
                        tagArray.jsonArray.map { it.jsonPrimitive.content }
                    } ?: emptyList()

                    val cachedEvent = CachedEvent(
                        id = eventId,
                        pubkey = pubkey,
                        kind = eventKind,
                        content = content,
                        createdAt = createdAt,
                        tags = tags
                    )
                    _cachedEvents.value = _cachedEvents.value + (eventId to cachedEvent)
                    return
                }

                if (kind == 10009) {
                    kind10009Received = true
                    val tags = event["tags"]?.jsonArray ?: return
                    
                    allRelayGroups.clear()
                    val currentRelayGroups = mutableSetOf<String>()
                    
                    tags.forEach { tag ->
                        val tagArray = tag.jsonArray
                        if (tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "group") {
                            val groupId = tagArray[1].jsonPrimitive.content
                            val relayUrl = tagArray.getOrNull(2)?.jsonPrimitive?.content
                            
                            if (relayUrl != null) {
                                allRelayGroups.getOrPut(relayUrl) { mutableSetOf() }.add(groupId)
                                
                                if (relayUrl == _currentRelayUrl.value) {
                                    currentRelayGroups.add(groupId)
                                } else {
                                }
                            } else {
                                currentRelayGroups.add(groupId)
                                allRelayGroups.getOrPut(_currentRelayUrl.value) { mutableSetOf() }.add(groupId)
                            }
                        }
                    }
                    
                    _joinedGroups.value = currentRelayGroups
                    getPublicKey()?.let { pubKey ->
                        SecureStorage.saveJoinedGroupsForRelay(pubKey, _currentRelayUrl.value, currentRelayGroups)
                    }

                    // Connect to relays from kind:10009 (these are the user's actual group relays)
                    connectToKind10009Relays()
                    return
                }

                // NIP-65: Handle relay list (kind:10002)
                if (kind == 10002) {
                    val eventPubkey = event["pubkey"]?.jsonPrimitive?.content
                    val isCurrentUser = eventPubkey == getPublicKey()

                    if (isCurrentUser) {
                        kind10002Received = true
                    }

                    val tags = event["tags"]?.jsonArray ?: return

                    val relays = mutableListOf<Nip65Relay>()
                    tags.forEach { tag ->
                        val tagArray = tag.jsonArray
                        if (tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "r") {
                            val relayUrl = tagArray[1].jsonPrimitive.content
                            val marker = tagArray.getOrNull(2)?.jsonPrimitive?.content

                            val relay = when (marker) {
                                "read" -> Nip65Relay(relayUrl, read = true, write = false)
                                "write" -> Nip65Relay(relayUrl, read = false, write = true)
                                else -> Nip65Relay(relayUrl, read = true, write = true)
                            }
                            relays.add(relay)
                        }
                    }

                    // Store in RelayListManager cache
                    if (eventPubkey != null && relays.isNotEmpty()) {
                        if (isCurrentUser) {
                            // Set as current user's relay list (also caches it)
                            relayListManager.setMyRelayList(eventPubkey, relays)
                        } else {
                            // Cache other users' relay lists for outbox model
                            relayListManager.cacheRelayListForUser(eventPubkey, relays)
                        }
                    }

                    return
                }
            }
        } catch (e: Exception) {
        }
        
        val userMetadata = client.parseUserMetadata(msg)
        if (userMetadata != null) {
            val (pubkey, metadata) = userMetadata
            _userMetadata.value = _userMetadata.value + (pubkey to metadata)
        }
    }

    suspend fun connect() {
        connect(_currentRelayUrl.value)
    }
    
    private suspend fun connect(relayUrl: String) {
        if (client != null || isConnecting) {
            return
        }
        
        isConnecting = true
        _connectionState.value = ConnectionState.Connecting
        
        try {
            val newClient = NostrGroupClient(relayUrl)
            client = newClient
            
            newClient.connect { msg ->
                handleMessage(msg, newClient)
            }
            
            newClient.waitForConnection()
            _connectionState.value = ConnectionState.Connected
            
            // Only send AUTH if using local keypair (not bunker)
            if (!AuthManager.isUsingBunker()) {
                AuthManager.getPrivateKey()?.let { privateKey ->
                    newClient.sendAuth(privateKey)
                }
            }
            newClient.requestGroups()
            
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            client = null
        } finally {
            isConnecting = false
        }
    }

    // Delegate auth functions to AuthManager
    fun getPublicKey(): String? = AuthManager.getPublicKey()
    fun getPrivateKey(): String? = AuthManager.getPrivateKey()
    fun isUsingBunker(): Boolean = AuthManager.isUsingBunker()
    fun isBunkerReady(): Boolean = AuthManager.isBunkerReady()

    suspend fun ensureBunkerConnected(): Boolean {
        return AuthManager.ensureBunkerConnected()
    }

    /**
     * Login with private key
     */
    suspend fun loginSuspend(privKey: String, pubKey: String) {
        AuthManager.loginWithPrivateKey(privKey, pubKey)

        // Connect to relays
        initializeOutboxModel()
        connect()

        AuthManager.setLoggedIn(true)
    }

    /**
     * Logout - clear all state
     */
    suspend fun logout() {
        // Cancel all background coroutines
        repositoryScope.coroutineContext.cancelChildren()

        // Clear account-specific data before logout
        getPublicKey()?.let { pubKey ->
            SecureStorage.clearAllJoinedGroupsForAccount(pubKey)
        }

        // Disconnect relays
        disconnect()
        relayPool.values.forEach { it.disconnect() }
        relayPool.clear()
        relayListManager.clear()

        // Clear auth via AuthManager
        AuthManager.logout()

        // Clear local state
        _joinedGroups.value = emptySet()
        allRelayGroups.clear()
    }

    /**
     * Completely forget bunker connection
     */
    fun forgetBunkerConnection() {
        AuthManager.forgetBunkerConnection()
    }

    suspend fun switchRelay(newRelayUrl: String) {
        
        disconnect()
        
        _currentRelayUrl.value = newRelayUrl
        SecureStorage.saveCurrentRelayUrl(newRelayUrl)

        val pubKey = getPublicKey() ?: ""
        _joinedGroups.value = SecureStorage.getJoinedGroupsForRelay(pubKey, newRelayUrl)
        
        connect(newRelayUrl)
        
        kind10009Received = false
        eoseReceived = false

        if (relayPool.isEmpty()) {
            initializeOutboxModel()
        }
        // No delay needed - initializeOutboxModel already waits for connection

        loadJoinedGroupsFromNostr()
    }

    /**
     * Request user metadata from their WRITE relays (Outbox model)
     */
    suspend fun requestUserMetadata(pubkeys: Set<String>) {
        if (pubkeys.isEmpty()) return


        // For each user, fetch from their WRITE relays
        pubkeys.forEach { pubkey ->
            repositoryScope.launch {
                try {
                    // First, get their relay list
                    val relays = relayListManager.getRelayList(pubkey)
                    val writeRelays = if (relays.isNotEmpty()) {
                        relays.filter { it.write }.map { it.url }
                    } else {
                        relayListManager.bootstrapRelays
                    }

                    // Fetch metadata from their WRITE relays
                    writeRelays.take(3).forEach { relayUrl ->
                        try {
                            val client = getOrConnectRelay(relayUrl)
                            client?.requestMetadata(listOf(pubkey))
                        } catch (e: Exception) {
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Request NIP-65 relay lists for given pubkeys
     * Uses RelayListManager for caching and fetching
     */
    suspend fun requestRelayLists(pubkeys: Set<String>) {
        // RelayListManager handles caching and fetching from bootstrap relays
        pubkeys.forEach { pubkey ->
            repositoryScope.launch {
                try {
                    relayListManager.getRelayList(pubkey)
                } catch (e: Exception) {
                }
            }
        }
    }

    /**
     * Get relay list for a pubkey from cache
     */
    fun getRelayListForPubkey(pubkey: String): List<Nip65Relay> {
        return relayListManager.getCachedRelayList(pubkey)
    }

    /**
     * Outbox model: Select relays based on query parameters (NIP-65)
     *
     * @param authors Pubkeys of content authors (use their WRITE/outbox relays)
     * @param taggedPubkeys Pubkeys tagged in content (use their READ/inbox relays)
     * @param explicitRelays Explicit relay hints that override outbox selection
     * @return List of relay URLs to query, in priority order
     */
    fun selectOutboxRelays(
        authors: List<String> = emptyList(),
        taggedPubkeys: List<String> = emptyList(),
        explicitRelays: List<String> = emptyList()
    ): List<String> {
        val relays = mutableListOf<String>()
        var hasAuthorRelays = false

        // 1. Explicit relays always come first (highest priority)
        explicitRelays.forEach { relay ->
            if (relay.isNotBlank() && relay !in relays) {
                relays.add(relay)
            }
        }

        // 2. If we have authors, use their WRITE relays (outbox)
        if (authors.isNotEmpty()) {
            authors.forEach { author ->
                val authorRelays = getRelayListForPubkey(author)
                if (authorRelays.isNotEmpty()) {
                    hasAuthorRelays = true
                }
                authorRelays
                    .filter { it.write }
                    .forEach { relay ->
                        if (relay.url !in relays) {
                            relays.add(relay.url)
                        }
                    }
            }
        }

        // 3. If we have tagged pubkeys, use their READ relays (inbox)
        if (taggedPubkeys.isNotEmpty()) {
            taggedPubkeys.forEach { pubkey ->
                val pubkeyRelays = getRelayListForPubkey(pubkey)
                pubkeyRelays
                    .filter { it.read }
                    .forEach { relay ->
                        if (relay.url !in relays) {
                            relays.add(relay.url)
                        }
                    }
            }
        }

        // 4. If no authors or tagged users, use current user's READ relays
        if (authors.isEmpty() && taggedPubkeys.isEmpty()) {
            val myRelays = relayListManager.myRelayList.value
            if (myRelays.isNotEmpty()) {
                hasAuthorRelays = true
            }
            myRelays
                .filter { it.read }
                .forEach { relay ->
                    if (relay.url !in relays) {
                        relays.add(relay.url)
                    }
                }
        }

        // 5. Add current NIP-29 relay as fallback (default Nostrord relay)
        // This is important when users don't have a NIP-65 relay list
        val currentNip29Relay = _currentRelayUrl.value
        if (currentNip29Relay !in relays) {
            relays.add(currentNip29Relay)
        }

        // 6. Always add fallback bootstrap relays at the end
        relayListManager.bootstrapRelays.forEach { relay ->
            if (relay !in relays) {
                relays.add(relay)
            }
        }

        // Log if we're using fallback relays due to missing NIP-65 data
        if (!hasAuthorRelays && authors.isNotEmpty()) {
        }

        return relays
    }

    suspend fun requestEventById(eventId: String, relayHints: List<String> = emptyList(), author: String? = null) {
        // Skip if already cached
        if (_cachedEvents.value.containsKey(eventId)) {
            return
        }


        // If we have an author, try to get their relay list first
        if (author != null && relayListManager.getCachedRelayList(author).isEmpty()) {
            requestRelayLists(setOf(author))
            // Give a small delay for the relay list to arrive
            kotlinx.coroutines.delay(200)
        }

        // Use outbox model for relay selection
        val relaysToTry = selectOutboxRelays(
            authors = if (author != null) listOf(author) else emptyList(),
            explicitRelays = relayHints
        )


        // Request from all available relays
        for (relayUrl in relaysToTry) {
            try {
                val client = getOrConnectHintRelay(relayUrl)
                client?.requestEventById(eventId)
            } catch (e: Exception) {
            }
        }
    }

    // Get or create a client for a hint relay (uses outbox pool)
    private suspend fun getOrConnectHintRelay(relayUrl: String): NostrGroupClient? {
        return getOrConnectRelay(relayUrl)
    }

    // Handle messages from hint relays (for event fetching)
    private fun handleHintRelayMessage(msg: String, client: NostrGroupClient) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray

            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "EVENT") {
                val subId = arr[1].jsonPrimitive.content
                val event = arr[2].jsonObject

                // Handle event_* subscriptions (fetched events by ID)
                if (subId.startsWith("event_")) {
                    val eventId = event["id"]?.jsonPrimitive?.content ?: return

                    // Skip if already cached
                    if (_cachedEvents.value.containsKey(eventId)) {
                        return
                    }

                    val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return
                    val content = event["content"]?.jsonPrimitive?.content ?: ""
                    val createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
                    val kind = event["kind"]?.jsonPrimitive?.int ?: 1
                    val tags = event["tags"]?.jsonArray?.map { tagArray ->
                        tagArray.jsonArray.map { it.jsonPrimitive.content }
                    } ?: emptyList()

                    val cachedEvent = CachedEvent(
                        id = eventId,
                        pubkey = pubkey,
                        kind = kind,
                        content = content,
                        createdAt = createdAt,
                        tags = tags
                    )
                    _cachedEvents.value = _cachedEvents.value + (eventId to cachedEvent)

                    // Also request metadata for the event author
                    if (!_userMetadata.value.containsKey(pubkey)) {
                        repositoryScope.launch {
                            requestUserMetadata(setOf(pubkey))
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun handleMessage(msg: String, client: NostrGroupClient) {
        // Handle NIP-42 AUTH challenge first
        val authChallenge = client.parseAuthChallenge(msg)
        if (authChallenge != null) {
            repositoryScope.launch {
                handleAuthChallenge(client, authChallenge)
            }
            return
        }

        val groupMetadata = client.parseGroupMetadata(msg)
        if (groupMetadata != null && groupMetadata.name != null) {
            _groups.value = (_groups.value + groupMetadata).distinctBy { it.id }
            return
        }
        
        val userMetadata = client.parseUserMetadata(msg)
        if (userMetadata != null) {
            val (pubkey, metadata) = userMetadata
            _userMetadata.value = _userMetadata.value + (pubkey to metadata)
            return
        }
        
        val message = client.parseMessage(msg)
        if (message != null && (message.kind == 9 || message.kind == 9021 || message.kind == 9022)) {
            val messageId = message.id

            // Use EventDeduplicator for efficient O(1) deduplication
            if (messageId.isBlank() || !eventDeduplicator.tryAddSync(messageId)) {
                // Skip duplicate message
                return
            }

            val groupId = extractGroupIdFromMessage(msg)
            if (groupId != null) {
                val currentMessages = _messages.value[groupId] ?: emptyList()
                _messages.value = _messages.value + (groupId to (currentMessages + message).sortedBy { it.createdAt })

                if (!_userMetadata.value.containsKey(message.pubkey)) {
                    repositoryScope.launch {
                        requestUserMetadata(setOf(message.pubkey))
                        // Also request NIP-65 relay list for outbox model support
                        requestRelayLists(setOf(message.pubkey))
                    }
                }

                val eventType = when (message.kind) {
                    9 -> "message"
                    9021 -> "join"
                    9022 -> "leave"
                    else -> "event"
                }
            }
        }
    } 

    /**
     * Handle NIP-42 AUTH challenge from relay
     */
    private suspend fun handleAuthChallenge(client: NostrGroupClient, challenge: String) {
        val pubKey = getPublicKey() ?: run {
            return
        }

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

        } catch (e: Exception) {
        }
    }

    private fun extractGroupIdFromMessage(msg: String): String? {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            if (arr.size < 3) return null
            val event = arr[2].jsonObject
            val tags = event["tags"]?.jsonArray ?: return null

            tags.firstOrNull { tag ->
                val tagArray = tag.jsonArray
                tagArray.size >= 2 && tagArray[0].jsonPrimitive.content == "h"
            }?.jsonArray?.get(1)?.jsonPrimitive?.content
        } catch (e: Exception) {
            null
        }
    }

    suspend fun joinGroup(groupId: String) {
        val currentClient = client ?: run {
            return
        }
        
        val pubKey = getPublicKey() ?: run {
            return
        }
        
        try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9021,
                tags = listOf(
                    listOf("h", groupId)
                ),
                content = "/join"
            )

            
            val signedEvent = signEvent(event)
            
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            
            currentClient.send(message)

            _joinedGroups.value = _joinedGroups.value + groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, _currentRelayUrl.value, _joinedGroups.value)

            publishJoinedGroupsList()

            
            requestGroupMessages(groupId)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    suspend fun leaveGroup(groupId: String, reason: String? = null) {
        val currentClient = client ?: run {
            return
        }
        
        val pubKey = getPublicKey() ?: run {
            return
        }
        
        try {
            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9022,
                tags = listOf(
                    listOf("h", groupId)
                ),
                content = reason.orEmpty()
            )

            
            val signedEvent = signEvent(event)
            
            val message = buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()
            
            currentClient.send(message)

            _joinedGroups.value = _joinedGroups.value - groupId
            SecureStorage.saveJoinedGroupsForRelay(pubKey, _currentRelayUrl.value, _joinedGroups.value)

            publishJoinedGroupsList()

            _messages.value = _messages.value - groupId

            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun isGroupJoined(groupId: String): Boolean {
        return _joinedGroups.value.contains(groupId)
    }
    
    suspend fun requestGroupMessages(groupId: String, channel: String? = null) {
        val currentClient = client
        if (currentClient == null) {
            connect()
            return requestGroupMessages(groupId, channel)
        }
        
        currentClient.requestGroupMessages(groupId, channel)
    }

    suspend fun sendMessage(groupId: String, content: String, channel: String? = null, mentions: Map<String, String> = emptyMap()) {
        val currentClient = client ?: run {
            return
        }

        val pubKey = getPublicKey() ?: run {
            return
        }

        try {
            val tags = mutableListOf(listOf("h", groupId))
            if (channel != null && channel != "general") {
                tags.add(listOf("channel", channel))
            }

            // Replace @displayName with nostr:npub... in content
            var processedContent = content
            mentions.forEach { (displayName, pubkeyHex) ->
                val npub = org.nostr.nostrord.nostr.Nip19.encodeNpub(pubkeyHex)
                processedContent = processedContent.replace("@$displayName", "nostr:$npub")
                // Add p tag for mentioned user
                tags.add(listOf("p", pubkeyHex))
            }

            val event = Event(
                pubkey = pubKey,
                createdAt = epochMillis() / 1000,
                kind = 9,
                tags = tags,
                content = processedContent
            )
            
            val signedEvent = signEvent(event)
            
            val eventJson = signedEvent.toJsonObject()
            val message = buildJsonArray {
                add("EVENT")
                add(eventJson)
            }.toString()
            
            currentClient.send(message)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMessagesForGroup(groupId: String): List<NostrGroupClient.NostrMessage> {
        return _messages.value[groupId] ?: emptyList()
    }
    
    suspend fun disconnect() {
        client?.disconnect()
        client = null
        _connectionState.value = ConnectionState.Disconnected
        _groups.value = emptyList()
        _messages.value = emptyMap()
        isConnecting = false
    }
}

// Helper function for parsing bunker URLs
data class BunkerInfo(
    val pubkey: String,
    val relays: List<String>,
    val secret: String?
)

fun parseBunkerUrl(url: String): BunkerInfo {
    val trimmed = url.trim()
    
    require(trimmed.startsWith("bunker://")) {
        "Invalid bunker URL: must start with bunker://"
    }

    val withoutScheme = trimmed.removePrefix("bunker://")
    val parts = withoutScheme.split("?", limit = 2)
    
    val pubkey = parts[0]
    require(pubkey.length == 64 && pubkey.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid pubkey in bunker URL"
    }

    val relays = mutableListOf<String>()
    var secret: String? = null

    if (parts.size > 1) {
        val queryParams = parts[1].split("&")
        for (param in queryParams) {
            val kv = param.split("=", limit = 2)
            if (kv.size == 2) {
                val key = kv[0]
                val value = kv[1].urlDecode()
                when (key) {
                    "relay" -> relays.add(value)
                    "secret" -> secret = value
                }
            }
        }
    }

    require(relays.isNotEmpty()) {
        "Bunker URL must contain at least one relay"
    }

    return BunkerInfo(pubkey, relays, secret)
}
