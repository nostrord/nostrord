package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMembers
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.addLeftGroupForRelay
import org.nostr.nostrord.storage.getLeftGroupsForRelay
import org.nostr.nostrord.storage.removeLeftGroupForRelay
import org.nostr.nostrord.utils.epochSeconds
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Regression guards for the durable-left-marker work (the membership-lifecycle redesign Phase 1).
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLeftMarkerTest {
    private val pubkey = "00000000000000000000000000000000000000000000000000000000deadbeef"
    private val relay = "wss://test.relay"
    private val groupA = "left-marker-group-a"
    private val groupB = "left-marker-group-b"

    private fun makeManager(scope: TestScope): GroupManager =
        GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    private fun reset() {
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, emptySet())
        SecureStorage.removeLeftGroupForRelay(pubkey, relay, groupA)
        SecureStorage.removeLeftGroupForRelay(pubkey, relay, groupB)
    }

    @BeforeTest fun before() = reset()

    @AfterTest fun after() = reset()

    @Test
    fun `loadAll restores left markers into the flow without filtering joined`() = runTest {
        val scope = TestScope(testScheduler)
        // Persist: group A is BOTH joined and left (a stale marker on a rejoined group); B is just left.
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, setOf(groupA))
        SecureStorage.addLeftGroupForRelay(pubkey, relay, groupA, nowSeconds = epochSeconds())
        SecureStorage.addLeftGroupForRelay(pubkey, relay, groupB, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadJoinedGroupsFromStorage(pubkey, relay)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()

        // Both markers restored into the flow (drives the membership NONE override).
        assertTrue(groupA in gm.leftGroups.value)
        assertTrue(groupB in gm.leftGroups.value)
        // ...but the joined set is NOT filtered against them: a still-joined group must keep its
        // subscription (the regression where a rejoined group lost its live messages).
        assertTrue(groupA in gm.getGroupIdsForMux(relay), "a joined group must stay in the mux even with a stale left marker")

        scope.cancel()
    }

    @Test
    fun `handleGroupMembers self-heals a stale left marker when joined and listed as member`() = runTest {
        val scope = TestScope(testScheduler)
        SecureStorage.saveJoinedGroupsForRelay(pubkey, relay, setOf(groupA))
        SecureStorage.addLeftGroupForRelay(pubkey, relay, groupA, nowSeconds = epochSeconds())

        val gm = makeManager(scope)
        gm.setCurrentPubkey(pubkey)
        gm.loadJoinedGroupsFromStorage(pubkey, relay)
        gm.loadAllJoinedGroupsFromStorage(pubkey, listOf(relay))
        testScheduler.advanceUntilIdle()
        assertTrue(groupA in gm.leftGroups.value)

        // The relay confirms us as a member AND we are joined -> the marker is stale (we rejoined).
        gm.handleGroupMembers(GroupMembers(groupA, listOf(pubkey)), createdAt = 200)
        testScheduler.advanceUntilIdle()

        assertFalse(groupA in gm.leftGroups.value, "self-heal clears the stale marker in memory")
        assertEquals(emptyMap(), SecureStorage.getLeftGroupsForRelay(pubkey, relay, nowSeconds = epochSeconds()), "and in storage")

        scope.cancel()
    }
}
