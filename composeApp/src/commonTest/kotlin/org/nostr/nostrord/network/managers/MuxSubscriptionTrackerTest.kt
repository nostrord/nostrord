package org.nostr.nostrord.network.managers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuxSubscriptionTrackerTest {
    // ========================================================================
    // 2. Resilience — deduplication of mux sends (Checklist item 2)
    // ========================================================================

    @Test
    fun `first refresh is always needed`() {
        val tracker = MuxSubscriptionTracker()
        val state =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1", "g2"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        assertTrue(tracker.needsRefresh("wss://relay", state))
    }

    @Test
    fun `identical state does not need refresh`() {
        val tracker = MuxSubscriptionTracker()
        val state =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1", "g2"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay", state)
        assertFalse(tracker.needsRefresh("wss://relay", state))
    }

    @Test
    fun `new group in metadata triggers refresh`() {
        val tracker = MuxSubscriptionTracker()
        val state1 =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay", state1)

        val state2 = state1.copy(metadataGroupIds = setOf("g1", "g2"))
        assertTrue(tracker.needsRefresh("wss://relay", state2))
    }

    @Test
    fun `new chat group triggers refresh`() {
        val tracker = MuxSubscriptionTracker()
        val state1 =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1", "g2"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay", state1)

        val state2 = state1.copy(chatGroupIds = setOf("g1", "g2"))
        assertTrue(tracker.needsRefresh("wss://relay", state2))
    }

    @Test
    fun `older since timestamp triggers refresh`() {
        val tracker = MuxSubscriptionTracker()
        val state1 =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay", state1)

        // Older since = we need events we didn't previously request
        val state2 = state1.copy(chatSinceSeconds = 500L)
        assertTrue(tracker.needsRefresh("wss://relay", state2))
    }

    @Test
    fun `newer since timestamp does NOT trigger refresh`() {
        val tracker = MuxSubscriptionTracker()
        val state1 =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay", state1)

        // Newer since = subset of what we already asked for, no need to refresh
        val state2 = state1.copy(chatSinceSeconds = 1500L)
        assertFalse(tracker.needsRefresh("wss://relay", state2))
    }

    @Test
    fun `clearRelay forces refresh on next call`() {
        val tracker = MuxSubscriptionTracker()
        val state =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay", state)
        assertFalse(tracker.needsRefresh("wss://relay", state))

        tracker.clearRelay("wss://relay")
        assertTrue(tracker.needsRefresh("wss://relay", state), "After clear, should need refresh")
    }

    @Test
    fun `clearAll forces refresh on all relays`() {
        val tracker = MuxSubscriptionTracker()
        val state =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay-a", state)
        tracker.update("wss://relay-b", state)

        tracker.clearAll()

        assertTrue(tracker.needsRefresh("wss://relay-a", state))
        assertTrue(tracker.needsRefresh("wss://relay-b", state))
    }

    @Test
    fun `different relays are tracked independently`() {
        val tracker = MuxSubscriptionTracker()
        val state =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay-a", state)

        // relay-a doesn't need refresh, relay-b does
        assertFalse(tracker.needsRefresh("wss://relay-a", state))
        assertTrue(tracker.needsRefresh("wss://relay-b", state))
    }

    @Test
    fun `url spellings normalize to the same entry`() {
        val tracker = MuxSubscriptionTracker()
        val state =
            MuxSubscriptionTracker.MuxState(
                metadataGroupIds = setOf("g1"),
                chatGroupIds = setOf("g1"),
                chatSinceSeconds = 1000L,
            )
        tracker.update("wss://relay.example.com/", state)
        // Trailing slash / host case must not split the entry: a CLOSED handler
        // clearing with the raw socket URL has to hit the state the refresh stored.
        assertFalse(tracker.needsRefresh("wss://relay.example.com", state))
        tracker.clearRelay("wss://RELAY.example.com")
        assertTrue(tracker.needsRefresh("wss://relay.example.com/", state))
    }

    // ========================================================================
    // Staleness — silently dead subs must not hide behind needsRefresh dedup
    // ========================================================================

    private val state1 =
        MuxSubscriptionTracker.MuxState(
            metadataGroupIds = setOf("g1"),
            chatGroupIds = setOf("g1"),
            chatSinceSeconds = 1000L,
        )

    @Test
    fun `stale only after the quiet window elapses`() {
        val tracker = MuxSubscriptionTracker()
        tracker.update("wss://relay", state1, nowMs = 0L)
        assertFalse(tracker.isStale("wss://relay", nowMs = 600_000L, staleAfterMs = 600_000L))
        assertTrue(tracker.isStale("wss://relay", nowMs = 600_001L, staleAfterMs = 600_000L))
    }

    @Test
    fun `activity resets the quiet window`() {
        val tracker = MuxSubscriptionTracker()
        tracker.update("wss://relay", state1, nowMs = 0L)
        tracker.noteActivity("wss://relay", nowMs = 500_000L)
        assertFalse(tracker.isStale("wss://relay", nowMs = 1_000_000L, staleAfterMs = 600_000L))
        assertTrue(tracker.isStale("wss://relay", nowMs = 1_100_001L, staleAfterMs = 600_000L))
    }

    @Test
    fun `relay without active mux state is never stale`() {
        val tracker = MuxSubscriptionTracker()
        // No sub was ever sent (or it was cleared): there is nothing to re-arm.
        tracker.noteActivity("wss://relay", nowMs = 0L)
        assertFalse(tracker.isStale("wss://relay", nowMs = 10_000_000L, staleAfterMs = 600_000L))
    }
}
