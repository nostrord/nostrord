package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Token-bucket rate limiter for metadata relay connections.
 *
 * Prevents opening too many simultaneous ephemeral outbox-relay connections when
 * a burst of unknown pubkeys arrives (e.g. entering a large group for the first time).
 *
 * Design:
 * - Bucket starts full ([requestsPerSecond] tokens available immediately)
 * - One token is consumed per [acquire] call; suspends if the bucket is empty
 * - The refill coroutine adds one token every (1000 / [requestsPerSecond]) ms,
 *   capped at [requestsPerSecond] to prevent bursting beyond the configured rate
 *
 * @param requestsPerSecond Maximum rate of metadata fetches per second.
 * @param scope             Coroutine scope for the refill loop; cancelled on logout.
 */
class MetadataRateLimiter(
    val requestsPerSecond: Int = DEFAULT_REQUESTS_PER_SECOND,
    scope: CoroutineScope
) {
    companion object {
        const val DEFAULT_REQUESTS_PER_SECOND = 5
    }

    // Channel acts as a token bucket: capacity = burst ceiling, receive = consume token
    private val tokens = Channel<Unit>(requestsPerSecond)

    init {
        // Pre-fill to capacity so the first burst is served immediately
        repeat(requestsPerSecond) { tokens.trySend(Unit) }

        // Refill loop: one token every (1000 / rate) ms, no-op if already full
        scope.launch {
            val intervalMs = 1000L / requestsPerSecond
            while (true) {
                delay(intervalMs)
                tokens.trySend(Unit) // returns failure silently when channel is full
            }
        }
    }

    /**
     * Suspends until a rate-limit token is available, then consumes it.
     * Use in combination with [MetadataManager.fetchConcurrency] for both
     * rate-limiting and concurrency-capping.
     */
    suspend fun acquire() {
        tokens.receive()
    }
}
