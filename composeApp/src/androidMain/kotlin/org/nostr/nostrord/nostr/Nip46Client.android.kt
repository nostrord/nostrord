package org.nostr.nostrord.nostr

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochMillis
import kotlin.random.Random

private const val TAG = "NIP46"

private fun log(msg: String) {
    println("[$TAG] $msg")
}

actual class Nip46Client actual constructor(existingPrivateKey: String?) {
    private val clientKeyPair: KeyPair = if (existingPrivateKey != null) {
        KeyPair.fromPrivateKeyHex(existingPrivateKey)
    } else {
        KeyPair.generate()
    }

    private var remoteSignerPubkey: String? = null
    private var relayClients: MutableList<NostrGroupClient> = mutableListOf()
    private var pendingRequests: MutableMap<String, CompletableDeferred<String>> = mutableMapOf()
    private var listenSubscriptionId: String? = null

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual fun generateNostrConnectUri(relays: List<String>, name: String): String {
        val relayParams = relays.joinToString("&") { "relay=${it.encodeForUri()}" }
        val metadata = """{"name":"$name"}"""
        val uri = "nostrconnect://${clientKeyPair.publicKeyHex}?$relayParams&metadata=${metadata.encodeForUri()}"
        log("generateNostrConnectUri: clientPubkey=${clientKeyPair.publicKeyHex.take(16)}...")
        log("generateNostrConnectUri: URI=$uri")
        return uri
    }

    actual suspend fun startListeningForConnection(relays: List<String>, secret: String?) {
        log("startListeningForConnection: relays=$relays, secret=$secret")

        for (relayUrl in relays) {
            try {
                val cleanUrl = relayUrl.trimEnd('/')
                log("startListening: connecting to relay $cleanUrl...")
                val client = NostrGroupClient(cleanUrl)
                client.connect { msg -> handleMessage(msg) }
                client.waitForConnection()
                relayClients.add(client)
                log("startListening: connected to $cleanUrl")
            } catch (e: Exception) {
                log("startListening: FAILED to connect to $relayUrl: ${e.message}")
            }
        }

        if (relayClients.isEmpty()) {
            log("startListening: FAILED - no relays connected")
            throw Exception("Failed to connect to any relay")
        }

        log("startListening: connected to ${relayClients.size} relay(s)")

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

        log("startListening: subscribing with filter: kinds=[24133], #p=[${clientKeyPair.publicKeyHex.take(16)}...], since=$since")
        log("startListening: sub message: $subMessage")

        relayClients.forEach { try { it.send(subMessage) } catch (e: Exception) {
            log("startListening: FAILED to send sub: ${e.message}")
        } }

        // Set up the deferred for the incoming connect
        pendingRequests["_incoming_connect"] = CompletableDeferred()
        log("startListening: deferred set up, now waiting for incoming connect event")
    }

    actual suspend fun awaitIncomingConnection(): String {
        log("awaitIncomingConnection: waiting (timeout=120s)...")
        val connectDeferred = pendingRequests["_incoming_connect"]
            ?: throw Exception("Not listening. Call startListeningForConnection first.")

        try {
            val result = withTimeout(120_000) { connectDeferred.await() }
            log("awaitIncomingConnection: SUCCESS, signerPubkey=${result.take(16)}...")
            return result
        } catch (e: Exception) {
            log("awaitIncomingConnection: FAILED: ${e::class.simpleName}: ${e.message}")
            throw e
        } finally {
            pendingRequests.remove("_incoming_connect")
            listenSubscriptionId?.let { subId ->
                val closeMessage = buildJsonArray { add("CLOSE"); add(subId) }.toString()
                relayClients.forEach { try { it.send(closeMessage) } catch (_: Exception) {} }
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

        for (relayUrl in relays) {
            try {
                val cleanUrl = relayUrl.trimEnd('/')
                val client = NostrGroupClient(cleanUrl)
                client.connect { msg -> handleMessage(msg) }
                client.waitForConnection()
                relayClients.add(client)
            } catch (e: Exception) {}
        }

        if (relayClients.isEmpty()) {
            throw Exception("Failed to connect to any bunker relay")
        }

        val requestId = generateRequestId()
        val params = buildList {
            add(remoteSignerPubkey)
            secret?.let { add(it) }
        }

        val response = sendRequest(requestId, "connect", params)

        if (response != "ack" && secret != null && response != secret) {
            throw Exception("Connect failed: unexpected response '$response'")
        }

        return remoteSignerPubkey
    }

    actual suspend fun getPublicKey(): String {
        log("getPublicKey: sending request...")
        val requestId = generateRequestId()
        val response = sendRequest(requestId, "get_public_key", emptyList())
        log("getPublicKey: got pubkey=${response.take(16)}...")
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

        log("sendRequest: method=$method, requestId=${requestId.take(8)}..., signerPubkey=${signerPubkey.take(16)}...")

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

        relayClients.forEach { client ->
            try { client.send(subMessage) } catch (_: Exception) {}
        }

        delay(100)

        val eventMessage = buildJsonArray {
            add("EVENT")
            add(signedEvent.toJsonObject())
        }.toString()

        relayClients.forEach { client ->
            try { client.send(eventMessage) } catch (_: Exception) {}
        }

        try {
            responseDeferred.await()
        } finally {
            pendingRequests.remove(requestId)
            val closeMessage = buildJsonArray {
                add("CLOSE")
                add(subscriptionId)
            }.toString()
            relayClients.forEach { try { it.send(closeMessage) } catch (_: Exception) {} }
        }
    }

    private fun handleMessage(msg: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

            log("handleMessage: type=$msgType, raw=${msg.take(200)}${if (msg.length > 200) "..." else ""}")

            if (msgType == "EOSE") {
                log("handleMessage: EOSE received (end of stored events)")
                return
            }

            if (msgType == "OK") {
                val eventId = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: "?"
                val accepted = arr.getOrNull(2)?.jsonPrimitive?.booleanOrNull ?: false
                val reason = arr.getOrNull(3)?.jsonPrimitive?.contentOrNull ?: ""
                log("handleMessage: OK eventId=${eventId.take(16)}... accepted=$accepted reason=$reason")
                return
            }

            if (msgType == "NOTICE") {
                val notice = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: ""
                log("handleMessage: NOTICE: $notice")
                return
            }

            if (msgType == "EVENT" && arr.size >= 3) {
                val eventObj = arr[2].jsonObject
                val kind = eventObj["kind"]?.jsonPrimitive?.int ?: return
                log("handleMessage: EVENT kind=$kind")

                if (kind != 24133) {
                    log("handleMessage: ignoring non-24133 event (kind=$kind)")
                    return
                }

                val eventPubkey = eventObj["pubkey"]?.jsonPrimitive?.content ?: return
                val encryptedContent = eventObj["content"]?.jsonPrimitive?.content ?: return

                log("handleMessage: kind=24133 from pubkey=${eventPubkey.take(16)}..., content length=${encryptedContent.length}")

                try {
                    val decrypted = decryptMessage(
                        ciphertext = encryptedContent,
                        privateKeyHex = clientKeyPair.privateKeyHex,
                        pubKeyHex = eventPubkey
                    )

                    log("handleMessage: decrypted: $decrypted")

                    val responseObj = json.parseToJsonElement(decrypted).jsonObject
                    val responseId = responseObj["id"]?.jsonPrimitive?.content
                    val method = responseObj["method"]?.jsonPrimitive?.contentOrNull
                    val result = responseObj["result"]?.jsonPrimitive?.contentOrNull
                    val error = responseObj["error"]?.jsonPrimitive?.contentOrNull

                    log("handleMessage: parsed -> id=$responseId, method=$method, result=${result?.take(32)}, error=$error")
                    log("handleMessage: pendingRequests keys: ${pendingRequests.keys}")

                    // Handle incoming connect request (nostrconnect:// flow)
                    if (method == "connect") {
                        log("handleMessage: incoming CONNECT from ${eventPubkey.take(16)}...")
                        remoteSignerPubkey = eventPubkey

                        val params = responseObj["params"]?.jsonArray
                        log("handleMessage: connect params=$params")

                        clientScope.launch {
                            try {
                                val ackResponse = buildJsonObject {
                                    responseId?.let { put("id", it) }
                                    put("result", "ack")
                                }.toString()
                                log("handleMessage: sending ack: $ackResponse")
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
                                log("handleMessage: ack sent successfully")
                            } catch (e: Exception) {
                                log("handleMessage: FAILED to send ack: ${e.message}")
                            }
                        }

                        val deferred = pendingRequests["_incoming_connect"]
                        log("handleMessage: _incoming_connect deferred exists=${deferred != null}, isCompleted=${deferred?.isCompleted}")
                        deferred?.complete(eventPubkey)
                        log("handleMessage: _incoming_connect completed with ${eventPubkey.take(16)}...")
                        return
                    }

                    // Handle signer ack response (some signers like Amber send ack directly
                    // without method:"connect", just {"id":..., "result":"ack"})
                    if (result == "ack" && pendingRequests.containsKey("_incoming_connect")) {
                        log("handleMessage: received direct ACK from ${eventPubkey.take(16)}..., completing _incoming_connect")
                        remoteSignerPubkey = eventPubkey
                        pendingRequests["_incoming_connect"]?.complete(eventPubkey)
                        return
                    }

                    if (result == "auth_url" && error != null) {
                        log("handleMessage: auth_url received: $error")
                        onAuthUrl?.invoke(error)
                        return
                    }

                    if (remoteSignerPubkey == null) {
                        remoteSignerPubkey = eventPubkey
                    }

                    if (responseId == null) {
                        log("handleMessage: no responseId, ignoring")
                        return
                    }
                    val deferred = pendingRequests[responseId]
                    if (deferred == null) {
                        log("handleMessage: no pending request for id=$responseId")
                        return
                    }

                    if (!error.isNullOrBlank() && result != "auth_url") {
                        log("handleMessage: completing with error: $error")
                        deferred.completeExceptionally(Exception(error))
                        return
                    }

                    log("handleMessage: completing request $responseId with result=${result?.take(32)}")
                    deferred.complete(result ?: "")
                } catch (e: Exception) {
                    log("handleMessage: EXCEPTION processing event: ${e::class.simpleName}: ${e.message}")
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            log("handleMessage: EXCEPTION parsing message: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun decryptMessage(ciphertext: String, privateKeyHex: String, pubKeyHex: String): String {
        return if (ciphertext.contains("?iv=")) {
            log("decryptMessage: using NIP-04")
            Nip04.decrypt(ciphertext, privateKeyHex, pubKeyHex)
        } else {
            log("decryptMessage: using NIP-44")
            Nip44.decrypt(ciphertext, privateKeyHex, pubKeyHex)
        }
    }

    private fun generateRequestId(): String {
        return Random.nextBytes(16).joinToString("") {
            it.toUByte().toString(16).padStart(2, '0')
        }
    }

    actual fun disconnect() {
        log("disconnect: cleaning up")
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
