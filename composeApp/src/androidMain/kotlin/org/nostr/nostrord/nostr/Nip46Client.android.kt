package org.nostr.nostrord.nostr

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.PublishResult
import org.nostr.nostrord.utils.epochMillis
import kotlin.random.Random

actual class Nip46Client actual constructor(
    existingPrivateKey: String?,
) {
    private val clientKeyPair: KeyPair =
        if (existingPrivateKey != null) {
            KeyPair.fromPrivateKeyHex(existingPrivateKey)
        } else {
            KeyPair.generate()
        }

    private var remoteSignerPubkey: String? = null
    private var relayClients: MutableList<NostrGroupClient> = mutableListOf()
    private var relayUrls: List<String> = emptyList()
    private val pendingRequests: MutableMap<String, CompletableDeferred<String>> = java.util.concurrent.ConcurrentHashMap()
    private var responseSubscriptionId: String? = null
    private var nostrConnectSecret: String? = null

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nip46Json = Json { ignoreUnknownKeys = true }

    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual fun generateNostrConnectUri(
        relays: List<String>,
        name: String,
    ): String {
        val relayParams = relays.joinToString("&") { "relay=${it.encodeForUri()}" }
        val metadata = """{"name":"$name"}"""
        val secretParam = nostrConnectSecret?.let { "&secret=${it.encodeForUri()}" } ?: ""
        val uri = "nostrconnect://${clientKeyPair.publicKeyHex}?$relayParams$secretParam&metadata=${metadata.encodeForUri()}"
        return uri
    }

    private suspend fun connectRelaysParallel(relays: List<String>): List<NostrGroupClient> = coroutineScope {
        relays
            .map { relayUrl ->
                async {
                    try {
                        val cleanUrl = relayUrl.trimEnd('/')
                        val client = NostrGroupClient(cleanUrl)
                        client.connect { msg -> handleMessage(msg, client) }
                        client.waitForConnection()
                        openResponseSubscription(client)
                        client
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
            .filterNotNull()
    }

    /**
     * Opens the durable NIP-46 response subscription on [client]. The filter
     * (`kinds:[24133], #p:[clientPubkey]`) covers both incoming `connect`
     * requests (nostrconnect:// flow) and signer replies (bunker:// flow), so
     * one stable subscription replaces the previous per-request ephemeral REQs.
     */
    private suspend fun openResponseSubscription(client: NostrGroupClient) {
        val subId = responseSubscriptionId
            ?: "nip46-resp-${clientKeyPair.publicKeyHex.take(8)}".also { responseSubscriptionId = it }
        val since = (epochMillis() / 1000) - 10
        val filter = buildJsonObject {
            putJsonArray("kinds") { add(24133) }
            putJsonArray("#p") { add(clientKeyPair.publicKeyHex) }
            put("since", since)
        }
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(filter)
        }.toString()
        try {
            client.send(req)
        } catch (_: Exception) {
        }
    }

    private suspend fun ensureRelaysConnected() {
        val dead = relayClients.filter { !it.isConnected() }
        if (dead.isNotEmpty()) {
            dead.forEach {
                try {
                    it.disconnect()
                } catch (_: Exception) {
                }
            }
            relayClients.removeAll(dead)
        }
        if (relayClients.isNotEmpty()) return
        if (relayUrls.isEmpty()) return
        relayClients.addAll(connectRelaysParallel(relayUrls))
    }

    actual suspend fun connectRelaysOnly(
        remoteSignerPubkey: String,
        relays: List<String>,
    ) {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }
        relayClients.addAll(connectRelaysParallel(relays))
        if (relayClients.isEmpty()) throw Exception("Failed to connect to any bunker relay")
    }

    actual suspend fun startListeningForConnection(
        relays: List<String>,
        secret: String?,
    ) {
        nostrConnectSecret = secret ?: generateRequestId().take(16)
        this.relayUrls = relays.map { it.trimEnd('/') }

        relayClients.addAll(connectRelaysParallel(relays))

        if (relayClients.isEmpty()) {
            throw Exception("Failed to connect to any relay")
        }
        // The durable response subscription opened in connectRelaysParallel
        // already filters kind:24133 #p:clientPubkey, so it doubles as the
        // nostrconnect listening sub — no extra REQ needed here.

        pendingRequests["_incoming_connect"] = CompletableDeferred()
    }

    actual suspend fun awaitIncomingConnection(): String {
        val connectDeferred =
            pendingRequests["_incoming_connect"]
                ?: throw Exception("Not listening. Call startListeningForConnection first.")

        return try {
            // No wall-clock timeout: the QR sheet lifecycle drives cancellation
            // via the calling coroutine. The listen subscription stays open
            // until disconnect(), so a late scanner still completes instead of
            // being killed by a 120s deadline.
            connectDeferred.await()
        } finally {
            pendingRequests.remove("_incoming_connect")
        }
    }

    actual suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?,
    ): String {
        this.remoteSignerPubkey = remoteSignerPubkey
        this.relayUrls = relays.map { it.trimEnd('/') }

        relayClients.addAll(connectRelaysParallel(relays))

        if (relayClients.isEmpty()) {
            throw Exception("Failed to connect to any bunker relay")
        }

        val requestId = generateRequestId()
        val params =
            buildList {
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
        params: List<String>,
    ): String = withTimeout(120_000) {
        val signerPubkey =
            remoteSignerPubkey
                ?: throw Exception("Not connected to signer")

        ensureRelaysConnected()
        if (relayClients.isEmpty()) {
            throw Exception("No bunker relay connections available")
        }

        val requestJson =
            buildJsonObject {
                put("id", requestId)
                put("method", method)
                putJsonArray("params") { params.forEach { add(it) } }
            }.toString()

        val encryptedContent =
            Nip44.encrypt(
                plaintext = requestJson,
                privateKeyHex = clientKeyPair.privateKeyHex,
                pubKeyHex = signerPubkey,
            )

        val event =
            Event(
                pubkey = clientKeyPair.publicKeyHex,
                createdAt = epochMillis() / 1000,
                kind = 24133,
                tags = listOf(listOf("p", signerPubkey)),
                content = encryptedContent,
            )

        val signedEvent = event.sign(clientKeyPair)
        val eventId = signedEvent.id
            ?: throw Exception("Failed to sign NIP-46 request event")
        val responseDeferred = CompletableDeferred<String>()
        pendingRequests[requestId] = responseDeferred

        val eventMessage =
            buildJsonArray {
                add("EVENT")
                add(signedEvent.toJsonObject())
            }.toString()

        try {
            // Publish in parallel via sendAndAwaitOk so a relay-side rejection
            // surfaces immediately. The response sub opened on connect routes
            // the signer's reply back into responseDeferred.
            val publishResults = relayClients.map { client ->
                async {
                    try {
                        client.sendAndAwaitOk(eventMessage, eventId)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        PublishResult.Error(eventId, e)
                    }
                }
            }.awaitAll()
            if (publishResults.none { it is PublishResult.Success }) {
                val reason = publishResults.firstNotNullOfOrNull { r ->
                    when (r) {
                        is PublishResult.Rejected -> r.reason
                        is PublishResult.Error -> r.exception.message
                        is PublishResult.Timeout -> "publish timeout"
                        else -> null
                    }
                } ?: "no relay accepted request"
                throw Exception("Failed to publish NIP-46 request: $reason")
            }
            responseDeferred.await()
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    private fun handleMessage(msg: String, source: NostrGroupClient) {
        try {
            val json = nip46Json
            val arr = json.parseToJsonElement(msg).jsonArray
            val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

            when (msgType) {
                "OK" -> {
                    // Route relay's publish ACK into the source client so that
                    // sendAndAwaitOk completes with the actual relay verdict
                    // instead of timing out. OK false → PublishResult.Rejected.
                    val parsed = source.parseOkMessage(arr) ?: return
                    val (eventId, success, message) = parsed
                    clientScope.launch { source.handleOkResponse(eventId, success, message) }
                    return
                }
                "NOTICE" -> {
                    val notice = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull
                    if (!notice.isNullOrBlank()) {
                        println("[Nip46Client] NOTICE from ${source.getRelayUrl()}: $notice")
                    }
                    return
                }
                "EOSE" -> {
                    // Catch-up boundary for the listening sub. No state to
                    // advance yet, but the frame is no longer silently dropped.
                    return
                }
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
                    val decrypted =
                        decryptMessage(
                            ciphertext = encryptedContent,
                            privateKeyHex = clientKeyPair.privateKeyHex,
                            pubKeyHex = eventPubkey,
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
                                val ackResponse =
                                    buildJsonObject {
                                        responseId?.let { put("id", it) }
                                        put("result", "ack")
                                    }.toString()
                                val ackEncrypted = Nip44.encrypt(ackResponse, clientKeyPair.privateKeyHex, eventPubkey)
                                val ackEvent =
                                    Event(
                                        pubkey = clientKeyPair.publicKeyHex,
                                        createdAt = epochMillis() / 1000,
                                        kind = 24133,
                                        tags = listOf(listOf("p", eventPubkey)),
                                        content = ackEncrypted,
                                    ).sign(clientKeyPair)
                                val ackMessage =
                                    buildJsonArray {
                                        add("EVENT")
                                        add(ackEvent.toJsonObject())
                                    }.toString()
                                relayClients.forEach {
                                    try {
                                        it.send(ackMessage)
                                    } catch (_: Exception) {
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }

                        val deferred = pendingRequests["_incoming_connect"]
                        deferred?.complete(eventPubkey)
                        return
                    }

                    // Handle signer connect response in nostrconnect:// flow.
                    // Result must equal the secret we generated (per NIP-46 spec).
                    val isConnectResponse =
                        pendingRequests.containsKey("_incoming_connect") &&
                            nostrConnectSecret != null &&
                            result == nostrConnectSecret
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

    private fun decryptMessage(
        ciphertext: String,
        privateKeyHex: String,
        pubKeyHex: String,
    ): String = if (ciphertext.contains("?iv=")) {
        Nip04.decrypt(ciphertext, privateKeyHex, pubKeyHex)
    } else {
        Nip44.decrypt(ciphertext, privateKeyHex, pubKeyHex)
    }

    private fun generateRequestId(): String = Random.nextBytes(16).joinToString("") {
        it.toUByte().toString(16).padStart(2, '0')
    }

    actual fun backgroundConnect(
        secret: String?,
        onSuccess: (() -> Unit)?,
        onRevoked: (() -> Unit)?,
    ) {
        val signerPubkey = remoteSignerPubkey ?: return
        clientScope.launch {
            try {
                val requestId = generateRequestId()
                val params =
                    buildList {
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
                try {
                    client.disconnect()
                } catch (_: Exception) {
                }
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
