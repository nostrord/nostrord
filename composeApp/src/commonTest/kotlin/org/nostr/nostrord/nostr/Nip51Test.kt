package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals

class Nip51Test {
    @Test
    fun `mutedPubkeysFrom picks p tags and ignores everything else`() {
        val tags = listOf(
            listOf("p", "aaa"),
            listOf("p", "bbb", "wss://relay.example.com"),
            listOf("word", "spam"),
            listOf("t", "hashtag"),
            listOf("e", "thread-id"),
            listOf("p", ""),
            listOf("p"),
        )
        assertEquals(setOf("aaa", "bbb"), Nip51.mutedPubkeysFrom(tags))
    }

    @Test
    fun `rebuildMuteTags replaces p tags and preserves foreign entries`() {
        val previous = listOf(
            listOf("p", "old-mute"),
            listOf("word", "spam"),
            listOf("t", "hashtag"),
            listOf("e", "thread-id"),
        )
        val rebuilt = Nip51.rebuildMuteTags(previous, setOf("new-mute"))
        assertEquals(
            listOf(
                listOf("word", "spam"),
                listOf("t", "hashtag"),
                listOf("e", "thread-id"),
                listOf("p", "new-mute"),
            ),
            rebuilt,
        )
    }

    @Test
    fun `rebuildMuteTags with an empty set keeps only foreign entries`() {
        val previous = listOf(
            listOf("p", "old-mute"),
            listOf("word", "spam"),
        )
        assertEquals(listOf(listOf("word", "spam")), Nip51.rebuildMuteTags(previous, emptySet()))
    }

    @Test
    fun `private section tags round-trip through encode and decode`() {
        val tags = listOf(
            listOf("p", "aaa"),
            listOf("word", "spam"),
        )
        assertEquals(tags, Nip51.decodeTags(Nip51.encodeTags(tags)))
    }

    @Test
    fun `decodeTags rejects non tag-array plaintext`() {
        assertEquals(null, Nip51.decodeTags("not json"))
        assertEquals(null, Nip51.decodeTags("""{"p":"aaa"}"""))
    }
}
