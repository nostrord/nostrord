package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.PublishResult
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
    val lastError: String? = null
) {
    val canRetry: Boolean get() = retryCount < maxRetries
}

/**
 * Status of a pending event.
 */
sealed class PendingEventStatus {
    data object Queued : PendingEventStatus()
    data object Sending : PendingEventStatus()
    data class Sent(val result: PublishResult) : PendingEventStatus()
    data class Failed(val reason: String, val canRetry: Boolean) : PendingEventStatus()
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
    private val scope: CoroutineScope
) {
    companion object {
        const val MAX_QUEUE_SIZE = 100
        const val BASE_RETRY_DELAY_MS = 1000L
        const val MAX_RETRY_DELAY_MS = 30_000L
    }

    private val mutex = Mutex()

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
     * Queue an event for later sending.
     * Called when send fails due to offline or network error.
     *
     * @return true if event was queued, false if queue is full
     */
    suspend fun queueEvent(
        eventJson: String,
        eventId: String,
        groupId: String
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

            val pendingEvent = PendingEvent(
                id = "pending_${epochMillis()}_$eventId",
                eventJson = eventJson,
                eventId = eventId,
                groupId = groupId
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

            val client = connectionManager.getPrimaryClient() ?: return

            for (event in events) {
                if (!event.canRetry) {
                    // Mark as permanently failed
                    updateStatus(event.id, PendingEventStatus.Failed(
                        reason = event.lastError ?: "Max retries exceeded",
                        canRetry = false
                    ))
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
                        removeEvent(event.id)
                    }
                    is PublishResult.Rejected -> {
                        // Don't retry rejected events - relay explicitly refused
                        updateStatus(event.id, PendingEventStatus.Failed(
                            reason = result.reason,
                            canRetry = false
                        ))
                        removeEvent(event.id)
                    }
                    is PublishResult.Timeout, is PublishResult.Error -> {
                        // Increment retry count
                        val errorMsg = when (result) {
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

        val client = connectionManager.getPrimaryClient() ?: return null

        updateStatus(event.id, PendingEventStatus.Sending)

        val result = client.sendAndAwaitOk(event.eventJson, event.eventId)

        when (result) {
            is PublishResult.Success -> {
                updateStatus(event.id, PendingEventStatus.Sent(result))
                removeEvent(event.id)
            }
            is PublishResult.Rejected -> {
                updateStatus(event.id, PendingEventStatus.Failed(
                    reason = result.reason,
                    canRetry = false
                ))
                removeEvent(event.id)
            }
            is PublishResult.Timeout, is PublishResult.Error -> {
                val errorMsg = when (result) {
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
    fun getPendingCountForGroup(groupId: String): Int {
        return _pendingEvents.value.count { it.groupId == groupId }
    }

    /**
     * Get all pending events for a specific group.
     */
    fun getPendingEventsForGroup(groupId: String): List<PendingEvent> {
        return _pendingEvents.value.filter { it.groupId == groupId }
    }

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

    private suspend fun updateStatus(pendingId: String, status: PendingEventStatus) {
        mutex.withLock {
            _eventStatuses.value = _eventStatuses.value + (pendingId to status)
        }
    }

    private suspend fun incrementRetryCount(pendingId: String, errorMsg: String) {
        mutex.withLock {
            val current = _pendingEvents.value
            val event = current.find { it.id == pendingId } ?: return@withLock

            val updated = event.copy(
                retryCount = event.retryCount + 1,
                lastError = errorMsg
            )

            _pendingEvents.value = current.map { if (it.id == pendingId) updated else it }

            // Update status
            val status = if (updated.canRetry) {
                PendingEventStatus.Failed(reason = errorMsg, canRetry = true)
            } else {
                PendingEventStatus.Failed(reason = "Max retries exceeded: $errorMsg", canRetry = false)
            }
            _eventStatuses.value = _eventStatuses.value + (pendingId to status)
        }
    }

    private fun calculateRetryDelay(retryCount: Int): Long {
        // Exponential backoff: 1s, 2s, 4s, 8s, ... up to max
        val delay = BASE_RETRY_DELAY_MS * (1 shl retryCount)
        return delay.coerceAtMost(MAX_RETRY_DELAY_MS)
    }
}
