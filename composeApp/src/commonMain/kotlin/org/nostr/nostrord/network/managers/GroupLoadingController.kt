package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.utils.epochMillis

/**
 * Formal state machine for group message loading.
 *
 * State transitions are explicit, deterministic, and race-free.
 * Each group has its own independent state and lock.
 */

/**
 * Sealed hierarchy representing all possible loading states for a group.
 * Replaces ad-hoc boolean flags with explicit, type-safe states.
 */
sealed class GroupLoadingState {
    /** Group not loaded, no messages requested */
    data object Idle : GroupLoadingState()

    /** Initial message request sent, waiting for EOSE */
    data class InitialLoading(
        val subscriptionId: String,
        val startedAt: Long = epochMillis()
    ) : GroupLoadingState()

    /** Messages loaded, more available for pagination */
    data class HasMore(
        val cursor: PaginationCursor
    ) : GroupLoadingState()

    /** Pagination request in flight */
    data class Paginating(
        val subscriptionId: String,
        val cursor: PaginationCursor,
        val startedAt: Long = epochMillis()
    ) : GroupLoadingState()

    /** All messages loaded, feed exhausted */
    data class Exhausted(
        val cursor: PaginationCursor
    ) : GroupLoadingState()

    /** Loading failed, can retry */
    data class Error(
        val cursor: PaginationCursor?,
        val reason: ErrorReason,
        val retryCount: Int = 0
    ) : GroupLoadingState()

    /** Retry in progress after error */
    data class Retrying(
        val cursor: PaginationCursor?,
        val subscriptionId: String,
        val attemptNumber: Int
    ) : GroupLoadingState()

    enum class ErrorReason {
        TIMEOUT,
        SEND_FAILED,
        DISCONNECTED,
        RELAY_ERROR
    }

    // Helper properties for UI consumption
    val isLoading: Boolean get() = this is InitialLoading || this is Paginating || this is Retrying
    val hasMore: Boolean get() = this is HasMore || this is Paginating || this is Retrying
    val canLoadMore: Boolean get() = this is HasMore
}

/**
 * Immutable pagination cursor that tracks position independently of message list.
 *
 * Uses both timestamp and event ID for precise cursor positioning,
 * handling timestamp collisions correctly.
 */
data class PaginationCursor(
    /** Timestamp of the oldest message in current page */
    val untilTimestamp: Long,
    /** Event ID of the oldest message (for tie-breaking) */
    val lastEventId: String?,
    /** Total messages received across all pages */
    val totalReceived: Int,
    /** Number of pages loaded */
    val pageNumber: Int
) {
    companion object {
        fun initial() = PaginationCursor(
            untilTimestamp = Long.MAX_VALUE,
            lastEventId = null,
            totalReceived = 0,
            pageNumber = 0
        )
    }

    /**
     * Create next cursor from received messages.
     * Subtracts 1 from timestamp to handle relays with exclusive "until" semantics.
     */
    fun advance(
        oldestTimestamp: Long,
        oldestEventId: String,
        messagesReceived: Int
    ): PaginationCursor = PaginationCursor(
        untilTimestamp = oldestTimestamp - 1, // Handle < vs <= relay differences
        lastEventId = oldestEventId,
        totalReceived = totalReceived + messagesReceived,
        pageNumber = pageNumber + 1
    )
}

/**
 * Tracks subscription state for EOSE handling.
 * Supports future multi-relay quorum tracking.
 */
data class SubscriptionTracker(
    val subscriptionId: String,
    val groupId: String,
    val isInitialLoad: Boolean,
    val expectedRelays: Int = 1,
    val receivedEose: MutableSet<String> = mutableSetOf(), // relay URLs that sent EOSE
    var messageCount: Int = 0,
    var oldestTimestamp: Long = Long.MAX_VALUE,
    var oldestEventId: String? = null,
    val timeoutJob: Job? = null
) {
    fun trackMessage(timestamp: Long, eventId: String) {
        messageCount++
        if (timestamp < oldestTimestamp) {
            oldestTimestamp = timestamp
            oldestEventId = eventId
        }
    }

    fun isComplete(quorumSize: Int = 1): Boolean {
        return receivedEose.size >= quorumSize
    }
}

