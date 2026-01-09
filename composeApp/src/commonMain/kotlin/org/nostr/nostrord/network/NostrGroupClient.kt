package org.nostr.nostrord.network

import androidx.compose.runtime.Immutable
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.nostr.nostrord.utils.epochMillis

@Immutable
data class GroupMetadata(
    val id: String,
    val name: String?,
    val about: String?,
    val picture: String?,
    val isPublic: Boolean,
    val isOpen: Boolean
)

/**
 * Group members list from kind 39002 event.
 * Contains all pubkeys that are members of the group.
 */
@Immutable
data class GroupMembers(
    val groupId: String,
    val members: List<String> // List of pubkeys
)

@Immutable
data class UserMetadata(
    val pubkey: String,
    val name: String?,
    val displayName: String?,
    val picture: String?,
    val about: String?,
    val nip05: String?,
    val banner: String? = null
)

data class CachedEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val content: String,
    val createdAt: Long,
    val tags: List<List<String>>
)

class NostrGroupClient(
    private val relayUrl: String = "wss://groups.fiatjaf.com"
) {
    // Managed coroutine scope for this client - cancelled on disconnect
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val client = createHttpClient()
    private var session: DefaultClientWebSocketSession? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var connectionJob: Job? = null
    private val connectionReady = Channel<Unit>(Channel.CONFLATED)

    // NIP-42 AUTH callback - set this to handle AUTH challenges
    var onAuthChallenge: ((challenge: String) -> Unit)? = null

    // Connection lost callback - notifies when WebSocket disconnects unexpectedly
    var onConnectionLost: (() -> Unit)? = null

    // Track if this was a graceful disconnect
    private var isDisconnecting = false

    fun getRelayUrl(): String = relayUrl

    /**
     * Check if the client is currently connected
     */
    fun isConnected(): Boolean = session != null && connectionJob?.isActive == true

    /**
     * Parse NIP-42 AUTH challenge from relay
     * Returns the challenge string if this is an AUTH message, null otherwise
     */
    fun parseAuthChallenge(message: String): String? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size >= 2 && arr[0].jsonPrimitive.content == "AUTH") {
                arr[1].jsonPrimitive.content
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun connect(onMessage: (String) -> Unit) {
        isDisconnecting = false
        connectionJob = clientScope.launch {
            try {
                client.webSocket(relayUrl) {
                    session = this

                    // Signal that connection is ready
                    connectionReady.trySend(Unit)

                    // Listen to incoming messages
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            onMessage(text)
                        }
                    }
                }
            } catch (e: Exception) {
                // Connection error occurred
            } finally {
                val wasConnected = session != null
                session = null
                // Notify if connection was lost unexpectedly (not a graceful disconnect)
                if (wasConnected && !isDisconnecting) {
                    onConnectionLost?.invoke()
                }
            }
        }
    }

    suspend fun waitForConnection(timeoutMs: Long = 15000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            connectionReady.receive()
            true
        } ?: run {
            false
        }
    }

    suspend fun send(message: String) {
        try {
            val currentSession = session ?: run {
                return
            }
            currentSession.send(Frame.Text(message))
        } catch (e: Exception) {
        }
    }

    suspend fun sendAuth(privateKeyHex: String) {
        // TODO: Implement proper AUTH if needed by the relay
    }

    suspend fun requestGroups() {
        val req = buildJsonArray {
            add("REQ")
            add("group-list")
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                }
            )
        }
        sendJson(req)
    }

