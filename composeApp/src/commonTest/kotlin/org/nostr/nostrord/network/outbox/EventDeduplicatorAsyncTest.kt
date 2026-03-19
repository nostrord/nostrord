package org.nostr.nostrord.network.outbox

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventDeduplicatorAsyncTest {

    // ============================================================================
    // Basic async deduplication
    // ============================================================================

    @Test
    fun `first event accepted`() = runTest {
        val dedup = EventDeduplicator()
        assertTrue(dedup.tryAdd("event-001"))
    }

    @Test
    fun `duplicate event rejected`() = runTest {
        val dedup = EventDeduplicator()
        dedup.tryAdd("event-001")
        assertFalse(dedup.tryAdd("event-001"))
    }

    @Test
    fun `distinct events all accepted`() = runTest {
        val dedup = EventDeduplicator()
        assertTrue(dedup.tryAdd("a"))
        assertTrue(dedup.tryAdd("b"))
        assertTrue(dedup.tryAdd("c"))
        assertEquals(3, dedup.size())
    }

    // ============================================================================
    // contains / remove
    // ============================================================================

    @Test
    fun `contains returns true after add`() = runTest {
        val dedup = EventDeduplicator()
        dedup.tryAdd("x")
        assertTrue(dedup.contains("x"))
    }

    @Test
    fun `contains returns false for unknown event`() = runTest {
        val dedup = EventDeduplicator()
        assertFalse(dedup.contains("unknown"))
    }

    @Test
    fun `remove makes event accepted again`() = runTest {
        val dedup = EventDeduplicator()
        dedup.tryAdd("x")
        dedup.remove("x")
        assertTrue(dedup.tryAdd("x"))
    }

    // ============================================================================
    // clear
    // ============================================================================

    @Test
    fun `clear empties the cache`() = runTest {
        val dedup = EventDeduplicator()
        dedup.tryAdd("a")
        dedup.tryAdd("b")
        dedup.clear()
        assertEquals(0, dedup.size())
        assertTrue(dedup.tryAdd("a"))
    }

    // ============================================================================
    // LRU eviction
    // ============================================================================

    @Test
    fun `oldest entry evicted when capacity exceeded`() = runTest {
        val dedup = EventDeduplicator(maxSize = 2, ttlMs = Long.MAX_VALUE)
        dedup.tryAdd("first")
        dedup.tryAdd("second")
        dedup.tryAdd("third") // evicts "first"

        assertEquals(2, dedup.size())
        assertTrue(dedup.tryAdd("first")) // "first" was evicted, accepted again
    }

    // ============================================================================
    // Batch filter async
    // ============================================================================

    @Test
    fun `filterNew returns only unseen events`() = runTest {
        val dedup = EventDeduplicator()
        dedup.tryAdd("seen")

        val result = dedup.filterNew(listOf("seen", "new-1", "new-2", "seen"))
        assertEquals(listOf("new-1", "new-2"), result)
    }

    @Test
    fun `filterNew on empty list returns empty`() = runTest {
        val dedup = EventDeduplicator()
        assertTrue(dedup.filterNew(emptyList()).isEmpty())
    }

    // ============================================================================
    // Concurrent stats consistency
    // ============================================================================

    @Test
    fun `stats track duplicates correctly`() = runTest {
        val dedup = EventDeduplicator()
        dedup.tryAdd("a")
        dedup.tryAdd("b")
        dedup.tryAdd("a") // duplicate

        val stats = dedup.getStats()
        assertEquals(3L, stats.totalEvents)
        assertEquals(1L, stats.duplicateEvents)
        assertEquals(2L, stats.uniqueEvents)
    }
}
