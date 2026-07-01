package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.SecureStorage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EVICT_PUBKEY = "0000000000000000000000000000000000000000000000000000000000facade0"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageEvictionTest {
    private fun makeManager(scope: TestScope): GroupManager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope)

    // One persisted message so loadMessagesFromStorage populates the group in memory.
    private fun seedGroup(groupId: String) {
        val json = """[{"id":"m-$groupId","pubkey":"p","content":"hi","createdAt":1,"kind":9,"tags":[]}]"""
        SecureStorage.saveMessagesForGroup(EVICT_PUBKEY, groupId, json)
    }

    @AfterTest
    fun cleanup() = SecureStorage.clearAllMessagesForAccount(EVICT_PUBKEY)

    @Test
    fun `evicts least-recently-used groups past the in-memory cap, keeping the active one`() = runTest {
        val scope = TestScope(testScheduler)
        val manager = makeManager(scope)
        manager.setCurrentPubkey(EVICT_PUBKEY)

        val cap = GroupManager.MAX_GROUPS_IN_MEMORY
        val total = cap + 5
        // Load g1..gN (g1 becomes least-recent, gN most-recent). loadMessagesFromStorage
        // touches recency but does not evict, so all are hydrated after the loop.
        for (i in 1..total) {
            seedGroup("g$i")
            manager.loadMessagesFromStorage("g$i")
        }
        assertEquals(total, manager.messages.value.size, "all groups hydrated before eviction")

        // Opening the newest group recomputes the working set and evicts the overflow.
        manager.setActiveGroupId("g$total")

        assertEquals(cap, manager.messages.value.size, "in-memory groups capped at MAX_GROUPS_IN_MEMORY")
        assertTrue(manager.getMessagesForGroup("g1").isEmpty(), "the least-recent group is evicted")
        assertTrue(manager.getMessagesForGroup("g$total").isNotEmpty(), "the active group is retained")
        // Evicted history is persisted, so reopening hydrates instantly from disk.
        manager.loadMessagesFromStorage("g1")
        assertTrue(manager.getMessagesForGroup("g1").isNotEmpty(), "evicted group reloads from disk")

        scope.cancel()
    }
}
