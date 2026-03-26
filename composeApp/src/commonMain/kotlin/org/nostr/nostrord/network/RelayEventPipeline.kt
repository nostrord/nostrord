package org.nostr.nostrord.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Bounded, per-relay message processing pipeline.
 *
 * Routes all WebSocket frames for one relay through a single sequential
 * consumer coroutine instead of spawning an unbounded `scope.launch` per
 * frame. Benefits:
 *
 * - Caps memory under burst load (initial load of many groups simultaneously)
 * - Serialises handleMessage calls from the same relay — no concurrent
 *   mutation of relay-scoped counters and state
 * - DROP_OLDEST on overflow keeps the pipeline moving with fresh data;
 *   the deduplicator handles any re-delivery the relay performs
 */
class RelayEventPipeline(
    val relayUrl: String,
    scope: CoroutineScope,
    processMessage: (String) -> Unit
) {
    private val channel = Channel<String>(
        capacity = 2000,
        onBufferOverflow = BufferOverflow.DROP_LATEST
    )

    init {
        scope.launch {
            for (msg in channel) {
                try { processMessage(msg) }
                catch (_: Exception) {}
            }
        }
    }

    /** Non-blocking enqueue. With DROP_OLDEST overflow this always succeeds. */
    fun enqueue(msg: String) {
        channel.trySend(msg)
    }

    /** Close the channel so the consumer coroutine exits after draining remaining messages. */
    fun close() {
        channel.close()
    }
}