suspend fun requestGroupMessages(
    groupId: String,
    channel: String? = null,
    until: Long? = null,
    limit: Int = 50,
    subscriptionId: String? = null
): String {
    val subId = subscriptionId ?: "msg_${epochMillis()}"
    val subscription = buildJsonArray {
        add("REQ")
        add(subId)
        add(buildJsonObject {
            put("kinds", buildJsonArray {
                add(7)      // Reactions
                add(9)      // Messages
                add(9021)   // Joins
                add(9022)   // Leaves
            })
            put("#h", buildJsonArray {
                add(groupId)
            })
            // Only filter by channel if it's NOT "general"
            if (channel != null && channel != "general") {
                put("#channel", buildJsonArray {
                    add(channel)
                })
            }
            // Pagination: fetch messages before this timestamp
            if (until != null) {
                put("until", until)
            }
            put("limit", limit)
        })
    }.toString()

    send(subscription)
    return subId
}

    private suspend fun sendJson(jsonElement: JsonElement) {
        val currentSession = session ?: run {
            return
        }
        val text = json.encodeToString(JsonElement.serializer(), jsonElement)
        currentSession.send(Frame.Text(text))
    }

    fun parseGroupMetadata(message: String): GroupMetadata? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val event = arr[2].jsonObject
            if (event["kind"]?.jsonPrimitive?.int != 39000) return null

            val tags = event["tags"]?.jsonArray ?: return null

            // Build map for tags with values (size >= 2)
            val tagMap = tags
                .filter { it.jsonArray.size >= 2 }
                .associate { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }

            // Get all tag names (including presence-only tags like ["public"], ["open"])
            val tagNames = tags.map { it.jsonArray[0].jsonPrimitive.content }.toSet()

            GroupMetadata(
                id = tagMap["d"] ?: "unknown",
                name = tagMap["name"],
                about = tagMap["about"],
                picture = tagMap["picture"],
                isPublic = tagNames.contains("public"),
                isOpen = tagNames.contains("open")
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse kind 39002 group members event.
     * Returns GroupMembers containing all member pubkeys from "p" tags.
     */
    fun parseGroupMembers(message: String): GroupMembers? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val event = arr[2].jsonObject
            if (event["kind"]?.jsonPrimitive?.int != 39002) return null

            val tags = event["tags"]?.jsonArray ?: return null

            // Get group ID from "d" tag
            val groupId = tags
                .firstOrNull { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "d" }
                ?.jsonArray?.get(1)?.jsonPrimitive?.content
                ?: return null

            // Extract all pubkeys from "p" tags
            val members = tags
                .filter { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "p" }
                .map { it.jsonArray[1].jsonPrimitive.content }

            GroupMembers(
                groupId = groupId,
                members = members
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Request group members (kind 39002) for a specific group.
     */
    suspend fun requestGroupMembers(groupId: String): String {
        val subId = "members_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(buildJsonObject {
                putJsonArray("kinds") { add(39002) }
                put("#d", buildJsonArray { add(groupId) })
            })
        }
        sendJson(req)
        return subId
    }

    fun parseUserMetadata(message: String): Pair<String, UserMetadata>? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val event = arr[2].jsonObject
            if (event["kind"]?.jsonPrimitive?.int != 0) return null

            val pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = event["content"]?.jsonPrimitive?.content ?: "{}"
            
            val metadata = json.parseToJsonElement(content).jsonObject
            
            Pair(pubkey, UserMetadata(
                pubkey = pubkey,
                name = metadata["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                displayName = metadata["display_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                picture = metadata["picture"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                about = metadata["about"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                nip05 = metadata["nip05"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                banner = metadata["banner"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ))
        } catch (e: Exception) {
            null
        }
    }

    @Immutable
    data class NostrMessage(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>> = emptyList()
    )

    @Immutable
    data class NostrReaction(
        val id: String,
        val pubkey: String,
        val emoji: String,
        val emojiUrl: String? = null, // URL for custom emoji (NIP-30)
        val targetEventId: String,
        val createdAt: Long
    )

    fun parseMessage(message: String): NostrMessage? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val event = arr[2].jsonObject
            
            // Parse tags
            val tags = event["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()
            
            NostrMessage(
                id = event["id"]?.jsonPrimitive?.content ?: return null,
                pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return null,
                content = event["content"]?.jsonPrimitive?.content ?: "",
                createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L,
                kind = event["kind"]?.jsonPrimitive?.int ?: 0,
                tags = tags  // ADICIONAR ESTA LINHA
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a kind 7 reaction event
     * Returns NostrReaction if valid, null otherwise
     */
    fun parseReaction(message: String): NostrReaction? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val event = arr[2].jsonObject

            // Only parse kind 7 (reaction) events
            val kind = event["kind"]?.jsonPrimitive?.int ?: return null
            if (kind != 7) return null

            val tags = event["tags"]?.jsonArray?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList()

            // Find the "e" tag pointing to the target event
            val targetEventId = tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1) ?: return null

            // The emoji is in the content field (commonly "+", "-", or an actual emoji like ":LUL:")
            val emoji = event["content"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "+"

            // Extract custom emoji URL from NIP-30 emoji tag: ["emoji", "shortcode", "url"]
            // The shortcode in the tag should match the content without colons
            val shortcode = emoji.trim(':')
            val emojiUrl = tags.firstOrNull { tag ->
                tag.size >= 3 && tag[0] == "emoji" && tag[1] == shortcode
            }?.getOrNull(2)

            NostrReaction(
                id = event["id"]?.jsonPrimitive?.content ?: return null,
                pubkey = event["pubkey"]?.jsonPrimitive?.content ?: return null,
                emoji = emoji,
                emojiUrl = emojiUrl,
                targetEventId = targetEventId,
                createdAt = event["created_at"]?.jsonPrimitive?.long ?: 0L
            )
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun prettyPrintEvent(message: String): String {
        return try {
            val json = Json { 
                ignoreUnknownKeys = true
                prettyPrint = true
                prettyPrintIndent = "  "
            }
            val arr = json.parseToJsonElement(message).jsonArray
            
            when (arr[0].jsonPrimitive.content) {
                "EVENT" -> {
                    val event = arr[2].jsonObject
                    val kind = event["kind"]?.jsonPrimitive?.int
                    val pubkey = event["pubkey"]?.jsonPrimitive?.content?.take(8) ?: "unknown"
                    val content = event["content"]?.jsonPrimitive?.content?.take(50) ?: ""
                    
                    buildString {
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        appendLine("📨 EVENT Received")
                        appendLine("Type: kind $kind")
                        appendLine("From: $pubkey...")
                        when (kind) {
                            0 -> appendLine("Category: User Profile (metadata)")
                            9 -> appendLine("Category: Group Message")
                            9021 -> appendLine("Category: Join Group Request")
                            9022 -> appendLine("Category: Leave Group Request")
                            39000 -> appendLine("Category: Group Metadata")
                            else -> appendLine("Category: Unknown")
                        }
                        appendLine("Content: ${if (content.length > 50) content.take(50) + "..." else content}")
                        appendLine("Full JSON:")
                        appendLine(json.encodeToString(JsonElement.serializer(), arr))
                        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    }
                }
                "EOSE" -> "✅ End of Stored Events (subscription: ${arr.getOrNull(1)?.jsonPrimitive?.content})"
                "OK" -> {
                    val eventId = arr.getOrNull(1)?.jsonPrimitive?.content?.take(8)
                    val success = arr.getOrNull(2)?.jsonPrimitive?.boolean
                    val message = arr.getOrNull(3)?.jsonPrimitive?.content
                    "✓ OK Response: Event $eventId -> ${if (success == true) "✅ Success" else "❌ Failed"}: $message"
                }
                "NOTICE" -> "⚠️ NOTICE: ${arr.getOrNull(1)?.jsonPrimitive?.content}"
                "CLOSED" -> "🔒 CLOSED: Subscription ${arr.getOrNull(1)?.jsonPrimitive?.content} closed: ${arr.getOrNull(2)?.jsonPrimitive?.content}"
                "AUTH" -> "🔐 AUTH: Challenge received: ${arr.getOrNull(1)?.jsonPrimitive?.content?.take(16)}..."
                else -> "❓ Unknown message type: $message"
            }
        } catch (e: Exception) {
            "⚠️ Failed to parse: $message (${e.message})"
        }
    }

    suspend fun requestMetadata(pubkeys: List<String>) {
        val req = buildJsonArray {
            add("REQ")
            add("metadata_${epochMillis()}")
            add(buildJsonObject {
                putJsonArray("kinds") { add(0) } // kind 0 = metadata
                putJsonArray("authors") {
                    pubkeys.forEach { add(it) }
                }
            })
        }
        sendJson(req)
    }

    suspend fun requestEventById(eventId: String) {
        // Use short subscription ID - many relays limit to 64 chars
        val subId = "e_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(buildJsonObject {
                putJsonArray("ids") { add(eventId) }
            })
        }
        sendJson(req)
    }

    /**
     * Request an addressable event by its coordinates (kind, pubkey, d-tag).
     * Addressable events are parameterized replaceable events (kinds 30000-39999).
     */
    suspend fun requestAddressableEvent(kind: Int, pubkey: String, identifier: String) {
        // Use short subscription ID - many relays limit to 64 chars
        val subId = "a_${epochMillis()}"
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(buildJsonObject {
                putJsonArray("kinds") { add(kind) }
                putJsonArray("authors") { add(pubkey) }
                putJsonArray("#d") { add(identifier) }
                put("limit", 1)
            })
        }
        sendJson(req)
    }

    suspend fun disconnect() {
        // Mark as graceful disconnect to prevent onConnectionLost callback
        isDisconnecting = true
        // Cancel all managed coroutines
        clientScope.coroutineContext.cancelChildren()
        connectionJob?.cancel()
        session?.close()
        client.close()
    }
}
