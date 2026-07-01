package org.nostr.nostrord.ui

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.ui.screens.group.buildThreadSummaries
import kotlin.test.Test
import kotlin.test.assertEquals

class ThreadsViewModelTest {
    private fun root(id: String, createdAt: Long, content: String, subject: String? = null) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "author_$id",
        content = content,
        createdAt = createdAt,
        kind = 11,
        tags = if (subject != null) listOf(listOf("subject", subject)) else emptyList(),
    )

    private fun reply(id: String, rootId: String, pubkey: String, createdAt: Long) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = pubkey,
        content = "re",
        createdAt = createdAt,
        kind = 1111,
        tags = listOf(listOf("E", rootId, "", "author_$rootId")),
    )

    @Test
    fun `summary counts replies by root, takes last activity and subject title`() {
        val roots = listOf(root("a", 100, "Hello\nworld", subject = "My title"))
        val replies = listOf(
            reply("r1", "a", "p1", 150),
            reply("r2", "a", "p1", 120),
            reply("r3", "b", "p2", 999), // belongs to a different root, excluded
        )
        val s = buildThreadSummaries(roots, replies).single()
        assertEquals("a", s.rootId)
        assertEquals(2, s.replyCount)
        assertEquals(150, s.lastActivity)
        assertEquals("My title", s.title)
        assertEquals("Hello", s.preview)
        assertEquals(listOf("p1"), s.replierPubkeys) // deduped
    }

    @Test
    fun `title falls back to the first non-blank content line when no subject`() {
        val s = buildThreadSummaries(listOf(root("a", 1, "\n  First line  \nsecond")), emptyList()).single()
        assertEquals("First line", s.title)
    }

    @Test
    fun `a thread with no replies uses the root timestamp as last activity`() {
        val s = buildThreadSummaries(listOf(root("a", 100, "x")), emptyList()).single()
        assertEquals(0, s.replyCount)
        assertEquals(100, s.lastActivity)
    }

    @Test
    fun `threads sort by last activity, newest first`() {
        val roots = listOf(root("a", 100, "a"), root("b", 90, "b"))
        // Only a has a reply -> a is most recent.
        assertEquals(
            listOf("a", "b"),
            buildThreadSummaries(roots, listOf(reply("r1", "a", "p", 110))).map { it.rootId },
        )
        // A newer reply on b floats it above a.
        assertEquals(
            listOf("b", "a"),
            buildThreadSummaries(
                roots,
                listOf(reply("r1", "a", "p", 110), reply("r2", "b", "p", 200)),
            ).map { it.rootId },
        )
    }
}
