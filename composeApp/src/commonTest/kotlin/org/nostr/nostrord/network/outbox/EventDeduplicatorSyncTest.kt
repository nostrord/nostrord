package org.nostr.nostrord.network.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventDeduplicatorSyncTest {

    // ============================================================================
    // Basic deduplication
    // ============================================================================

    @Test
    fun `first event is accepted`() {
        val dedup = EventDeduplicator()
        assertTrue(dedup.tryAddSync("event-001"))
    }

    @Test
    fun `same event id is rejected on second call`() {
        val dedup = EventDeduplicator()
        dedup.tryAddSync("event-001")
        assertFalse(dedup.tryAddSync("event-001"))
    }

    @Test
    fun `different event ids are all accepted`() {
        val dedup = EventDeduplicator()
        assertTrue(dedup.tryAddSync("event-001"))
        assertTrue(dedup.tryAddSync("event-002"))
        assertTrue(dedup.tryAddSync("event-003"))
    }

    // ============================================================================
    // Size tracking
    // ============================================================================

    @Test
    fun `size reflects unique events only`() {
        val dedup = EventDeduplicator()
        dedup.tryAddSync("a")
        dedup.tryAddSync("b")
        dedup.tryAddSync("a") // duplicate
        assertEquals(2, dedup.size())
    }

    @Test
    fun `clear resets size to zero`() {
        val dedup = EventDeduplicator()
        dedup.tryAddSync("a")
        dedup.tryAddSync("b")
        dedup.clearSync()
        assertEquals(0, dedup.size())
    }

    @Test
    fun `event accepted again after clear`() {
        val dedup = EventDeduplicator()
        dedup.tryAddSync("a")
        dedup.clearSync()
        assertTrue(dedup.tryAddSync("a"))
    }

    // ============================================================================
    // Max size eviction (LRU)
    // ============================================================================

    @Test
    fun `oldest event evicted when capacity exceeded`() {
        val dedup = EventDeduplicator(maxSize = 3, ttlMs = Long.MAX_VALUE)
        dedup.tryAddSync("first")
        dedup.tryAddSync("second")
        dedup.tryAddSync("third")
        // Adding a 4th evicts "first"
        dedup.tryAddSync("fourth")
        assertEquals(3, dedup.size())
        // "first" was evicted — should be accepted as new
        assertTrue(dedup.tryAddSync("first"))
    }

    // ============================================================================
    // Batch filter
    // ============================================================================

    @Test
    fun `filterNewSync returns only new events`() {
        val dedup = EventDeduplicator()
        dedup.tryAddSync("existing")

        val result = dedup.filterNewSync(listOf("existing", "new-a", "new-b", "existing"))
        assertEquals(listOf("new-a", "new-b"), result)
    }

    @Test
    fun `filterNewSync on empty list returns empty`() {
        val dedup = EventDeduplicator()
        assertTrue(dedup.filterNewSync(emptyList()).isEmpty())
    }

    // ============================================================================
    // Statistics
    // ============================================================================

    @Test
    fun `stats correctly track totals and duplicates`() {
        val dedup = EventDeduplicator()
        dedup.tryAddSync("a")
        dedup.tryAddSync("b")
        dedup.tryAddSync("a") // duplicate

        val stats = dedup.getStats()
        assertEquals(3L, stats.totalEvents)
        assertEquals(2L, stats.uniqueEvents)
        assertEquals(1L, stats.duplicateEvents)
    }
}
