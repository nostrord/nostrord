package org.nostr.nostrord.utils

import org.nostr.nostrord.network.NostrGroupClient
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSearchTest {
    private fun msg(id: String, content: String, kind: Int = 9) = NostrGroupClient.NostrMessage(
        id = id,
        pubkey = "pk",
        content = content,
        createdAt = 0L,
        kind = kind,
    )

    private val messages = listOf(
        msg("a", "Hello world"),
        msg("b", "another message"),
        msg("c", "WORLD domination"),
        msg("d", "join request", kind = 9021),
    )

    @Test
    fun shortQueryReturnsNothing() {
        assertEquals(emptyList(), ChatSearch.matchingIds(messages, "w"))
        assertEquals(emptyList(), ChatSearch.matchingIds(messages, " "))
    }

    @Test
    fun matchesAreCaseInsensitiveAndInListOrder() {
        assertEquals(listOf("a", "c"), ChatSearch.matchingIds(messages, "world"))
    }

    @Test
    fun onlyKind9IsSearchable() {
        // "join" appears only in the kind:9021 message, which is not searchable text.
        assertEquals(emptyList(), ChatSearch.matchingIds(messages, "join"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertEquals(emptyList(), ChatSearch.matchingIds(messages, "zzz"))
    }

    @Test
    fun stepWrapsAround() {
        assertEquals(0, ChatSearch.step(2, 3, +1))
        assertEquals(2, ChatSearch.step(0, 3, -1))
        assertEquals(1, ChatSearch.step(0, 3, +1))
        // Empty list never throws.
        assertEquals(0, ChatSearch.step(0, 0, +1))
    }

    @Test
    fun cursorDefaultsToMostRecentAndNumbersInverted() {
        val ids = listOf("a", "b", "c") // oldest -> newest
        // No anchor: lands on the newest (last) and shows 1.
        ChatSearch.cursor(ids, null).let {
            assertEquals(2, it.index)
            assertEquals("c", it.currentId)
            assertEquals(1, it.position)
        }
        // Oldest match is the highest number.
        ChatSearch.cursor(ids, "a").let {
            assertEquals(0, it.index)
            assertEquals("a", it.currentId)
            assertEquals(3, it.position)
        }
        // Unknown / empty anchor also falls back to most recent.
        assertEquals(ChatSearch.cursor(ids, null), ChatSearch.cursor(ids, "zzz"))
        // Empty list: no cursor.
        assertEquals(ChatSearch.Cursor(-1, null, 0), ChatSearch.cursor(emptyList(), "a"))
    }

    @Test
    fun indexedCursorMatchesListCursor() {
        // The O(1) overload (indexById map) must resolve identically to the O(matches) list overload,
        // including the most-recent fallback for a null / unknown anchor.
        val ids = listOf("a", "b", "c")
        val index = ChatSearch.indexById(ids)
        assertEquals(ChatSearch.cursor(ids, null), ChatSearch.cursor(ids, index, null))
        assertEquals(ChatSearch.cursor(ids, "a"), ChatSearch.cursor(ids, index, "a"))
        assertEquals(ChatSearch.cursor(ids, "zzz"), ChatSearch.cursor(ids, index, "zzz"))
        assertEquals(
            ChatSearch.cursor(emptyList(), "a"),
            ChatSearch.cursor(emptyList(), ChatSearch.indexById(emptyList()), "a"),
        )
    }

    @Test
    fun searchableTextLeavesPlainContentUntouched() {
        // No nostr: references, so the resolvers are never consulted and the content is returned as is.
        val text = "just a plain message"
        assertEquals(text, ChatSearch.searchableText(text, resolveMention = { null }, resolveQuote = { null }))
    }

    @Test
    fun searchableTextExtractorMatchesResolvedText() {
        // A message whose raw content is only a mention reference; the UI resolves it to a
        // display name. The query matches the resolved text, not the raw npub.
        val withMention = msg("e", "hi nostr:npub1xyz")
        val resolved = { m: NostrGroupClient.NostrMessage ->
            if (m.id == "e") "hi @Alice Wonderland" else m.content
        }
        // "wonderland" exists only in the resolved text, never in the raw content.
        assertEquals(listOf("e"), ChatSearch.matchingIds(listOf(withMention), "wonderland", resolved))
        // With the default content-only extractor the same query finds nothing.
        assertEquals(emptyList(), ChatSearch.matchingIds(listOf(withMention), "wonderland"))
    }
}
