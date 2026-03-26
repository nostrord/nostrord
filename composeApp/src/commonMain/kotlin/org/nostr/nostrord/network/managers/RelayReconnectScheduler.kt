package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * Centralised, priority-aware reconnect scheduler for NIP-29 pool relays.
 *
 * Replaces per-relay `while(true)` loops with a shared scheduler that:
 * - Applies exponential backoff with random jitter (prevents thundering herd)
 * - Limits concurrent reconnects to [MAX_CONCURRENT] (battery/CPU friendly)
 * - Promotes active relays to faster backoff than background ones
 * - Transparently transitions from fast retries to a 30 s slow-retry loop
 * - Stops retrying when the user removes a relay from their list
 *
 * All coroutines run on [scope]; cancelling scope (e.g. on logout) automatically
 * cancels all pending retries. New retries can be scheduled on the same scope
 * after it is restored via [CoroutineScope.cancelChildren].
 *
 * @param scope         Coroutine scope shared with the owning repository.
 * @param isRelayActive Returns true if [relayUrl] should still be retried (e.g. still
 *                      in the user's saved relay list).
 * @param doReconnect   Performs one connection attempt; returns true on success.
 */
class RelayReconnectScheduler(
    private val scope: CoroutineScope,
    private val isRelayActive: (relayUrl: String) -> Boolean,
    private val doReconnect: suspend (relayUrl: String) -> Boolean
) {
    enum class Priority {
        /** Relay hosts the group currently open on screen — fastest backoff. */
        ACTIVE,
        /** NIP-29 relay the user has groups on — standard backoff. */
        BACKGROUND
    }

    /** Maximum number of reconnect attempts running at the same time. */
    private val concurrency = Semaphore(MAX_CONCURRENT)

    companion object {
        const val MAX_CONCURRENT = 2
        const val MAX_FAST_ATTEMPTS = 8
        const val SLOW_RETRY_DELAY_MS = 30_000L
    }

    /**
     * Schedule a reconnect attempt for [relayUrl].
     *
     * The first call uses [attempt] = 1. Each failed attempt calls [schedule] again
     * with [attempt] + 1. After [MAX_FAST_ATTEMPTS] the scheduler switches to a 30 s
     * slow-retry loop and continues until [isRelayActive] returns false.
     *
     * Concurrent calls for the same relay are safe — [doReconnect] is idempotent
     * (the underlying connection pool returns the existing client if already connected).
     */
    fun schedule(
        relayUrl: String,
        attempt: Int = 1,
        priority: Priority = Priority.BACKGROUND
    ) {
        scope.launch {
            val delayMs = computeDelay(attempt, priority)
            delay(delayMs)

            // In the slow-retry phase, stop if the relay was removed from the user's list.
            if (attempt > MAX_FAST_ATTEMPTS && !isRelayActive(relayUrl)) {
                println("[Pool] abandoned  relay=$relayUrl  reason=removed-from-list")
                return@launch
            }

            val success = concurrency.withPermit {
                try { doReconnect(relayUrl) } catch (_: Exception) { false }
            }

            if (!success) {
                println("[Pool] retry  relay=$relayUrl  attempt=$attempt")
                schedule(relayUrl, attempt + 1, priority)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun computeDelay(attempt: Int, priority: Priority): Long {
        // After fast phase, use a flat 30 s slow-retry interval.
        if (attempt > MAX_FAST_ATTEMPTS) return SLOW_RETRY_DELAY_MS

        val baseMs = when (priority) {
            Priority.ACTIVE     -> minOf(500L  * (1L shl (attempt - 1)), 10_000L)
            Priority.BACKGROUND -> minOf(1000L * (1L shl (attempt - 1)), 30_000L)
        }
        // Add 0–25 % jitter so multiple relays don't all retry at the exact same instant.
        val jitter = (baseMs * Random.nextDouble(0.0, 0.25)).toLong()
        return baseMs + jitter
    }
}