/**
 * Per-group loading controller with its own mutex.
 * Manages state transitions and subscription tracking for a single group.
 */
class GroupLoadingController(
    private val groupId: String,
    private val scope: CoroutineScope,
    private val pageSize: Int = 50,
    private val timeoutMs: Long = 10_000L,
    private val maxRetries: Int = 3
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<GroupLoadingState>(GroupLoadingState.Idle)
    val state: StateFlow<GroupLoadingState> = _state.asStateFlow()

    private var currentTracker: SubscriptionTracker? = null
    private var timeoutJob: Job? = null

    /**
     * Attempt to start initial message loading.
     * Returns subscription ID if started, null if already loading.
     */
    suspend fun startInitialLoad(): String? = mutex.withLock {
        val currentState = _state.value

        // Only start from Idle or Error states
        when (currentState) {
            is GroupLoadingState.Idle,
            is GroupLoadingState.Error -> {
                val subId = "msg_${groupId.take(8)}_${epochMillis()}"
                val tracker = SubscriptionTracker(
                    subscriptionId = subId,
                    groupId = groupId,
                    isInitialLoad = true
                )
                currentTracker = tracker

                _state.value = GroupLoadingState.InitialLoading(subId)
                startTimeout(subId, isInitial = true)

                subId
            }
            else -> null // Already loading or has messages
        }
    }

    /**
     * Attempt to start pagination (load more messages).
     * Returns subscription ID and cursor if started, null if cannot paginate.
     */
    suspend fun startPagination(): Pair<String, PaginationCursor>? = mutex.withLock {
        val currentState = _state.value

        when (currentState) {
            is GroupLoadingState.HasMore -> {
                val subId = "msg_${groupId.take(8)}_${epochMillis()}"
                val cursor = currentState.cursor
                val tracker = SubscriptionTracker(
                    subscriptionId = subId,
                    groupId = groupId,
                    isInitialLoad = false
                )
                currentTracker = tracker

                _state.value = GroupLoadingState.Paginating(subId, cursor)
                startTimeout(subId, isInitial = false)

                Pair(subId, cursor)
            }
            else -> null // Not in pageable state
        }
    }

    /**
     * Track an incoming message for the current subscription.
     * Thread-safe: can be called from WebSocket callback.
     */
    suspend fun trackMessage(subscriptionId: String, timestamp: Long, eventId: String) {
        mutex.withLock {
            val tracker = currentTracker
            if (tracker?.subscriptionId == subscriptionId) {
                tracker.trackMessage(timestamp, eventId)
            }
        }
    }

    /**
     * Handle EOSE for a subscription.
     * Returns true if this was our subscription.
     */
    suspend fun handleEose(subscriptionId: String, relayUrl: String = "primary"): Boolean {
        return mutex.withLock {
            val tracker = currentTracker ?: return@withLock false
            if (tracker.subscriptionId != subscriptionId) return@withLock false

            // Cancel timeout
            timeoutJob?.cancel()
            timeoutJob = null

            // Mark EOSE received from this relay
            tracker.receivedEose.add(relayUrl)

            // Check if we have quorum (currently single-relay, so always complete)
            if (!tracker.isComplete()) {
                return@withLock true // Wait for more relays
            }

            // Determine next state based on message count
            val messagesReceived = tracker.messageCount
            val isExhausted = messagesReceived < pageSize

            val newCursor = if (tracker.oldestEventId != null) {
                (getCurrentCursor() ?: PaginationCursor.initial()).advance(
                    oldestTimestamp = tracker.oldestTimestamp,
                    oldestEventId = tracker.oldestEventId!!,
                    messagesReceived = messagesReceived
                )
            } else {
                // No messages received - exhausted
                getCurrentCursor() ?: PaginationCursor.initial()
            }

            _state.value = if (isExhausted) {
                GroupLoadingState.Exhausted(newCursor)
            } else {
                GroupLoadingState.HasMore(newCursor)
            }

            currentTracker = null
            true
        }
    }

    /**
     * Handle send failure.
     * Preserves retry count if we were in a retry attempt.
     */
    suspend fun handleSendFailure(subscriptionId: String) {
        mutex.withLock {
            val tracker = currentTracker ?: return@withLock
            if (tracker.subscriptionId != subscriptionId) return@withLock

            timeoutJob?.cancel()
            timeoutJob = null

            // Preserve retry count from current state
            val existingRetryCount = getRetryCount()

            _state.value = GroupLoadingState.Error(
                cursor = getCurrentCursor(),
                reason = GroupLoadingState.ErrorReason.SEND_FAILED,
                retryCount = existingRetryCount
            )
            currentTracker = null
        }
    }

    /**
     * Get current retry count from state (for preserving across transitions).
     */
    private fun getRetryCount(): Int {
        return when (val s = _state.value) {
            is GroupLoadingState.Retrying -> s.attemptNumber
            is GroupLoadingState.Error -> s.retryCount
            else -> 0
        }
    }

    /**
     * Handle connection lost.
     * Resets ALL states to Idle because the WebSocket subscription is gone regardless
     * of whether the group was loading or had already loaded messages.
     * This allows startInitialLoad() to re-subscribe on reconnect.
     */
    suspend fun handleDisconnect() {
        mutex.withLock {
            timeoutJob?.cancel()
            timeoutJob = null
            _state.value = GroupLoadingState.Idle
            currentTracker = null
        }
    }

    /**
     * Retry after error.
     * Returns subscription ID if retry started.
     */
    suspend fun retry(): String? = mutex.withLock {
        val currentState = _state.value

        when (currentState) {
            is GroupLoadingState.Error -> {
                if (currentState.retryCount >= maxRetries) {
                    return@withLock null // Max retries exceeded
                }

                val subId = "msg_${groupId.take(8)}_${epochMillis()}"
                val tracker = SubscriptionTracker(
                    subscriptionId = subId,
                    groupId = groupId,
                    isInitialLoad = currentState.cursor == null
                )
                currentTracker = tracker

                _state.value = GroupLoadingState.Retrying(
                    cursor = currentState.cursor,
                    subscriptionId = subId,
                    attemptNumber = currentState.retryCount + 1
                )
                startTimeout(subId, isInitial = currentState.cursor == null)

                subId
            }
            else -> null
        }
    }

    /**
     * Reset to Idle state.
     */
    suspend fun reset() {
        mutex.withLock {
            timeoutJob?.cancel()
            timeoutJob = null
            currentTracker = null
            _state.value = GroupLoadingState.Idle
        }
    }

    /**
     * Check if a subscription ID belongs to this controller.
     */
    suspend fun ownsSubscription(subscriptionId: String): Boolean = mutex.withLock {
        currentTracker?.subscriptionId == subscriptionId
    }

    private fun getCurrentCursor(): PaginationCursor? {
        return when (val s = _state.value) {
            is GroupLoadingState.HasMore -> s.cursor
            is GroupLoadingState.Paginating -> s.cursor
            is GroupLoadingState.Exhausted -> s.cursor
            is GroupLoadingState.Error -> s.cursor
            is GroupLoadingState.Retrying -> s.cursor
            else -> null
        }
    }

    private fun startTimeout(subscriptionId: String, isInitial: Boolean) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            handleTimeout(subscriptionId, isInitial)
        }
    }

    private suspend fun handleTimeout(subscriptionId: String, isInitial: Boolean) {
        mutex.withLock {
            val tracker = currentTracker ?: return@withLock
            if (tracker.subscriptionId != subscriptionId) return@withLock

            // Timeout occurred - transition to error state
            val cursor = getCurrentCursor()

            // Preserve retry count from current state
            val existingRetryCount = getRetryCount()

            // If we received some messages, treat as partial success
            if (tracker.messageCount > 0) {
                val newCursor = if (tracker.oldestEventId != null) {
                    (cursor ?: PaginationCursor.initial()).advance(
                        oldestTimestamp = tracker.oldestTimestamp,
                        oldestEventId = tracker.oldestEventId!!,
                        messagesReceived = tracker.messageCount
                    )
                } else cursor

                // Got some messages, assume more might be available
                _state.value = GroupLoadingState.HasMore(newCursor ?: PaginationCursor.initial())
            } else {
                // No messages at all - error state with preserved retry count
                _state.value = GroupLoadingState.Error(
                    cursor = cursor,
                    reason = GroupLoadingState.ErrorReason.TIMEOUT,
                    retryCount = existingRetryCount
                )
            }

            currentTracker = null
        }
    }
}

