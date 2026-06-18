package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.cache.CachedMsg
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HYDRATE_PUBKEY = "00000000000000000000000000000000000000000000000000000000deadbeef"
private const val HYDRATE_GROUP = "cache-group"

@OptIn(ExperimentalCoroutinesApi::class)
class GroupCacheHydrationTest {
    @Test
    fun `opening a group renders its messages from the persistent cache`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        // A previously-seen group's history already sits in the cache (e.g. a prior session).
        cache.upsertMessages(
            HYDRATE_PUBKEY,
            HYDRATE_GROUP,
            listOf(
                CachedMsg("m1", HYDRATE_GROUP, "p", createdAt = 100, kind = 9, content = "hi", tagsJson = "[]"),
                CachedMsg("m2", HYDRATE_GROUP, "p", createdAt = 200, kind = 9, content = "there", tagsJson = "[]"),
            ),
        )
        val manager = GroupManager(connectionManager = ConnectionManager(scope), scope = scope, cacheStore = cache)
        manager.setCurrentPubkey(HYDRATE_PUBKEY)

        // Opening the group hydrates from cache with no relay round-trip.
        manager.setActiveGroupId(HYDRATE_GROUP)
        advanceUntilIdle()

        assertEquals(listOf("m1", "m2"), manager.getMessagesForGroup(HYDRATE_GROUP).map { it.id })

        scope.cancel()
    }
}
