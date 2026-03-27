package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.nostr.nostrord.utils.epochMillis

/**
 * Handles event deduplication across multiple relays.
 *
 * Uses a simple cache to track seen event IDs and prevent
 * duplicate processing when the same event arrives from
 * multiple relays.
 *
 * ## Features:
 * - Thread-safe operations using Mutex
 * - LRU-style eviction when cache is full
 * - TTL-based eviction (default 24 hours)
 * - Configurable cache size and TTL
 * - Statistics tracking
 */
class EventDeduplicator(
    private val maxSize: Int = DEFAULT_MAX_SIZE,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    companion object {
        // 20k covers ~50 groups × 3 relays × many events in a long session.
        // Memory: 20k × 64 bytes (hex ID + map overhead) ≈ 1.3 MB — acceptable on all targets.
        const val DEFAULT_MAX_SIZE = 20_000
        const val DEFAULT_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours

        // Run TTL eviction at most once every 10 minutes — keeps the hot-path O(1).
        private const val EVICT_INTERVAL_MS = 10 * 60 * 1000L
    }

    private val seenEvents = mutableMapOf<String, Long>()
    private val insertionOrder = mutableListOf<String>()
    private val mutex = Mutex()

    // TTL eviction runs lazily — only when at capacity, at most every EVICT_INTERVAL_MS.
    // This keeps tryAddSync() O(1) in the steady state instead of scanning on every call.
    private var lastEvictAt = 0L

    // Statistics
    private var totalEvents = 0L
    private var duplicateEvents = 0L

    /**
     * Try to add an event ID to the set.
     * Returns true if the event is new (not a duplicate).
     * Returns false if the event was already seen.
     */
    suspend fun tryAdd(eventId: String): Boolean {
        return mutex.withLock {
            totalEvents++
            val now = epochMillis()

            if (seenEvents.containsKey(eventId)) {
                duplicateEvents++
                return@withLock false
            }

            // At capacity: try TTL eviction first (rate-limited), then LRU fallback
            if (seenEvents.size >= maxSize) {
                if (now - lastEvictAt >= EVICT_INTERVAL_MS) {
                    evictExpired(now)
                    lastEvictAt = now
                }
                // If still at capacity after eviction, remove oldest insertion
                if (seenEvents.size >= maxSize && insertionOrder.isNotEmpty()) {
                    val oldest = insertionOrder.removeAt(0)
                    seenEvents.remove(oldest)
                }
            }

            seenEvents[eventId] = now
            insertionOrder.add(eventId)
            true
        }
    }

    /**
     * Public TTL eviction — called from AppModule's hourly cleanup coroutine.
     * Removes all entries older than [ttlMs] while holding the mutex.
     */
    suspend fun evictExpired() = mutex.withLock {
        evictExpired(epochMillis())
        lastEvictAt = epochMillis()
    }

    private fun evictExpired(now: Long) {
        val expiredBefore = now - ttlMs
        val iterator = insertionOrder.iterator()
        while (iterator.hasNext()) {
            val eventId = iterator.next()
            val timestamp = seenEvents[eventId] ?: continue
            if (timestamp < expiredBefore) {
                iterator.remove()
                seenEvents.remove(eventId)
            } else {
                // Since insertionOrder is sorted by time, we can stop early
                break
            }
        }
    }

    /**
     * Check if an event ID has been seen (without adding it)
     */
    suspend fun contains(eventId: String): Boolean {
        return mutex.withLock {
            seenEvents.containsKey(eventId)
        }
    }

    /**
     * Remove an event ID from the set
     */
    suspend fun remove(eventId: String) {
        mutex.withLock {
            seenEvents.remove(eventId)
            insertionOrder.remove(eventId)
        }
    }

    /**
     * Clear all seen events
     */
    suspend fun clear() {
        mutex.withLock {
            seenEvents.clear()
            insertionOrder.clear()
            totalEvents = 0
            duplicateEvents = 0
        }
    }

    /**
     * Non-suspend version for synchronous contexts.
     * Safe for single-threaded JS/Wasm runtime.
     */
    fun clearSync() {
        seenEvents.clear()
        insertionOrder.clear()
        totalEvents = 0
        duplicateEvents = 0
    }

    /**
     * Non-suspend version for synchronous contexts.
     * Safe for single-threaded JS/Wasm runtime.
     */
    fun tryAddSync(eventId: String): Boolean {
        totalEvents++
        val now = epochMillis()

        if (seenEvents.containsKey(eventId)) {
            duplicateEvents++
            return false
        }

        if (seenEvents.size >= maxSize) {
            if (now - lastEvictAt >= EVICT_INTERVAL_MS) {
                evictExpiredSync(now)
                lastEvictAt = now
            }
            if (seenEvents.size >= maxSize && insertionOrder.isNotEmpty()) {
                val oldest = insertionOrder.removeAt(0)
                seenEvents.remove(oldest)
            }
        }

        seenEvents[eventId] = now
        insertionOrder.add(eventId)
        return true
    }

    /**
     * Eviction for sync methods
     */
    private fun evictExpiredSync(now: Long) {
        val expiredBefore = now - ttlMs
        val iterator = insertionOrder.iterator()
        while (iterator.hasNext()) {
            val eventId = iterator.next()
            val timestamp = seenEvents[eventId] ?: continue
            if (timestamp < expiredBefore) {
                iterator.remove()
                seenEvents.remove(eventId)
            } else {
                break
            }
        }
    }

    /**
     * Get current cache size
     */
    fun size(): Int = seenEvents.size

    /**
     * Get deduplication statistics
     */
    fun getStats(): DeduplicationStats {
        val rate = if (totalEvents > 0) {
            (duplicateEvents.toDouble() / totalEvents * 100)
        } else 0.0

        return DeduplicationStats(
            totalEvents = totalEvents,
            uniqueEvents = totalEvents - duplicateEvents,
            duplicateEvents = duplicateEvents,
            deduplicationRate = rate,
            cacheSize = seenEvents.size,
            maxCacheSize = maxSize
        )
    }

    data class DeduplicationStats(
        val totalEvents: Long,
        val uniqueEvents: Long,
        val duplicateEvents: Long,
        val deduplicationRate: Double,
        val cacheSize: Int,
        val maxCacheSize: Int
    ) {
        override fun toString(): String {
            val rateStr = ((deduplicationRate * 10).toLong() / 10.0).toString()
            return "EventDeduplicator: $uniqueEvents unique, $duplicateEvents duplicates " +
                    "($rateStr% dedup rate), cache $cacheSize/$maxCacheSize"
        }
    }
}

/**
 * Extension for batch deduplication
 */
suspend fun EventDeduplicator.filterNew(eventIds: List<String>): List<String> {
    return eventIds.filter { tryAdd(it) }
}

/**
 * Synchronous batch filter
 */
fun EventDeduplicator.filterNewSync(eventIds: List<String>): List<String> {
    return eventIds.filter { tryAddSync(it) }
}
