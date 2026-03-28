package org.nostr.nostrord.nostr

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochMillis
import kotlin.random.Random

actual class Nip46Client actual constructor(existingPrivateKey: String?) {
    private val clientKeyPair: KeyPair = if (existingPrivateKey != null) {
        KeyPair.fromPrivateKeyHex(existingPrivateKey)
    } else {
        KeyPair.generate()
    }

    private var remoteSignerPubkey: String? = null
    private var relayClients: MutableList<NostrGroupClient> = mutableListOf()
    private var relayUrls: List<String> = emptyList()
    private val pendingRequests: MutableMap<String, CompletableDeferred<String>> = java.util.concurrent.ConcurrentHashMap()
    private var listenSubscriptionId: String? = null
    private var nostrConnectSecret: String? = null

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nip46Json = Json { ignoreUnknownKeys = true }

    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual fun generateNostrConnectUri(relays: List<String>, name: String): String {
        val relayParams = relays.joinToString("&") { "relay=${it.encodeForUri()}" }
        val metadata = """{"name":"$name"}"""
        val secretParam = nostrConnectSecret?.let { "&secret=${it.encodeForUri()}" } ?: ""
        val uri = "nostrconnect://${clientKeyPair.publicKeyHex}?$relayParams$secretParam&metadata=${metadata.encodeForUri()}"
        return uri
    }

    private suspend fun connectRelaysParallel(relays: List<String>): List<NostrGroupClient> =
        coroutineScope {
            relays.map { relayUrl ->
                async {
                    try {
                        val cleanUrl = relayUrl.trimEnd('/')
                        val client = NostrGroupClient(cleanUrl)
                        client.connect { msg -> handleMessage(msg) }
                        client.waitForConnection()
                        client
                    } catch (e: Exception) { null }
                }
            }.awaitAll().filterNotNull()
        }

    /**
     * Ensure at least one relay client is connected before sending a request.
     * Disconnected clients are replaced with fresh connections.
     */
    private suspend fun ensureRelaysConnected() {
        // Remove dead clients
        val dead = relayClients.filter { !it.isConnected() }
        if (dead.isNotEmpty()) {
            dead.forEach { try { it.disconnect() } catch (_: Exception) {} }
            relayClients.removeAll(dead)
        }

        // If we still have live clients, we're good
        if (relayClients.isNotEmpty()) return

        // Reconnect using stored relay URLs
        if (relayUrls.isEmpty()) return
        val fresh = connectRelaysParallel(relayUrls)
        relayClients.addAll(fresh)
    }

    actual suspend fun connectRelaysOnly(remoteSignerPubkey: String, relays: List<String>) {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }
        relayClients.addAll(connectRelaysParallel(relays))
        if (relayClients.isEmpty()) throw Exception("Failed to connect to any bunker relay")
    }

    actual suspend fun startListeningForConnection(relays: List<String>, secret: String?) {
        nostrConnectSecret = secret ?: generateRequestId().take(16)
        this.relayUrls = relays.map { it.trimEnd('/') }

        relayClients.addAll(connectRelaysParallel(relays))

        if (relayClients.isEmpty()) {
            throw Exception("Failed to connect to any relay")
        }

        val subscriptionId = "nip46-listen-${generateRequestId().take(8)}"
        listenSubscriptionId = subscriptionId
        val since = (epochMillis() / 1000) - 10
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(24133) }
            putJsonArray("#p") { add(clientKeyPair.publicKeyHex) }
            put("since", since)
        }
        val subMessage = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(filter)
        }.toString()

        relayClients.forEach { try { it.send(subMessage) } catch (e: Exception) {
        } }

        pendingRequests["_incoming_connect"] = CompletableDeferred()
    }

    actual suspend fun awaitIncomingConnection(): String {
        val connectDeferred = pendingRequests["_incoming_connect"]
            ?: throw Exception("Not listening. Call startListeningForConnection first.")

        try {
            val result = withTimeout(120_000) { connectDeferred.await() }
            return result
        } catch (e: Exception) {
            throw e
        } finally {
            pendingRequests.remove("_incoming_connect")
            listenSubscriptionId?.let { subId ->
                val closeMessage = buildJsonArray { add("CLOSE"); add(subId) }.toString()
                withContext(NonCancellable) {
                    relayClients.forEach { try { it.send(closeMessage) } catch (_: Exception) {} }
                }
            }
            listenSubscriptionId = null
        }
    }

    actual suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?
    ): String {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }

        relayClients.addAll(connectRelaysParallel(relays))

        if (relayClients.isEmpty()) {
            throw Exception("Failed to connect to any bunker relay")
        }

        val requestId = generateRequestId()
        val params = buildList {
            add(remoteSignerPubkey)
            secret?.let { add(it) }
        }

        sendRequest(requestId, "connect", params)

        return remoteSignerPubkey
    }

    actual suspend fun getPublicKey(): String {
        val requestId = generateRequestId()
        val response = sendRequest(requestId, "get_public_key", emptyList())
        return response
    }

    actual suspend fun signEvent(eventJson: String): String {
        val requestId = generateRequestId()
        return sendRequest(requestId, "sign_event", listOf(eventJson))
    }

    private suspend fun sendRequest(
        requestId: String,
        method: String,
        params: List<String>
    ): String = withTimeout(120_000) {
        val signerPubkey = remoteSignerPubkey
            ?: throw Exception("Not connected to signer")

        // Ensure relay connections are alive before sending
        ensureRelaysConnected()
        if (relayClients.isEmpty()) {
            throw Exception("No bunker relay connections available")
        }

        val requestJson = buildJsonObject {
            put("id", requestId)
            put("method", method)
            putJsonArray("params") { params.forEach { add(it) } }
        }.toString()

        val encryptedContent = Nip44.encrypt(
            plaintext = requestJson,
            privateKeyHex = clientKeyPair.privateKeyHex,
            pubKeyHex = signerPubkey
        )

        val event = Event(
            pubkey = clientKeyPair.publicKeyHex,
            createdAt = epochMillis() / 1000,
            kind = 24133,
            tags = listOf(listOf("p", signerPubkey)),
            content = encryptedContent
        )

        val signedEvent = event.sign(clientKeyPair)
        val responseDeferred = CompletableDeferred<String>()
        pendingRequests[requestId] = responseDeferred

        val subscriptionId = "nip46-${requestId.take(8)}"
        val since = (epochMillis() / 1000) - 120

        val filter = buildJsonObject {
            putJsonArray("kinds") { add(24133) }
            putJsonArray("#p") { add(clientKeyPair.publicKeyHex) }
            put("since", since)
        }

        val subMessage = buildJsonArray {
            add("REQ")
            add(subscriptionId)
            add(filter)
        }.toString()

        var sentToAny = false
        relayClients.forEach { client ->
            try { client.send(subMessage); sentToAny = true } catch (_: Exception) {}
        }

        delay(100)

        val eventMessage = buildJsonArray {
            add("EVENT")
            add(signedEvent.toJsonObject())
        }.toString()

        relayClients.forEach { client ->
            try { client.send(eventMessage); sentToAny = true } catch (_: Exception) {}
        }

        if (!sentToAny) {
            pendingRequests.remove(requestId)
            throw Exception("Failed to send signing request to any relay")
        }

        try {
            responseDeferred.await()
        } finally {
            pendingRequests.remove(requestId)
            val closeMessage = buildJsonArray {
                add("CLOSE")
                add(subscriptionId)
            }.toString()
            withContext(NonCancellable) {
                relayClients.forEach { try { it.send(closeMessage) } catch (_: Exception) {} }
            }
        }
    }

    private fun handleMessage(msg: String) {
        try {
            val json = nip46Json
            val arr = json.parseToJsonElement(msg).jsonArray
            val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

            if (msgType == "EOSE" || msgType == "OK" || msgType == "NOTICE") {
                return
            }

            if (msgType == "EVENT" && arr.size >= 3) {
                val eventObj = arr[2].jsonObject
                val kind = eventObj["kind"]?.jsonPrimitive?.int ?: return

                if (kind != 24133) {
                    return
                }

                val eventPubkey = eventObj["pubkey"]?.jsonPrimitive?.content ?: return
                val encryptedContent = eventObj["content"]?.jsonPrimitive?.content ?: return

                try {
                    val decrypted = decryptMessage(
                        ciphertext = encryptedContent,
                        privateKeyHex = clientKeyPair.privateKeyHex,
                        pubKeyHex = eventPubkey
                    )
                    val responseObj = json.parseToJsonElement(decrypted).jsonObject
                    val responseId = responseObj["id"]?.jsonPrimitive?.content
                    val method = responseObj["method"]?.jsonPrimitive?.contentOrNull
                    val result = responseObj["result"]?.jsonPrimitive?.contentOrNull
                    val error = responseObj["error"]?.jsonPrimitive?.contentOrNull

                    // Handle incoming connect request (nostrconnect:// flow)
                    if (method == "connect") {
                        val params = responseObj["params"]?.jsonArray
                        val incomingSecret = params?.getOrNull(1)?.jsonPrimitive?.contentOrNull
                        val expectedSec = nostrConnectSecret
                        if (expectedSec != null && incomingSecret != null && incomingSecret != expectedSec) {
                            return // reject: secret mismatch
                        }

                        remoteSignerPubkey = eventPubkey

                        clientScope.launch {
                            try {
                                val ackResponse = buildJsonObject {
                                    responseId?.let { put("id", it) }
                                    put("result", "ack")
                                }.toString()
                                val ackEncrypted = Nip44.encrypt(ackResponse, clientKeyPair.privateKeyHex, eventPubkey)
                                val ackEvent = Event(
                                    pubkey = clientKeyPair.publicKeyHex,
                                    createdAt = epochMillis() / 1000,
                                    kind = 24133,
                                    tags = listOf(listOf("p", eventPubkey)),
                                    content = ackEncrypted
                                ).sign(clientKeyPair)
                                val ackMessage = buildJsonArray {
                                    add("EVENT")
                                    add(ackEvent.toJsonObject())
                                }.toString()
                                relayClients.forEach { try { it.send(ackMessage) } catch (_: Exception) {} }
                            } catch (e: Exception) {
                            }
                        }

                        val deferred = pendingRequests["_incoming_connect"]
                        deferred?.complete(eventPubkey)
                        return
                    }

                    // Handle signer connect response in nostrconnect:// flow.
                    // Result must equal the secret we generated (per NIP-46 spec).
                    val isConnectResponse = pendingRequests.containsKey("_incoming_connect") &&
                        nostrConnectSecret != null && result == nostrConnectSecret
                    if (isConnectResponse) {
                        remoteSignerPubkey = eventPubkey
                        pendingRequests["_incoming_connect"]?.complete(eventPubkey)
                        return
                    }

                    if (result == "auth_url" && error != null) {
                        onAuthUrl?.invoke(error)
                        return
                    }

                    if (remoteSignerPubkey == null) {
                        remoteSignerPubkey = eventPubkey
                    }

                    if (responseId == null) {
                        return
                    }
                    val deferred = pendingRequests[responseId]
                    if (deferred == null) {
                        return
                    }

                    if (!error.isNullOrBlank() && result != "auth_url") {
                        deferred.completeExceptionally(Exception(error))
                        return
                    }

                    deferred.complete(result ?: "")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun decryptMessage(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String {
        return if (ciphertext.contains("?iv=")) {
            Nip04.decrypt(ciphertext, privateKeyHex, pubKeyHex)
        } else {
            Nip44.decrypt(ciphertext, privateKeyHex, pubKeyHex)
        }
    }

    private fun generateRequestId(): String {
        return Random.nextBytes(16).joinToString("") {
            it.toUByte().toString(16).padStart(2, '0')
        }
    }

    actual fun backgroundConnect(secret: String?, onSuccess: (() -> Unit)?, onRevoked: (() -> Unit)?) {
        val signerPubkey = remoteSignerPubkey ?: return
        clientScope.launch {
            try {
                val requestId = generateRequestId()
                val params = buildList {
                    add(signerPubkey)
                    secret?.let { add(it) }
                }
                withTimeout(10_000) {
                    sendRequest(requestId, "connect", params)
                }
                onSuccess?.invoke()
            } catch (_: Exception) {
                // Timeout or explicit rejection → treat as revoked.
                onRevoked?.invoke()
            }
        }
    }

    actual fun disconnect() {
        clientScope.coroutineContext.cancelChildren()
        relayClients.forEach { client ->
            clientScope.launch {
                try { client.disconnect() } catch (_: Exception) {}
            }
        }
        relayClients.clear()
        pendingRequests.clear()
    }
}

private fun String.encodeForUri(): String = buildString {
    for (c in this@encodeForUri) {
        when {
            c.isLetterOrDigit() || c in "-_.~" -> append(c)
            else -> {
                for (b in c.toString().encodeToByteArray()) {
                    append('%')
                    append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
    }
}
