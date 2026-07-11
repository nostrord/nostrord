package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.getPutUserCursorForRelay
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.utils.epochSeconds
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Regression guards for adopting a membership granted by an admin's kind:9000 (put-user):
// the relay lists us in kind:39002 without any kind:9021 of ours, and the group must still
// land in the joined set (group list, mux, Leave) instead of existing only relay-side.
@OptIn(ExperimentalCoroutinesApi::class)
class GroupExternalMembershipAdoptionTest {
    private val pubkey = "00000000000000000000000000000000000000000000000000000000deadbeef"
    private val relay = "wss://test.relay"
    private val group = "external-add-group"

    private fun makeManager(scope: TestScope): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    private fun reset() {
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, emptySet())
        SecureStorage.removeLeftGroupForRelay(pubkey, relay, group)
    }

    @BeforeTest fun before() = reset()

    @AfterTest fun after() = reset()

    @Test
    fun `39002 listing self adopts the membership into the joined set and storage`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey, "someone-else")), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertTrue(group in gm.getGroupIdsForMux(relay), "adopted group joins the mux for its relay")
        assertTrue(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "adopted group is persisted so it survives a restart",
        )

        scope.cancel()
    }

    @Test
    fun `durable left marker blocks adoption - a stale relay listing must not resurrect a left group`() = runTest {
        val scope = TestScope(testScheduler)
        SecureStorage.addLeftGroupForRelay(pubkey, relay, group, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(group in gm.leftGroups.value)

        gm.handleGroupMembers(GroupMembers(group, listOf(pubkey)), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.getGroupIdsForMux(relay), "left group stays out of the mux")
        assertFalse(
            group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay),
            "left group is not re-persisted as joined",
        )

        scope.cancel()
    }

    @Test
    fun `live kind-9000 targeting self adopts and advances the watch cursor`() = runTest {
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

        assertTrue(group in gm.getGroupIdsForMux(relay), "put-user targeting self adopts the group")
        assertEquals(
            createdAt,
            SecureStorage.getPutUserCursorForRelay(pubkey, relay),
            "cursor advances so the add is not replayed on the next REQ",
        )

        scope.cancel()
    }

    @Test
    fun `39002 without self does not adopt`() = runTest {
        val scope = TestScope(testScheduler)
        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        testScheduler.advanceUntilIdle()

        gm.handleGroupMembers(GroupMembers(group, listOf("someone-else")), createdAt = 100, relayUrl = relay)
        testScheduler.advanceUntilIdle()

        assertFalse(group in gm.getGroupIdsForMux(relay))
        assertFalse(group in SecureStorage.getJoinedGroupsForRelay(pubkey, relay))

        scope.cancel()
    }
}
