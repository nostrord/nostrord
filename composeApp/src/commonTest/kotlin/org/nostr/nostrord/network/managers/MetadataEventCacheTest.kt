package org.nostr.nostrord.network.managers

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.storage.cache.CachedEventRow
import org.nostr.nostrord.storage.cache.InMemoryCacheStore
import kotlin.test.Test
import kotlin.test.assertTrue

private const val EVENT_ACCOUNT = "0000000000000000000000000000000000000000000000000000000000c0ffee0"

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataEventCacheTest {
    @Test
    fun `requestEventById resolves a previously-seen event from the cache without a network fetch`() = runTest {
        val scope = TestScope(testScheduler)
        val cache = InMemoryCacheStore()
        // A quoted event was persisted in a prior session.
        cache.upsertEvents(
            EVENT_ACCOUNT,
            listOf(CachedEventRow(id = "e1", pubkey = "p", createdAt = 10, kind = 1, content = "quoted note", tagsJson = "[]")),
        )

        val conn = ConnectionManager(scope)
        val manager =
            MetadataManager(
                connectionManager = conn,
                outboxManager = OutboxManager(conn, RelayListManager(), scope),
                scope = scope,
                cacheStore = cache,
                accountProvider = { EVENT_ACCOUNT },
            )

        // No relay client exists; resolution must come from the disk cache.
        manager.requestEventById("e1") { _, _ -> }
        assertTrue(manager.hasCachedEvent("e1"), "event should be resolved from the persistent cache")

        scope.cancel()
    }
}
