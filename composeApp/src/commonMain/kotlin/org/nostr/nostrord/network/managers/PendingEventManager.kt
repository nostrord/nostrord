package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.PublishResult
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.utils.epochMillis

/**
 * Represents a pending event waiting to be sent to the relay.
 */
data class PendingEvent(
    val id: String,
    val eventJson: String,
    val eventId: String,
    val groupId: String,
    val createdAt: Long = epochMillis(),
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val lastError: String? = null,
) {
    val canRetry: Boolean get() = retryCount < maxRetries
}

/**
 * Status of a pending event.
 */
sealed class PendingEventStatus {
    data object Queued : PendingEventStatus()

    data object Sending : PendingEventStatus()

    data class Sent(
        val result: PublishResult,
    ) : PendingEventStatus()

    data class Failed(
        val reason: String,
        val canRetry: Boolean,
    ) : PendingEventStatus()
}

/**
 * Manages events that failed to send or were queued while offline.
 * Automatically retries when connection is restored.
 *
 * Features:
 * - Queue events when offline
 * - Auto-retry with exponential backoff
 * - Max retry limit per event
 * - Status tracking for UI feedback
 */
class PendingEventManager(
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MAX_QUEUE_SIZE = 100
        const val BASE_RETRY_DELAY_MS = 1000L
        const val MAX_RETRY_DELAY_MS = 30_000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var currentPubkey: String? = null

    /**
     * Set the current user pubkey for storage scoping.
     */
    fun setCurrentPubkey(pubkey: String?) {
        currentPubkey = pubkey
        // Load persisted events when pubkey is set
        if (pubkey != null) {
            loadFromStorage()
        }
    }

    // In-memory queue of pending events
    private val _pendingEvents = MutableStateFlow<List<PendingEvent>>(emptyList())
    val pendingEvents: StateFlow<List<PendingEvent>> = _pendingEvents.asStateFlow()

    // Status for each pending event
    private val _eventStatuses = MutableStateFlow<Map<String, PendingEventStatus>>(emptyMap())
    val eventStatuses: StateFlow<Map<String, PendingEventStatus>> = _eventStatuses.asStateFlow()

    // Whether retry is currently in progress
    private val _isRetrying = MutableStateFlow(false)
    val isRetrying: StateFlow<Boolean> = _isRetrying.asStateFlow()

    /**
     * Terminal-outcome hooks for optimistic send. [onEventDelivered] fires when a
     * queued event is finally accepted by the relay; [onEventPermanentlyFailed]
     * fires when it is rejected or its retries are exhausted. Both are keyed by the
     * nostr event id so the owner (GroupManager) can resolve the on-screen status.
     */
    var onEventDelivered: ((eventId: String) -> Unit)? = null
    var onEventPermanentlyFailed: ((eventId: String, groupId: String, eventJson: String, reason: String) -> Unit)? = null

    /**
     * Queue an event for later sending.
     * Called when send fails due to offline or network error.
     *
     * @return true if event was queued, false if queue is full
     */
    suspend fun queueEvent(
        eventJson: String,
        eventId: String,
        groupId: String,
    ): Boolean {
        return mutex.withLock {
            val current = _pendingEvents.value

            // Prevent duplicate events
            if (current.any { it.eventId == eventId }) {
                return@withLock false
            }

            // Enforce queue size limit
            if (current.size >= MAX_QUEUE_SIZE) {
                // Remove oldest event to make room
                val oldest = current.minByOrNull { it.createdAt }
                if (oldest != null) {
                    _pendingEvents.value = current - oldest
                    _eventStatuses.value = _eventStatuses.value - oldest.id
                }
            }

            val pendingEvent =
                PendingEvent(
                    id = "pending_${epochMillis()}_$eventId",
                    eventJson = eventJson,
                    eventId = eventId,
                    groupId = groupId,
                )

            _pendingEvents.value = _pendingEvents.value + pendingEvent
            _eventStatuses.value = _eventStatuses.value + (pendingEvent.id to PendingEventStatus.Queued)
            true
        }
    }

    /**
     * Remove a pending event (e.g., user cancelled).
     */
    suspend fun removeEvent(pendingId: String) {
        mutex.withLock {
            _pendingEvents.value = _pendingEvents.value.filter { it.id != pendingId }
            _eventStatuses.value = _eventStatuses.value - pendingId
        }
    }

    /**
     * Retry all pending events.
     * Should be called when connection is restored.
     */
    suspend fun retryPendingEvents() {
        if (_isRetrying.value) return
        _isRetrying.value = true

        try {
            val events = _pendingEvents.value.toList()
            if (events.isEmpty()) return

            val client = connectionManager.getFocusedClient() ?: return

            for (event in events) {
                if (!event.canRetry) {
                    // Mark as permanently failed
                    val reason = event.lastError ?: "Max retries exceeded"
                    updateStatus(
                        event.id,
                        PendingEventStatus.Failed(reason = reason, canRetry = false),
                    )
                    onEventPermanentlyFailed?.invoke(event.eventId, event.groupId, event.eventJson, reason)
                    removeEvent(event.id)
                    continue
                }

                // Update status to sending
                updateStatus(event.id, PendingEventStatus.Sending)

                // Calculate retry delay with exponential backoff
                val delayMs = calculateRetryDelay(event.retryCount)
                if (event.retryCount > 0) {
                    delay(delayMs)
                }

                // Attempt to send
                val result = client.sendAndAwaitOk(event.eventJson, event.eventId)

                when (result) {
                    is PublishResult.Success -> {
                        // Remove from queue on success
                        updateStatus(event.id, PendingEventStatus.Sent(result))
                        onEventDelivered?.invoke(event.eventId)
                        removeEvent(event.id)
                    }
                    is PublishResult.Rejected -> {
                        // Don't retry rejected events - relay explicitly refused
                        updateStatus(
                            event.id,
                            PendingEventStatus.Failed(
                                reason = result.reason,
                                canRetry = false,
                            ),
                        )
                        onEventPermanentlyFailed?.invoke(event.eventId, event.groupId, event.eventJson, result.reason)
                        removeEvent(event.id)
                    }
                    is PublishResult.Timeout, is PublishResult.Error -> {
                        val errorMsg =
                            when (result) {
                                is PublishResult.Timeout -> "Timeout"
                                is PublishResult.Error -> result.exception.message ?: "Unknown error"
                                else -> "Unknown error"
                            }
                        incrementRetryCount(event.id, errorMsg)
                    }
                }
            }
        } finally {
            _isRetrying.value = false
        }
    }

    /**
     * Retry a specific pending event.
     */
    suspend fun retrySingleEvent(pendingId: String): PublishResult? {
        val event = _pendingEvents.value.find { it.id == pendingId } ?: return null
        if (!event.canRetry) return null

        val client = connectionManager.getFocusedClient() ?: return null

        updateStatus(event.id, PendingEventStatus.Sending)

        val result = client.sendAndAwaitOk(event.eventJson, event.eventId)

        when (result) {
            is PublishResult.Success -> {
                updateStatus(event.id, PendingEventStatus.Sent(result))
                onEventDelivered?.invoke(event.eventId)
                removeEvent(event.id)
            }
            is PublishResult.Rejected -> {
                updateStatus(
                    event.id,
                    PendingEventStatus.Failed(
                        reason = result.reason,
                        canRetry = false,
                    ),
                )
                onEventPermanentlyFailed?.invoke(event.eventId, event.groupId, event.eventJson, result.reason)
                removeEvent(event.id)
            }
            is PublishResult.Timeout, is PublishResult.Error -> {
                val errorMsg =
                    when (result) {
                        is PublishResult.Timeout -> "Timeout"
                        is PublishResult.Error -> result.exception.message ?: "Unknown error"
                        else -> "Unknown error"
                    }
                incrementRetryCount(event.id, errorMsg)
            }
        }

        return result
    }

    /**
     * Get count of pending events for a specific group.
     */
    fun getPendingCountForGroup(groupId: String): Int = _pendingEvents.value.count { it.groupId == groupId }

    /**
     * Get all pending events for a specific group.
     */
    fun getPendingEventsForGroup(groupId: String): List<PendingEvent> = _pendingEvents.value.filter { it.groupId == groupId }

    /**
     * Clear all pending events (e.g., on logout).
     */
    suspend fun clear() {
        mutex.withLock {
            _pendingEvents.value = emptyList()
            _eventStatuses.value = emptyMap()
        }
    }

    /**
     * Called when connection is restored.
     * Triggers automatic retry of pending events.
     */
    fun onConnectionRestored() {
        scope.launch {
            retryPendingEvents()
        }
    }

    private suspend fun updateStatus(
        pendingId: String,
        status: PendingEventStatus,
    ) {
        mutex.withLock {
            _eventStatuses.value = _eventStatuses.value + (pendingId to status)
        }
    }

    private suspend fun incrementRetryCount(
        pendingId: String,
        errorMsg: String,
    ) {
        mutex.withLock {
            val current = _pendingEvents.value
            val event = current.find { it.id == pendingId } ?: return@withLock

            val updated =
                event.copy(
                    retryCount = event.retryCount + 1,
                    lastError = errorMsg,
                )

            _pendingEvents.value = current.map { if (it.id == pendingId) updated else it }

            if (updated.canRetry) {
                _eventStatuses.value = _eventStatuses.value +
                    (pendingId to PendingEventStatus.Failed(reason = errorMsg, canRetry = true))
            } else {
                // Retries exhausted: terminal failure. Notify the owner and drop it
                // from the queue so the on-screen message resolves to Failed now
                // instead of lingering as Sending until the next reconnect sweep.
                val reason = "Max retries exceeded: $errorMsg"
                _eventStatuses.value = _eventStatuses.value +
                    (pendingId to PendingEventStatus.Failed(reason = reason, canRetry = false))
                onEventPermanentlyFailed?.invoke(updated.eventId, updated.groupId, updated.eventJson, reason)
                _pendingEvents.value = _pendingEvents.value.filter { it.id != pendingId }
                _eventStatuses.value = _eventStatuses.value - pendingId
            }
        }
    }

    private fun calculateRetryDelay(retryCount: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, ... up to max
        val delay = BASE_RETRY_DELAY_MS * (1 shl retryCount)
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    // ==========================================================================
    // PERSISTENCE
    // ==========================================================================

    /**
     * Load pending events from storage.
     */
    private fun loadFromStorage() {
        val pubkey = currentPubkey ?: return
        val eventsJson = SecureStorage.getPendingEvents(pubkey) ?: return

        try {
            val eventsArray = json.parseToJsonElement(eventsJson).jsonArray
            val events =
                eventsArray.mapNotNull { element ->
                    try {
                        val obj = element.jsonObject
                        PendingEvent(
                            id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            eventJson = obj["eventJson"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            eventId = obj["eventId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            groupId = obj["groupId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                            createdAt = obj["createdAt"]?.jsonPrimitive?.long ?: epochMillis(),
                            retryCount = obj["retryCount"]?.jsonPrimitive?.int ?: 0,
                            maxRetries = obj["maxRetries"]?.jsonPrimitive?.int ?: 3,
                            lastError = obj["lastError"]?.jsonPrimitive?.contentOrNull,
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

            if (events.isNotEmpty()) {
                _pendingEvents.value = events
                events.forEach { event ->
                    _eventStatuses.value = _eventStatuses.value + (event.id to PendingEventStatus.Queued)
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors
        }
    }

    /**
     * Save pending events to storage.
     */
    fun saveToStorage() {
        val pubkey = currentPubkey ?: return
        val events = _pendingEvents.value

        if (events.isEmpty()) {
            SecureStorage.clearPendingEvents(pubkey)
            return
        }

        try {
            val eventsJson =
                buildJsonArray {
                    events.forEach { event ->
                        add(
                            buildJsonObject {
                                put("id", event.id)
                                put("eventJson", event.eventJson)
                                put("eventId", event.eventId)
                                put("groupId", event.groupId)
                                put("createdAt", event.createdAt)
                                put("retryCount", event.retryCount)
                                put("maxRetries", event.maxRetries)
                                event.lastError?.let { put("lastError", it) }
                            },
                        )
                    }
                }.toString()

            SecureStorage.savePendingEvents(pubkey, eventsJson)
        } catch (e: Exception) {
            // Ignore save errors
        }
    }

    /**
     * Clear persisted pending events.
     */
    fun clearStorage() {
        val pubkey = currentPubkey ?: return
        SecureStorage.clearPendingEvents(pubkey)
    }
}
