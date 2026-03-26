package org.nostr.nostrord.network

import androidx.compose.runtime.Immutable
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.nostr.nostrord.utils.epochMillis

@Serializable
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

/**
 * Group admins list from kind 39001 event.
 * Contains pubkeys of group administrators.
 */
@Immutable
data class GroupAdmins(
    val groupId: String,
    val admins: List<String> // List of admin pubkeys
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

/**
 * Result of publishing an event to a relay.
 * Represents the relay's OK response per NIP-01.
 */
sealed class PublishResult {
    /** Event accepted by relay */
    data class Success(val eventId: String, val message: String?) : PublishResult()
    /** Event rejected by relay */
    data class Rejected(val eventId: String, val reason: String) : PublishResult()
    /** Timeout waiting for OK response */
    data class Timeout(val eventId: String) : PublishResult()
    /** Error during publish (network, etc.) */
    data class Error(val eventId: String, val exception: Exception) : PublishResult()
}

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

    // OK response callback - notifies when relay sends OK for published event
    var onOkResponse: ((eventId: String, success: Boolean, message: String?) -> Unit)? = null

    // Pending OK responses - maps event ID to CompletableDeferred for awaiting
    private val pendingOkResponses = mutableMapOf<String, CompletableDeferred<PublishResult>>()
    private val pendingOkMutex = Mutex()

    // Pending group creation confirmations - maps subscription ID to deferred GroupMetadata
    private val pendingGroupCreation = mutableMapOf<String, CompletableDeferred<GroupMetadata>>()
    private val pendingGroupCreationMutex = Mutex()

    // Track if this was a graceful disconnect
    private var isDisconnecting = false

    // Timestamp of the last WebSocket frame received — used by the heartbeat to detect
    // relays that stop sending events without closing the connection ("frozen" relays).
    private var lastMessageReceivedAt = 0L

    // Open subscription IDs tracked for diagnostic logging.
    // Updated in send() on every REQ/CLOSE. Not synchronised — count may be off by ±1
    // under concurrent sends, which is acceptable for logging purposes.
    private val openSubscriptions = mutableSetOf<String>()

    companion object {
        /** How often the heartbeat checks for stale connections. */
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 30_000L
        /** If no frame arrives within this window, the connection is considered frozen. */
        private const val HEARTBEAT_STALE_MS = 90_000L
    }

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
            val connectedAt = epochMillis()
            lastMessageReceivedAt = connectedAt
            try {
                client.webSocket(relayUrl) {
                    session = this
                    println("[WS] OPEN  relay=$relayUrl")

                    // Signal that connection is ready
                    connectionReady.trySend(Unit)

                    // Heartbeat: detect relays that stop sending without closing the WebSocket.
                    // Ktor handles WebSocket-level ping/pong, but some relays freeze at the
                    // application layer — the socket stays open but events stop arriving.
                    // If no frame arrives for HEARTBEAT_STALE_MS, close gracefully so that
                    // onConnectionLost fires and the reconnect loop re-establishes the session.
                    val wsSession = this
                    launch {
                        while (isActive) {
                            delay(HEARTBEAT_CHECK_INTERVAL_MS)
                            val idleMs = epochMillis() - lastMessageReceivedAt
                            if (idleMs > HEARTBEAT_STALE_MS) {
                                println("[WS] STALE  relay=$relayUrl  idle=${idleMs / 1000}s — closing for reconnect")
                                wsSession.close(CloseReason(CloseReason.Codes.GOING_AWAY, "heartbeat-stale"))
                                break
                            }
                        }
                    }

                    // Listen to incoming messages
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                lastMessageReceivedAt = epochMillis()
                                onMessage(frame.readText())
                            }
                            is Frame.Close -> {} // CLOSED event below captures code + reason
                            else -> {} // ping/pong handled by Ktor
                        }
                    }
                    val durationSec = (epochMillis() - connectedAt) / 1000
                    val reason = closeReason.await()
                    println("[WS] CLOSED  relay=$relayUrl  duration=${durationSec}s  code=${reason?.code}  reason='${reason?.message}'")
                }
            } catch (e: Exception) {
                val durationSec = (epochMillis() - connectedAt) / 1000
                // JobCancellationException = graceful disconnect or race-condition loser in
                // getOrConnectRelay (expected, not an error). Only log real failures.
                if (e is kotlinx.coroutines.CancellationException) {
                    if (!isDisconnecting) {
                        println("[WS] CANCELLED (unexpected)  relay=$relayUrl  duration=${durationSec}s")
                    }
                    // else: graceful disconnect — silent
                } else {
                    println("[WS] ERROR  relay=$relayUrl  duration=${durationSec}s  error=${e::class.simpleName}: ${e.message}")
                }
            } finally {
                val wasConnected = session != null
                session = null
                if (wasConnected && !isDisconnecting) {
                    println("[WS] LOST (unexpected)  relay=$relayUrl  hasLostCallback=${onConnectionLost != null}")
                    onConnectionLost?.invoke()
                } else if (!wasConnected && !isDisconnecting) {
                    println("[WS] FAILED-TO-OPEN  relay=$relayUrl")
                }
            }
        }
    }

    suspend fun waitForConnection(timeoutMs: Long = 7_000): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            connectionReady.receive()
            true
        } ?: run {
            false
        }
    }

    suspend fun send(message: String) {
        trackSubLifecycle(message)
        val currentSession = session ?: run {
            println("[WS] SEND-DROPPED  relay=$relayUrl  reason=no-session  msg=${message.take(80)}")
            throw IllegalStateException("Not connected to $relayUrl")
        }
        try {
            currentSession.send(Frame.Text(message))
        } catch (e: Exception) {
            println("[WS] SEND-ERROR  relay=$relayUrl  error=${e.message}  msg=${message.take(80)}")
            throw e  // Propagate so callers (GroupManager) can transition to Error state
        }
    }

    /**
     * Tracks open/closed subscriptions for slot-count diagnostics.
     * No per-sub logging here — mux subs are logged at the call site in sendMuxSubscriptions.
     */
    private fun trackSubLifecycle(message: String) {
        try {
            if (!message.startsWith("""["REQ"""") && !message.startsWith("""["CLOSE"""")) return
            val arr = json.parseToJsonElement(message).jsonArray
            val subId = arr[1].jsonPrimitive.content
            when (arr[0].jsonPrimitive.content) {
                "REQ" -> openSubscriptions.add(subId)
                "CLOSE" -> openSubscriptions.remove(subId)
            }
        } catch (_: Exception) {}
    }

    /**
     * Send an EVENT message and wait for OK response from relay.
     * @param eventJson The full EVENT message JSON (["EVENT", {...}])
     * @param eventId The event ID to track for OK response
     * @param timeoutMs Timeout in milliseconds to wait for OK
     * @return PublishResult indicating success, rejection, timeout, or error
     */
    suspend fun sendAndAwaitOk(
        eventJson: String,
        eventId: String,
        timeoutMs: Long = 10_000
    ): PublishResult {
        val deferred = CompletableDeferred<PublishResult>()

        // Register pending response
        pendingOkMutex.withLock {
            pendingOkResponses[eventId] = deferred
        }

        return try {
            // Send the event
            val currentSession = session ?: run {
                pendingOkMutex.withLock { pendingOkResponses.remove(eventId) }
                return PublishResult.Error(eventId, Exception("Not connected"))
            }
            currentSession.send(Frame.Text(eventJson))

            // Wait for OK with timeout
            withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            pendingOkMutex.withLock { pendingOkResponses.remove(eventId) }
            PublishResult.Timeout(eventId)
        } catch (e: Exception) {
            pendingOkMutex.withLock { pendingOkResponses.remove(eventId) }
            PublishResult.Error(eventId, e)
        }
    }

    /**
     * Parse an OK message from the relay.
     * Format: ["OK", <event_id>, <success>, <message>]
     * @return Triple of (eventId, success, message) or null if not an OK message
     */
    fun parseOkMessage(message: String): Triple<String, Boolean, String?>? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size >= 3 && arr[0].jsonPrimitive.content == "OK") {
                val eventId = arr[1].jsonPrimitive.content
                val success = arr[2].jsonPrimitive.boolean
                val okMessage = arr.getOrNull(3)?.jsonPrimitive?.contentOrNull
                Triple(eventId, success, okMessage)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Handle an OK response from the relay.
     * This should be called when an OK message is received.
     * Completes any pending deferred for this event ID.
     */
    suspend fun handleOkResponse(eventId: String, success: Boolean, message: String?) {
        // Notify callback
        onOkResponse?.invoke(eventId, success, message)

        // Complete pending deferred
        val deferred = pendingOkMutex.withLock {
            pendingOkResponses.remove(eventId)
        }

        if (deferred != null) {
            val result = if (success) {
                PublishResult.Success(eventId, message)
            } else {
                PublishResult.Rejected(eventId, message ?: "Rejected by relay")
            }
            deferred.complete(result)
        }
    }

    /**
     * Parse a kind:39000 message and return (subscriptionId, GroupMetadata).
     * Used so the subscription ID is available alongside the metadata.
     */
    fun parseGroupMetadataWithSubId(message: String): Pair<String, GroupMetadata>? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val subId = arr[1].jsonPrimitive.content
            val metadata = parseGroupMetadata(message) ?: return null
            Pair(subId, metadata)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Complete a pending group creation deferred when kind:39000 arrives
     * for the matching subscription ID.
     */
    suspend fun handleGroupCreationEvent(subId: String, metadata: GroupMetadata) {
        val deferred = pendingGroupCreationMutex.withLock {
            pendingGroupCreation[subId]
        }
        deferred?.complete(metadata)
    }

    /**
     * Send kind:9007 (create-group), await relay OK, then subscribe for kind:39000
     * to discover the relay-confirmed group ID before returning.
     *
     * @param create9007EventJson Full ["EVENT", {...}] JSON for the 9007 event
     * @param create9007EventId   The 9007 event ID (for tracking the OK response)
     * @param suggestedGroupId    The `d` tag value we suggested in the 9007 event
     * @param timeoutMs           Total timeout split: half for OK, half for 39000
     * @return The relay-confirmed group ID, or [suggestedGroupId] as fallback
     */
    suspend fun awaitGroupCreated(
        create9007EventJson: String,
        create9007EventId: String,
        suggestedGroupId: String,
        timeoutMs: Long = 15_000
    ): String {
        val halfTimeout = timeoutMs / 2

        // Step 1: send 9007 and wait for relay OK
        val okResult = sendAndAwaitOk(create9007EventJson, create9007EventId, halfTimeout)
        when (okResult) {
            is PublishResult.Rejected -> throw Exception("Group creation rejected: ${okResult.reason}")
            is PublishResult.Timeout -> throw Exception("Relay did not respond in time. Try again.")
            is PublishResult.Error -> throw Exception("Relay error: ${okResult.exception.message}")
            else -> Unit // Success — continue
        }

        // Step 2: subscribe for kind:39000 with #d = suggestedGroupId
        val subId = "gc_${epochMillis()}"
        val deferred = CompletableDeferred<GroupMetadata>()
        pendingGroupCreationMutex.withLock {
            pendingGroupCreation[subId] = deferred
        }

        return try {
            send(buildJsonArray {
                add("REQ")
                add(subId)
                add(buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                    putJsonArray("#d") { add(suggestedGroupId) }
                })
            }.toString())

            // Step 3: wait for the 39000 event, fall back to suggestedGroupId on timeout
            val confirmedId = withTimeoutOrNull(halfTimeout) {
                deferred.await().id
            }

            // Close the temporary subscription
            send(buildJsonArray { add("CLOSE"); add(subId) }.toString())

            confirmedId ?: suggestedGroupId
        } finally {
            pendingGroupCreationMutex.withLock {
                pendingGroupCreation.remove(subId)
            }
        }
    }

    /**
     * Clear all pending OK responses (called on disconnect).
     */
    private suspend fun clearPendingOkResponses() {
        pendingOkMutex.withLock {
            pendingOkResponses.values.forEach { deferred ->
                deferred.complete(PublishResult.Error("", Exception("Disconnected")))
            }
            pendingOkResponses.clear()
        }
    }

    suspend fun sendAuth(privateKeyHex: String) {
        // TODO: Implement proper AUTH if needed by the relay
    }

    suspend fun requestGroups() {
        // Use a relay-specific subscription ID to avoid collisions when multiple
        // relay clients are active concurrently. The ID must be stable per relay
        // so the EOSE handler can map it back to the originating relay URL.
        val subId = "group-list-${relayUrl.hashCode().toUInt()}"
        println("[Groups] REQ  relay=$relayUrl")
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(
                buildJsonObject {
                    putJsonArray("kinds") { add(39000) }
                }
            )
        }
        sendJson(req)
    }

    /**
     * Returns the subscription ID that requestGroups() uses for this relay.
     * Used by callers (e.g., EOSE handler) to identify the originating relay.
     */
    fun groupListSubscriptionId(): String = "group-list-${relayUrl.hashCode().toUInt()}"

    /**
     * Subscribe for kind:39000 metadata for a specific group.
     * Called when entering a group screen to ensure metadata is fresh.
     *
     * Uses a short deterministic sub ID (same pattern as admins/members) so repeated
     * calls reuse the same relay slot instead of leaking a new subscription each time.
     * Sends CLOSE before REQ so the relay resets and re-delivers the latest state.
     * Returns the sub ID so the caller can send CLOSE after EOSE.
     */
    suspend fun requestGroupMetadata(groupId: String): String {
        val subId = "meta_${groupId.take(8)}"
        send(buildJsonArray { add("CLOSE"); add(subId) }.toString())
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(buildJsonObject {
                putJsonArray("kinds") { add(39000) }
                put("#d", buildJsonArray { add(groupId) })
            })
        }
        sendJson(req)
        return subId
    }

suspend fun requestGroupMessages(
    groupId: String,
    channel: String? = null,
    until: Long? = null,
    limit: Int = 50,
    subscriptionId: String? = null
): String {
    val subId = subscriptionId ?: "msg_${epochMillis()}"

    // CHAT + ADMIN subscription: kinds that are paginated and count against the limit.
    // Reactions (kind 7) and zaps (kind 9321) are covered by the relay-level mux_reactions sub
    // so they are intentionally omitted here to preserve the per-group limit budget.
    val subscription = buildJsonArray {
        add("REQ")
        add(subId)
        add(buildJsonObject {
            put("kinds", buildJsonArray {
                add(5)      // Deletion requests (NIP-09)
                add(9)      // Chat messages (NIP-29)
                add(9000)   // Group admin: add user (NIP-29)
                add(9001)   // Group admin: remove user (NIP-29)
                add(9002)   // Group admin: edit metadata (NIP-29)
                add(9003)   // Group admin: delete event (NIP-29)
                add(9021)   // Join request
                add(9022)   // Leave request
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

    send(subscription)  // may throw — caller (GroupManager) catches and handles via state machine
    return subId
}

/**
 * Deterministic sub ID for the relay-level multiplexed chat subscription.
 * Using the relay URL hash makes it stable across reconnects so the relay doesn't
 * accumulate duplicate slots.
 */
fun muxChatSubId(): String = "mux_chat_${relayUrl.hashCode().toUInt()}"

/**
 * Deterministic sub ID for the relay-level multiplexed reactions subscription.
 */
fun muxReactionsSubId(): String = "mux_reactions_${relayUrl.hashCode().toUInt()}"

/**
 * Deterministic sub ID for the relay-level multiplexed group-metadata subscription.
 */
fun muxMetaSubId(): String = "mux_meta_${relayUrl.hashCode().toUInt()}"

/**
 * Send (or refresh) the three relay-level multiplexed subscriptions.
 *
 * Replaces per-group `live_<id>` + `reactions_<id>` with three relay-scoped REQs that cover
 * ALL joined/loaded groups on this relay simultaneously.  Benefits:
 *
 * - N×3 per-group subs → 3 per-relay subs regardless of group count
 * - A single reconnect re-subscribes everything with one round-trip
 * - `since = min(cursors)` ensures no group misses events during the offline window
 *
 * Idempotent: sends CLOSE before REQ so calling it multiple times is safe.
 *
 * @param groupIds     All group IDs on this relay to include in the filter.
 * @param sinceSeconds Unix-seconds `since` value (use min cursor across all groups).
 */
suspend fun sendMuxSubscriptions(groupIds: List<String>, sinceSeconds: Long) {
    if (groupIds.isEmpty()) return
    val chatSubId = muxChatSubId()
    val reactSubId = muxReactionsSubId()
    val metaSubId = muxMetaSubId()

    // Close the previous mux slots first (idempotent — no-op if not open).
    send(buildJsonArray { add("CLOSE"); add(chatSubId) }.toString())
    send(buildJsonArray { add("CLOSE"); add(reactSubId) }.toString())
    send(buildJsonArray { add("CLOSE"); add(metaSubId) }.toString())

    // Chat + admin events for all groups on this relay.
    send(buildJsonArray {
        add("REQ"); add(chatSubId)
        add(buildJsonObject {
            putJsonArray("kinds") {
                add(5); add(9); add(9000); add(9001); add(9002); add(9003); add(9021); add(9022)
            }
            putJsonArray("#h") { groupIds.forEach { add(it) } }
            put("since", sinceSeconds)
        })
    }.toString())

    // Reactions / zaps for all groups on this relay.
    send(buildJsonArray {
        add("REQ"); add(reactSubId)
        add(buildJsonObject {
            putJsonArray("kinds") { add(7); add(9321) }
            putJsonArray("#h") { groupIds.forEach { add(it) } }
            put("since", sinceSeconds)
        })
    }.toString())

    // Group metadata (kind 39000) for all groups on this relay.
    // Uses the same `since` as chat/reactions — kind 39000 is addressable so relays
    // always serve the latest version regardless; `since` just avoids re-delivering
    // metadata that hasn't changed since the last refresh.
    send(buildJsonArray {
        add("REQ"); add(metaSubId)
        add(buildJsonObject {
            putJsonArray("kinds") { add(39000) }
            putJsonArray("#h") { groupIds.forEach { add(it) } }
            put("since", sinceSeconds)
        })
    }.toString())
    println("[Mux] OPEN  relay=$relayUrl  groups=${groupIds.size}  since=$sinceSeconds  subs=${openSubscriptions.size}")
}

/**
 * Superseded by [sendMuxSubscriptions] — kept as a single-group fallback.
 * @param sinceSeconds  Cursor-based Unix-seconds since value from [LiveCursorStore].
 */
suspend fun sendLiveSubscription(groupId: String, sinceSeconds: Long? = null) {
    val subId = "live_${groupId.take(8)}"
    val since = sinceSeconds ?: (epochMillis() / 1000 - 60)
    send(buildJsonArray { add("CLOSE"); add(subId) }.toString())
    send(buildJsonArray {
        add("REQ")
        add(subId)
        add(buildJsonObject {
            putJsonArray("kinds") {
                add(5); add(9); add(9000); add(9001); add(9002); add(9003); add(9021); add(9022)
            }
            put("#h", buildJsonArray { add(groupId) })
            put("since", since)
        })
    }.toString())
}

    private suspend fun sendJson(jsonElement: JsonElement) {
        val text = json.encodeToString(JsonElement.serializer(), jsonElement)
        try {
            send(text)
        } catch (_: Exception) {
            // sendJson is used for fire-and-forget subscriptions (group list, metadata, members).
            // Failures here are non-fatal — the caller will retry on next reconnect.
        }
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
                isPublic = !tagNames.contains("private"),
                isOpen = !tagNames.contains("closed")
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
     * Parse kind 39001 group admins event.
     * Returns GroupAdmins containing all admin pubkeys from "p" tags.
     */
    fun parseGroupAdmins(message: String): GroupAdmins? {
        return try {
            val arr = json.parseToJsonElement(message).jsonArray
            if (arr.size < 3 || arr[0].jsonPrimitive.content != "EVENT") return null
            val event = arr[2].jsonObject
            if (event["kind"]?.jsonPrimitive?.int != 39001) return null

            val tags = event["tags"]?.jsonArray ?: return null

            val groupId = tags
                .firstOrNull { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "d" }
                ?.jsonArray?.get(1)?.jsonPrimitive?.content
                ?: return null

            val admins = tags
                .filter { it.jsonArray.size >= 2 && it.jsonArray[0].jsonPrimitive.content == "p" }
                .map { it.jsonArray[1].jsonPrimitive.content }

            GroupAdmins(groupId = groupId, admins = admins)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Request group admins (kind 39001) for a specific group.
     *
     * Uses a deterministic subscription ID so repeated calls reuse the same relay slot
     * instead of leaking a new subscription each time. Without this, 5 groups × 4
     * reconnects = 20 admin subscriptions — most relays cap at 20–30 total.
     */
    suspend fun requestGroupAdmins(groupId: String): String {
        val subId = "admins_${groupId.take(8)}"
        // Close any existing subscription for this group before re-opening to signal
        // to the relay that it should reset and re-send the latest state.
        send(buildJsonArray { add("CLOSE"); add(subId) }.toString())
        val req = buildJsonArray {
            add("REQ")
            add(subId)
            add(buildJsonObject {
                putJsonArray("kinds") { add(39001) }
                put("#d", buildJsonArray { add(groupId) })
            })
        }
        sendJson(req)
        return subId
    }

    /**
     * Request group members (kind 39002) for a specific group.
     *
     * Same deterministic ID rationale as requestGroupAdmins.
     */
    suspend fun requestGroupMembers(groupId: String): String {
        val subId = "members_${groupId.take(8)}"
        // Close existing subscription before re-opening.
        send(buildJsonArray { add("CLOSE"); add(subId) }.toString())
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
                // Use current time if created_at is missing to avoid sorting issues
                createdAt = event["created_at"]?.jsonPrimitive?.long ?: (epochMillis() / 1000),
                kind = event["kind"]?.jsonPrimitive?.int ?: 0,
                tags = tags
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
                // Use current time if created_at is missing
                createdAt = event["created_at"]?.jsonPrimitive?.long ?: (epochMillis() / 1000)
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
                            5 -> appendLine("Category: Deletion Request (NIP-09)")
                            7 -> appendLine("Category: Reaction")
                            9 -> appendLine("Category: Group Message (NIP-29)")
                            9000 -> appendLine("Category: Admin Add User (NIP-29)")
                            9001 -> appendLine("Category: Admin Remove User (NIP-29)")
                            9005 -> appendLine("Category: Admin Delete Event (NIP-29)")
                            9008 -> appendLine("Category: Admin Edit Metadata (NIP-29)")
                            9021 -> appendLine("Category: Join Group Request")
                            9022 -> appendLine("Category: Leave Group Request")
                            9321 -> appendLine("Category: Zap Request (NIP-57)")
                            30382 -> appendLine("Category: Group List (NIP-51)")
                            39000 -> appendLine("Category: Group Metadata")
                            39002 -> appendLine("Category: Group Members")
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
        if (pubkeys.isEmpty()) return
        val subId = "metadata_${pubkeys.first().take(8)}"
        // CLOSE any previous subscription for this pubkey before re-subscribing
        sendJson(buildJsonArray { add("CLOSE"); add(subId) })
        val req = buildJsonArray {
            add("REQ")
            add(subId)
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
        // Clear pending OK responses before disconnect
        clearPendingOkResponses()
        // Cancel all managed coroutines
        clientScope.coroutineContext.cancelChildren()
        connectionJob?.cancel()
        session?.close()
        client.close()
    }

    /**
     * Non-graceful cleanup for abandoned clients (timeout, error).
     * Closes the HttpClient and cancels coroutines WITHOUT touching session
     * (which may be null or mid-close). Does NOT send any frames.
     * Call this instead of disconnect() when the connection was never established
     * or when orphaning a client after a concurrent connect succeeded first.
     */
    fun cancelAndClose() {
        onConnectionLost = null   // prevent any further callbacks
        isDisconnecting = true
        connectionJob?.cancel()
        clientScope.coroutineContext.cancelChildren()
        try { client.close() } catch (_: Exception) {}
    }
}
