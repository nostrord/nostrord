package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTimelineTest {

    private fun msg(id: String, kind: Int, createdAt: Long) = NostrMessage(id = id, pubkey = "p_$id", content = "", createdAt = createdAt, kind = kind)

    private fun ids(messages: List<NostrMessage>) = messages.map { it.id }

    @Test
    fun `hides moderation events older than the oldest loaded chat message`() {
        // A late older join (kind 9021) streamed from the unbounded moderation sub must not
        // appear above the oldest loaded kind:9 message, or it would insert mid-list and shift
        // the reading position.
        val sorted = listOf(
            msg("oldJoin", kind = 9021, createdAt = 100),
            msg("m1", kind = 9, createdAt = 200),
            msg("m2", kind = 9, createdAt = 300),
        )

        val result = clampSystemEventsToLoadedWindow(sorted)

        assertEquals(listOf("m1", "m2"), ids(result))
    }

    @Test
    fun `keeps moderation events at or after the oldest loaded chat message`() {
        val sorted = listOf(
            msg("m1", kind = 9, createdAt = 200),
            msg("join", kind = 9021, createdAt = 250),
            msg("m2", kind = 9, createdAt = 300),
        )

        val result = clampSystemEventsToLoadedWindow(sorted)

        assertEquals(listOf("m1", "join", "m2"), ids(result))
    }

    @Test
    fun `re-reveals older moderation events as older chat pages load`() {
        // Pagination lowers the frontier: once an older kind:9 message is loaded, the
        // previously hidden join above it comes back (a hide, not a delete).
        val beforePaging = listOf(
            msg("oldJoin", kind = 9021, createdAt = 100),
            msg("m2", kind = 9, createdAt = 300),
        )
        assertEquals(listOf("m2"), ids(clampSystemEventsToLoadedWindow(beforePaging)))

        // Paging in a kind:9 message older than the join lowers the frontier past it, so the
        // join is no longer above the oldest loaded message and comes back.
        val afterPaging = listOf(
            msg("m0", kind = 9, createdAt = 50),
            msg("oldJoin", kind = 9021, createdAt = 100),
            msg("m2", kind = 9, createdAt = 300),
        )
        assertEquals(listOf("m0", "oldJoin", "m2"), ids(clampSystemEventsToLoadedWindow(afterPaging)))
    }

    @Test
    fun `shows everything when no chat messages are loaded yet`() {
        // A fresh group with only join/leave noise and no kind:9 yet shows all events.
        val sorted = listOf(
            msg("join", kind = 9021, createdAt = 100),
            msg("leave", kind = 9022, createdAt = 200),
        )

        val result = clampSystemEventsToLoadedWindow(sorted)

        assertEquals(listOf("join", "leave"), ids(result))
    }

    @Test
    fun `never drops chat messages even below the frontier`() {
        val sorted = listOf(
            msg("m0", kind = 9, createdAt = 50),
            msg("oldJoin", kind = 9021, createdAt = 60),
            msg("m1", kind = 9, createdAt = 200),
        )

        val result = clampSystemEventsToLoadedWindow(sorted)

        // The frontier is m0 (oldest kind:9), so oldJoin at 60 stays (>= 50) and all kind:9 stay.
        assertTrue(result.all { it.kind == 9 || it.createdAt >= 50 })
        assertEquals(listOf("m0", "oldJoin", "m1"), ids(result))
    }
}
