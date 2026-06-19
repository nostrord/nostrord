package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.GroupMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// normalizeRelayUrl trims trailing slashes and lowercases the host, so
// "wss://example.com/" → "wss://example.com". Tests must use the same canonical form
// that the production code will store and look up.
private const val RELAY_A = "wss://example.com"
private const val RELAY_B = "wss://other.relay"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupManagerRemoveRelayTest {
    private fun makeManager(scope: TestScope): GroupManager {
        val connManager = ConnectionManager(scope)
        return GroupManager(connectionManager = connManager, scope = scope)
    }

    @Test
    fun `removeRelayEntry removes relay from groupsByRelay`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        manager.prePopulateRelayList(listOf(RELAY_A))
        assertTrue(
            manager.groupsByRelay.value.containsKey(RELAY_A),
            "groupsByRelay should contain the relay after pre-population; keys=${manager.groupsByRelay.value.keys}",
        )

        manager.removeRelayEntry(RELAY_A)
        assertFalse(
            manager.groupsByRelay.value.containsKey(RELAY_A),
            "groupsByRelay should not contain the relay after removal",
        )

        scope.cancel()
    }

    @Test
    fun `removeRelayEntry removes relay from joinedGroupsByRelay`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        manager.updateAllRelayJoinedGroups(
            mapOf(
                RELAY_A to setOf("group-1", "group-2"),
                RELAY_B to setOf("group-3"),
            ),
        )

        assertTrue(
            manager.joinedGroupsByRelay.value.containsKey(RELAY_A),
            "Relay should be present before removal; keys=${manager.joinedGroupsByRelay.value.keys}",
        )

        manager.removeRelayEntry(RELAY_A)

        assertFalse(
            manager.joinedGroupsByRelay.value.containsKey(RELAY_A),
            "joinedGroupsByRelay should not contain the removed relay",
        )
        assertTrue(
            manager.joinedGroupsByRelay.value.containsKey(RELAY_B),
            "Other relay should remain untouched",
        )

        scope.cancel()
    }

    @Test
    fun `removeRelayEntry with trailing slash normalizes correctly`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        manager.updateAllRelayJoinedGroups(mapOf(RELAY_A to setOf("group-1")))

        // Pass URL with trailing slash — normalizeRelayUrl() strips it, so lookup must still work
        manager.removeRelayEntry("$RELAY_A/")

        assertNull(
            manager.joinedGroupsByRelay.value[RELAY_A],
            "Relay entry should be gone regardless of trailing slash",
        )

        scope.cancel()
    }

    @Test
    fun `removeRelayEntry on unknown relay is a no-op`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        manager.updateAllRelayJoinedGroups(mapOf(RELAY_B to setOf("group-1")))

        manager.removeRelayEntry("wss://unknown.relay")

        assertTrue(
            manager.joinedGroupsByRelay.value.containsKey(RELAY_B),
            "Other relays should not be affected by removal of an unknown relay",
        )

        scope.cancel()
    }

    private fun gm(id: String, name: String) = GroupMetadata(id = id, name = name, about = null, picture = null, isPublic = true, isOpen = true)

    @Test
    fun `autoForgettableOrphans returns an orphan among real groups on a relay`() = runTest {
        // A joined group with no kind:39000 after the relay finished its list (EOSE) is a
        // deleted / mis-relayed orphan and is safe to forget when the relay served metadata
        // for its other joined groups.
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)
        manager.updateAllRelayJoinedGroups(mapOf(RELAY_A to setOf("real", "ghost")))
        manager.handleGroupMetadata(gm("real", "Real"), RELAY_A)
        manager.handleEoseSuspend("group-list", RELAY_A)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(mapOf(RELAY_A to setOf("ghost")), manager.autoForgettableOrphans())
        scope.cancel()
    }

    @Test
    fun `autoForgettableOrphans skips a relay where every joined group is an orphan`() = runTest {
        // Glitch guard: a relay that EOSEs without metadata for ALL its joined groups is a
        // transient data loss, not a real deletion, so it must not wipe the user's groups.
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)
        manager.updateAllRelayJoinedGroups(mapOf(RELAY_B to setOf("only")))
        manager.handleEoseSuspend("group-list", RELAY_B)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(emptyMap(), manager.autoForgettableOrphans())
        scope.cancel()
    }

    @Test
    fun `autoForgettableOrphans skips a recently-joined group whose metadata is still in flight`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)
        manager.updateAllRelayJoinedGroups(mapOf(RELAY_A to setOf("real", "fresh")))
        manager.handleGroupMetadata(gm("real", "Real"), RELAY_A)
        manager.markRecentlyJoined("fresh")
        manager.handleEoseSuspend("group-list", RELAY_A)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(emptyMap(), manager.autoForgettableOrphans())
        scope.cancel()
    }

    @Test
    fun `updateAllRelayJoinedGroups overwrites a relay to empty so a left group is dropped`() = runTest {
        // The kind:10009 reconciliation (OutboxManager.handleKind10009Event) passes an empty
        // set for a relay whose last group was left on another device. This must OVERWRITE
        // (not union) the relay's set, or the left group lingers and the next publish
        // resurrects it. Guards the contract that the OutboxManager fix relies on.
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)

        manager.updateAllRelayJoinedGroups(
            mapOf(RELAY_A to setOf("group-1"), RELAY_B to setOf("group-2")),
        )
        assertTrue(manager.joinedGroupsByRelay.value[RELAY_A]?.contains("group-1") == true)

        // Authoritative newest event no longer lists RELAY_A's group: pass it as empty.
        manager.updateAllRelayJoinedGroups(mapOf(RELAY_A to emptySet()))

        assertTrue(
            manager.joinedGroupsByRelay.value[RELAY_A].isNullOrEmpty(),
            "Left group must be dropped, not unioned; got=${manager.joinedGroupsByRelay.value[RELAY_A]}",
        )
        assertTrue(
            manager.joinedGroupsByRelay.value[RELAY_B]?.contains("group-2") == true,
            "Relays absent from the update stay untouched",
        )

        scope.cancel()
    }
}
