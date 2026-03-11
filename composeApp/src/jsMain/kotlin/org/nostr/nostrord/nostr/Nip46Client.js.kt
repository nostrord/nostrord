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
    private var pendingRequests: MutableMap<String, CompletableDeferred<String>> = mutableMapOf()
    private var listenSubscriptionId: String? = null

    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual var onAuthUrl: ((String) -> Unit)? = null
    actual val clientPubkey: String get() = clientKeyPair.publicKeyHex
    actual val clientPrivateKey: String get() = clientKeyPair.privateKeyHex

    actual fun generateNostrConnectUri(relays: List<String>, name: String): String {
        val relayParams = relays.joinToString("&") { "relay=${it.encodeForUri()}" }
        val metadata = """{"name":"$name"}"""
        return "nostrconnect://${clientKeyPair.publicKeyHex}?$relayParams&metadata=${metadata.encodeForUri()}"
    }

    actual suspend fun startListeningForConnection(relays: List<String>, secret: String?) {
        for (relayUrl in relays) {
            try {
                val cleanUrl = relayUrl.trimEnd('/')
                val client = NostrGroupClient(cleanUrl)
                client.connect { msg -> handleMessage(msg) }
                client.waitForConnection()
                relayClients.add(client)
            } catch (_: Exception) {}
        }

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

        relayClients.forEach { try { it.send(subMessage) } catch (_: Exception) {} }

        pendingRequests["_incoming_connect"] = CompletableDeferred()
    }

    actual suspend fun awaitIncomingConnection(): String {
        val connectDeferred = pendingRequests["_incoming_connect"]
            ?: throw Exception("Not listening. Call startListeningForConnection first.")

        try {
            return withTimeout(120_000) { connectDeferred.await() }
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
        val signerPubkey = remoteSignerPubkey ?: throw Exception("Not connected to signer")

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

        relayClients.forEach { try { it.send(subMessage) } catch (_: Exception) {} }
        delay(100)

        val eventMessage = buildJsonArray {
            add("EVENT")
            add(signedEvent.toJsonObject())
        }.toString()

        relayClients.forEach { try { it.send(eventMessage) } catch (_: Exception) {} }

        try {
            responseDeferred.await()
        } finally {
            pendingRequests.remove(requestId)
            val closeMessage = buildJsonArray { add("CLOSE"); add(subscriptionId) }.toString()
            relayClients.forEach { try { it.send(closeMessage) } catch (_: Exception) {} }
        }
    }

    private fun handleMessage(msg: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(msg).jsonArray
            val msgType = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return

            if (msgType == "EVENT" && arr.size >= 3) {
                val eventObj = arr[2].jsonObject
                val kind = eventObj["kind"]?.jsonPrimitive?.int ?: return
                if (kind != 24133) return

                val eventPubkey = eventObj["pubkey"]?.jsonPrimitive?.content ?: return
                val encryptedContent = eventObj["content"]?.jsonPrimitive?.content ?: return

                try {
                    val decrypted = if (encryptedContent.contains("?iv=")) {
                        Nip04.decrypt(encryptedContent, clientKeyPair.privateKeyHex, eventPubkey)
                    } else {
                        Nip44.decrypt(encryptedContent, clientKeyPair.privateKeyHex, eventPubkey)
                    }

                    val responseObj = json.parseToJsonElement(decrypted).jsonObject
                    val responseId = responseObj["id"]?.jsonPrimitive?.content
                    val method = responseObj["method"]?.jsonPrimitive?.contentOrNull
                    val result = responseObj["result"]?.jsonPrimitive?.contentOrNull
                    val error = responseObj["error"]?.jsonPrimitive?.contentOrNull

                    // Handle incoming connect request (nostrconnect:// flow)
                    if (method == "connect") {
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
                            } catch (_: Exception) {}
                        }
                        pendingRequests["_incoming_connect"]?.complete(eventPubkey)
                        return
                    }

                    // Handle signer ack response (some signers send ack directly without method:"connect")
                    if (result == "ack" && pendingRequests.containsKey("_incoming_connect")) {
                        remoteSignerPubkey = eventPubkey
                        pendingRequests["_incoming_connect"]?.complete(eventPubkey)
                        return
                    }

                    if (result == "auth_url" && error != null) {
                        onAuthUrl?.invoke(error)
                        return
                    }

                    if (responseId == null) return
                    val deferred = pendingRequests[responseId] ?: return

                    if (!error.isNullOrBlank() && result != "auth_url") {
                        deferred.completeExceptionally(Exception(error))
                        return
                    }

                    deferred.complete(result ?: "")
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
    }

    private fun generateRequestId(): String {
        return Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
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
