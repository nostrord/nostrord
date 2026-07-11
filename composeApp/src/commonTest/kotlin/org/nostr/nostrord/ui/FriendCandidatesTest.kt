package org.nostr.nostrord.ui

import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.screens.group.buildFriendCandidates
import org.nostr.nostrord.ui.screens.group.filterFriendCandidates
import kotlin.test.Test
import kotlin.test.assertEquals

// Add-member friend picker derivation, shared by the Compose and web modals.
class FriendCandidatesTest {
    private fun meta(pubkey: String, name: String? = null, displayName: String? = null, picture: String? = null) = UserMetadata(
        pubkey = pubkey,
        name = name,
        displayName = displayName,
        picture = picture,
        about = null,
        nip05 = null,
    )

    @Test
    fun `named follows sort first alphabetically, unnamed after, displayName wins`() {
        val following = setOf("pk-zoe", "pk-anon", "pk-bob")
        val metadata = mapOf(
            "pk-zoe" to meta("pk-zoe", name = "zoe"),
            "pk-bob" to meta("pk-bob", name = "ignored", displayName = "Bob"),
        )
        val candidates = buildFriendCandidates(following, metadata)
        assertEquals(listOf("Bob", "zoe", null), candidates.map { it.name })
        assertEquals("pk-anon", candidates.last().pubkey)
    }

    @Test
    fun `filter matches name case-insensitively and pubkey prefix, blank returns all`() {
        val candidates = buildFriendCandidates(
            setOf("abc123", "def456"),
            mapOf("abc123" to meta("abc123", name = "Alice")),
        )
        assertEquals(2, filterFriendCandidates(candidates, "  ").size)
        assertEquals(listOf("abc123"), filterFriendCandidates(candidates, "aLiCe").map { it.pubkey })
        assertEquals(listOf("def456"), filterFriendCandidates(candidates, "def").map { it.pubkey })
        assertEquals(emptyList(), filterFriendCandidates(candidates, "nobody").map { it.pubkey })
    }
}