/**
 * Registry of per-group loading controllers.
 * Provides O(1) lookup by group ID and subscription ID.
 */
class GroupLoadingRegistry(
    private val scope: CoroutineScope,
    private val pageSize: Int = 50,
    private val timeoutMs: Long = 10_000L
) {
    private val mutex = Mutex()
    private val controllers = mutableMapOf<String, GroupLoadingController>()

    // Reverse lookup: subscriptionId -> controller for O(1) lookup
    private val subscriptionToController = mutableMapOf<String, GroupLoadingController>()

    /**
     * Get or create a controller for a group.
     */
    suspend fun getController(groupId: String): GroupLoadingController = mutex.withLock {
        controllers.getOrPut(groupId) {
            GroupLoadingController(groupId, scope, pageSize, timeoutMs)
        }
    }

    /**
     * Register a subscription ID for O(1) lookup.
     * Called when a new subscription is started.
     */
    suspend fun registerSubscription(subscriptionId: String, controller: GroupLoadingController) {
        mutex.withLock {
            subscriptionToController[subscriptionId] = controller
        }
    }

    /**
     * Unregister a subscription ID.
     * Called when subscription completes (EOSE, timeout, error).
     */
    suspend fun unregisterSubscription(subscriptionId: String) {
        mutex.withLock {
            subscriptionToController.remove(subscriptionId)
        }
    }

    /**
     * Find controller by subscription ID - O(1) lookup.
     */
    suspend fun findBySubscription(subscriptionId: String): GroupLoadingController? = mutex.withLock {
        subscriptionToController[subscriptionId]
    }

    /**
     * Get state for a group (for UI binding).
     */
    suspend fun getState(groupId: String): StateFlow<GroupLoadingState> {
        return getController(groupId).state
    }

    /**
     * Remove a group's controller (on leave/clear).
     */
    suspend fun remove(groupId: String) {
        mutex.withLock {
            val controller = controllers.remove(groupId)
            // Remove any subscriptions for this controller
            subscriptionToController.entries.removeAll { it.value == controller }
            controller?.reset()
        }
    }

    /**
     * Clear all controllers.
     */
    suspend fun clear() {
        val toReset = mutex.withLock {
            val all = controllers.values.toList()
            controllers.clear()
            subscriptionToController.clear()
            all
        }
        toReset.forEach { it.reset() }
    }

    /**
     * Handle EOSE by finding the right controller - O(1) lookup.
     * Also unregisters the subscription.
     */
    suspend fun handleEose(subscriptionId: String, relayUrl: String = "primary"): Boolean {
        val controller = findBySubscription(subscriptionId) ?: return false
        val result = controller.handleEose(subscriptionId, relayUrl)
        if (result) {
            unregisterSubscription(subscriptionId)
        }
        return result
    }

    /**
     * Track a message for the given subscription - O(1) lookup.
     * This is synchronous-safe (called from coroutine context).
     */
    suspend fun trackMessage(subscriptionId: String, timestamp: Long, eventId: String) {
        findBySubscription(subscriptionId)?.trackMessage(subscriptionId, timestamp, eventId)
    }

    /**
     * Handle disconnect for all active controllers.
     */
    suspend fun handleDisconnectAll() {
        val snapshot = mutex.withLock {
            subscriptionToController.clear()  // Clear all pending subscriptions
            controllers.values.toList()
        }
        snapshot.forEach { it.handleDisconnect() }
    }

    /**
     * Handle disconnect for a specific set of groups (e.g. when a pool relay drops).
     * Resets only the affected groups to Idle so startInitialLoad() can re-subscribe.
     */
    suspend fun handleDisconnectForGroups(groupIds: List<String>) {
        val snapshot = mutex.withLock {
            groupIds.mapNotNull { controllers[it] }.also { affected ->
                subscriptionToController.entries.removeAll { e -> e.value in affected }
            }
        }
        snapshot.forEach { it.handleDisconnect() }
    }
}
