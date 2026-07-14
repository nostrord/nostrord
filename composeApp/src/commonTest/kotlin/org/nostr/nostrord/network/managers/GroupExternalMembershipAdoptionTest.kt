package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.getPendingGroupInvitesFor
import org.nostr.nostrord.storage.getPutUserCursorForRelay
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.storage.savePendingGroupInvitesFor
import org.nostr.nostrord.storage.savePutUserCursorForRelay
import org.nostr.nostrord.utils.epochSeconds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Regression guards for a membership granted by an admin's kind:9000 (put-user): the add
// becomes a PENDING invite (never a silent adoption — a third party must not be able to
// make this client publish a kind:10009 naming their group). Accepting adopts it into the
// joined set (group list, mux, Leave); declining routes through leaveGroup, whose durable
// left marker keeps the relay's recurring 39002 from resurrecting the invite.
@OptIn(ExperimentalCoroutinesApi::class)
class GroupExternalMembershipAdoptionTest {
    private val pubkey = "00000000000000000000000000000000000000000000000000000000deadbeef"
    private val relay = "wss://test.relay"
    private val group = "external-add-group"

    private fun makeManager(scope: TestScope): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    private fun reset() {
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, emptySet())
        SecureStorage.removeLeftGroupForRelay(pubkey, relay, group)
        SecureStorage.savePendingGroupInvitesFor(pubkey, emptyList())
        SecureStorage.savePutUserCursorForRelay(pubkey, relay, 0L)
    }

    @BeforeTest fun before() = reset()

    @AfterTest fun after() = reset()

    @Test
    fun `39002 listing self registers a pending invite without adopting`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey, "someone-else")), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        val invite = gm.pendingGroupInvites.value[group]
        assertEquals(relay, invite?.relayUrl, "the add lands as a pending invite")
        assertEquals(null, invite?.actorPubkey, "39002-inferred invites carry no actor")
        assertFalse(group in gm.getGroupIdsForMux(relay), "not adopted into the mux until accepted")
        assertFalse(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "not persisted as joined until accepted",
        )
        assertTrue(
            SecureStorage.getPendingGroupInvitesFor(pubkey).any { it.groupId == group },
            "pending invite is persisted so it survives a restart",
        )

        scope.cancel()
    }

    @Test
    fun `accepting a pending invite adopts the membership into the joined set and storage`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        gm.acceptPendingInvite(group)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value, "accepted invite leaves the pending set")
        assertTrue(group in gm.getGroupIdsForMux(relay), "adopted group joins the mux for its relay")
        assertTrue(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "adopted group is persisted so it survives a restart",
        )

        scope.cancel()
    }

    @Test
    fun `durable left marker blocks the invite - a stale relay listing must not resurrect a left group`() = runTest {
        val scope = TestScope(testScheduler)
        SecureStorage.addLeftGroupForRelay(pubkey, relay, group, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.leftGroups.value)

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value, "left group never re-enters the pending set")
        assertFalse(group in gm.getGroupIdsForMux(relay), "left group stays out of the mux")
        assertFalse(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "left group is not re-persisted as joined",
        )

        scope.cancel()
    }

    @Test
    fun `live kind-9000 targeting self registers the invite with its actor and advances the watch cursor`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        val createdAt = 1_000_000L
        val msg = NostrGroupClient.NostrMessage(
            id = "evt-put-user-1",
            pubkey = "admin-pubkey",
            content = "",
            createdAt = createdAt,
            kind = 9000,
            tags = listOf(listOf("p", pubkey), listOf("h", group)),
        )
        val raw = """["EVENT","mux_padd_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
        gm.handleMessage(msg, raw, subscriptionId = "mux_padd_x", relayUrl = relay)
        testScheduler.advanceUntilIdle()

        val invite = gm.pendingGroupInvites.value[group]
        assertEquals("admin-pubkey", invite?.actorPubkey, "put-user invite records who added us")
        assertFalse(group in gm.getGroupIdsForMux(relay), "not adopted into the mux until accepted")
        assertEquals(
            createdAt,
            SecureStorage.getPutUserCursorForRelay(pubkey, relay),
            "cursor advances so the add is not replayed on the next REQ",
        )

        scope.cancel()
    }

    @Test
    fun `declining via leaveGroup drops the invite and blocks re-registration`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.pendingGroupInvites.value)

        gm.leaveGroup(group, pubkey, relay, signEvent = { it }, publishJoinedGroups = {})
        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value, "declined invite leaves the pending set")

        // The relay still lists us (its member removal races the 9022): must not re-prompt.
        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 200, relayUrl = relay)
        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value, "left marker blocks 39002 re-registration")

        scope.cancel()
    }

    @Test
    fun `a put-user newer than the leave re-opens the invite - a stale one does not`() = runTest {
        val scope = TestScope(testScheduler)
        // Durable marker from a past-session leave (leaveGroup itself also arms the 5s
        // isRecentlyLeft churn guard, which would mask the marker logic under test).
        val leftAt = epochSeconds()
        SecureStorage.addLeftGroupForRelay(pubkey, relay, group, nowSeconds = leftAt)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.leftGroups.value)

        fun putUser(createdAt: Long) {
            val msg = NostrGroupClient.NostrMessage(
                id = "evt-readd-$createdAt",
                pubkey = "admin-pubkey",
                content = "",
                createdAt = createdAt,
                kind = 9000,
                tags = listOf(listOf("p", pubkey), listOf("h", group)),
            )
            val raw = """["EVENT","mux_padd_x",{"tags":[["p","$pubkey"],["h","$group"]]}]"""
            gm.handleMessage(msg, raw, subscriptionId = "mux_padd_x", relayUrl = relay)
        }

        // Historical replay from before the leave: stays blocked.
        putUser(leftAt - 100)
        testScheduler.advanceUntilIdle()
        assertFalse(group in gm.pendingGroupInvites.value, "a put-user older than the leave stays blocked")

        // Deliberate re-add after the leave: re-opens the invite and clears the marker.
        putUser(leftAt + 100)
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.pendingGroupInvites.value, "a fresh put-user re-opens the invite")
        assertFalse(group in gm.leftGroups.value, "the durable left marker is cleared")

        scope.cancel()
    }

    @Test
    fun `pending invite is restored from storage on the next session`() = runTest {
        val scope = TestScope(testScheduler)
        val first = makeManager(scope)
        first.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()
        first.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        val second = makeManager(scope)
        second.setCurrentPubkey(pubkey)
        second.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()

        assertTrue(group in second.pendingGroupInvites.value, "undecided invite survives a restart")

        scope.cancel()
    }

    @Test
    fun `39002 without self does not register an invite`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf("someone-else")), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.pendingGroupInvites.value)
        assertFalse(group in gm.getGroupIdsForMux(relay))
        assertFalse(group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay))

        scope.cancel()
    }
}
