package org.nostr.nostrord.ui

import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.ui.screens.group.pendingJoinRequests
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingJoinRequestsTest {
    private fun joinRequest(pubkey: String, at: Long) = NostrMessage(id = "9021-$pubkey-$at", pubkey = pubkey, content = "/join", createdAt = at, kind = 9021)

    private fun leave(pubkey: String, at: Long) = NostrMessage(id = "9022-$pubkey-$at", pubkey = pubkey, content = "", createdAt = at, kind = 9022)

    // Admin remove (kind 9001): authored by the admin, the removed user is the `p` tag.
    private fun remove(target: String, admin: String, at: Long) = NostrMessage(id = "9001-$target-$at", pubkey = admin, content = "", createdAt = at, kind = 9001, tags = listOf(listOf("p", target)))

    @Test
    fun pendingExcludesCurrentMembers() {
        val msgs = listOf(joinRequest("alice", 100))
        assertEquals(emptyList(), pendingJoinRequests(msgs, members = setOf("alice")))
    }

    @Test
    fun pendingShowsNonMemberRequest() {
        val msgs = listOf(joinRequest("alice", 100))
        assertEquals(listOf("alice"), pendingJoinRequests(msgs, members = emptySet()).map { it.pubkey })
    }

    @Test
    fun selfLeaveAfterRequestSuppressesIt() {
        val msgs = listOf(joinRequest("alice", 100), leave("alice", 200))
        assertEquals(emptyList(), pendingJoinRequests(msgs, members = emptySet()))
    }

    @Test
    fun adminRemovalAfterApprovalDoesNotResurrectRequest() {
        // alice requested, was approved (now a non-member after removal), then an admin removed her.
        // Her stale 9021 must NOT reappear just because she's no longer a member.
        val msgs = listOf(joinRequest("alice", 100), remove(target = "alice", admin = "admin", at = 300))
        assertEquals(emptyList(), pendingJoinRequests(msgs, members = emptySet()))
    }

    @Test
    fun freshRequestAfterRemovalReappears() {
        // A genuine re-request sent after removal is newer than the removal, so it is pending again.
        val msgs = listOf(joinRequest("alice", 100), remove(target = "alice", admin = "admin", at = 300), joinRequest("alice", 400))
        assertEquals(listOf("alice"), pendingJoinRequests(msgs, members = emptySet()).map { it.pubkey })
    }

    @Test
    fun newestFirstAndDeduplicatedByPubkey() {
        val msgs =
            listOf(
                joinRequest("alice", 100),
                joinRequest("alice", 150),
                joinRequest("bob", 200),
            )
        val result = pendingJoinRequests(msgs, members = emptySet())
        assertEquals(listOf("bob", "alice"), result.map { it.pubkey })
        assertEquals(2, result.size)
    }
}
