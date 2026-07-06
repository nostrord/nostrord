package org.nostr.nostrord.storage.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryCacheStoreTest {
    private val account = "acct-1"
    private val group = "g1"

    private fun msg(id: String, createdAt: Long) = CachedMsg(id = id, groupId = group, pubkey = "p", createdAt = createdAt, kind = 9, content = "c-$id", tagsJson = "[]")

    @Test
    fun `loadLatest returns the most recent messages oldest-first bounded by limit`() = runTest {
        val store = InMemoryCacheStore()
        store.upsertMessages(account, group, (1L..10L).map { msg("m$it", it) })

        val latest = store.loadLatest(account, group, limit = 3)
        assertEquals(listOf("m8", "m9", "m10"), latest.map { it.id })
    }

    @Test
    fun `loadBefore paginates older messages oldest-first`() = runTest {
        val store = InMemoryCacheStore()
        store.upsertMessages(account, group, (1L..10L).map { msg("m$it", it) })

        val page = store.loadBefore(account, group, beforeCreatedAt = 8, limit = 3)
        assertEquals(listOf("m5", "m6", "m7"), page.map { it.id })
        assertEquals(1L, store.oldestCreatedAt(account, group))
    }

    @Test
    fun `upsert is idempotent by id`() = runTest {
        val store = InMemoryCacheStore()
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        assertEquals(1, store.loadLatest(account, group, 100).size)
    }

    @Test
    fun `events round-trip by id and batch`() = runTest {
        val store = InMemoryCacheStore()
        val e = CachedEventRow(id = "e1", pubkey = "p", createdAt = 5, kind = 1, content = "note", tagsJson = "[]")
        store.upsertEvents(account, listOf(e))
        assertEquals(e, store.getEvent(account, "e1"))
        assertEquals(listOf(e), store.getEvents(account, listOf("e1", "missing")))
    }

    @Test
    fun `accounts are isolated and clearAccount wipes only one`() = runTest {
        val store = InMemoryCacheStore()
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        store.upsertMessages("acct-2", group, listOf(msg("m2", 2)))

        store.clearAccount(account)
        assertTrue(store.loadLatest(account, group, 100).isEmpty())
        assertEquals(listOf("m2"), store.loadLatest("acct-2", group, 100).map { it.id })
    }

    @Test
    fun `evictToByteBudget drops the oldest rows first`() = runTest {
        val store = InMemoryCacheStore()
        // 5 messages; budget that fits roughly two of them forces eviction of the oldest.
        store.upsertMessages(account, group, (1L..5L).map { msg("m$it", it) })
        val perRow = "c-m1".length + "[]".length + 120L
        store.evictToByteBudget(account, maxBytes = perRow * 2)

        val remaining = store.loadLatest(account, group, 100).map { it.id }
        assertEquals(listOf("m4", "m5"), remaining)
        assertNull(store.loadBefore(account, group, beforeCreatedAt = 4, limit = 100).firstOrNull())
    }
}
