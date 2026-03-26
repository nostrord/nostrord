package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.network.NostrGroupClient

/**
 * Buffers incoming chat messages per group for [windowMs] milliseconds, then flushes
 * them sorted by [NostrGroupClient.NostrMessage.createdAt] in a single callback.
 *
 * Benefits:
 * - Reduces StateFlow emissions during burst loads (N arriving messages → 1 UI update)
 * - Ensures a stable sort across multi-relay deliveries: events from two relays that
 *   cover the same time-window are collected in the buffer and emitted in order
 *
 * Non-UI concerns (deduplication, cursor tracking, pagination state machine) are handled
 * BEFORE enqueue, so they remain immediate and are not affected by the window delay.
 *
 * @param scope        Coroutine scope that owns the debounce timers.
 * @param windowMs     Debounce window in ms. Resets on each new message for the same group.
 * @param maxBufferSize Flush immediately (without waiting for [windowMs]) when this many
 *                     messages have accumulated for a group. Prevents unbounded memory use
 *                     during extremely high-throughput bursts (e.g. initial load of 150+ events).
 * @param onFlush      Called once per group when the window expires or the buffer is full,
 *                     with messages sorted by createdAt.
 */
class EventOrderingBuffer(
    private val scope: CoroutineScope,
    val windowMs: Long = WINDOW_MS,
    val maxBufferSize: Int = MAX_BUFFER_SIZE,
    private val onFlush: (groupId: String, messages: List<NostrGroupClient.NostrMessage>) -> Unit
) {
    companion object {
        const val WINDOW_MS = 300L
        const val MAX_BUFFER_SIZE = 150
    }

    private val mutex = Mutex()
    // groupId → messages collected in the current window
    private val buffers = mutableMapOf<String, MutableList<NostrGroupClient.NostrMessage>>()
    // groupId → active debounce Job (replaced on each enqueue)
    private val flushJobs = mutableMapOf<String, Job>()

    /**
     * Add [message] to the buffer for [groupId] and reset the flush timer.
     *
     * If the buffer reaches [maxBufferSize] the batch is flushed immediately without
     * waiting for the debounce window. Otherwise, the [onFlush] callback fires after
     * [windowMs] of inactivity for this group.
     */
    fun enqueue(groupId: String, message: NostrGroupClient.NostrMessage) {
        scope.launch {
            var immediateFlush: List<NostrGroupClient.NostrMessage>? = null

            mutex.withLock {
                val buffer = buffers.getOrPut(groupId) { mutableListOf() }
                buffer.add(message)

                if (buffer.size >= maxBufferSize) {
                    // Buffer full — cancel the pending timer and flush right away.
                    flushJobs[groupId]?.cancel()
                    flushJobs.remove(groupId)
                    immediateFlush = buffers.remove(groupId)!!.toList()
                } else {
                    // Debounce: cancel the old timer and start a fresh one.
                    // NOTE: scope.launch inside withLock is safe — it merely schedules a new
                    // coroutine (returns a Job immediately) without suspending the current one.
                    // The new coroutine acquires the mutex independently after the window expires.
                    flushJobs[groupId]?.cancel()
                    flushJobs[groupId] = scope.launch {
                        delay(windowMs)
                        val batch = mutex.withLock {
                            flushJobs.remove(groupId)
                            val b = buffers.remove(groupId) ?: return@withLock emptyList()
                            b.toList()
                        }
                        if (batch.isNotEmpty()) {
                            onFlush(groupId, batch.sortedBy { it.createdAt })
                        }
                    }
                }
            }

            // Flush outside the lock so onFlush can freely call other suspend code.
            immediateFlush?.let { onFlush(groupId, it.sortedBy { m -> m.createdAt }) }
        }
    }

    /**
     * Immediately flush all pending group buffers without waiting for the window to expire.
     * Call on logout or scope cancellation to ensure no messages are silently dropped.
     */
    fun flushAll() {
        scope.launch {
            val snapshot = mutex.withLock {
                flushJobs.values.forEach { it.cancel() }
                flushJobs.clear()
                val snap = buffers.toMap()
                buffers.clear()
                snap
            }
            snapshot.forEach { (groupId, messages) ->
                if (messages.isNotEmpty()) {
                    onFlush(groupId, messages.sortedBy { it.createdAt })
                }
            }
        }
    }
}
