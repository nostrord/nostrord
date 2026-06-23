package org.nostr.nostrord.storage.cache

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CacheStoreDmTest {
    private fun dm(id: String, peer: String, content: String = "hi") = CachedMsg(id, peer, peer, 1, DM_CACHE_KIND, content, "[]")

    private fun groupMsg(id: String, group: String, content: String = "yo") = CachedMsg(id, group, "bob", 1, 9, content, "[]")

    @Test
    fun `loadByKind returns only the requested kind across peers`() = runTest {
        val store = InMemoryCacheStore()
        store.upsertMessages("acct", "peerA", listOf(dm("d1", "peerA")))
        store.upsertMessages("acct", "peerB", listOf(dm("d2", "peerB")))
        store.upsertMessages("acct", "groupX", listOf(groupMsg("g1", "groupX")))

        assertEquals(setOf("d1", "d2"), store.loadByKind("acct", DM_CACHE_KIND).map { it.id }.toSet())
        assertEquals(listOf("g1"), store.loadByKind("acct", 9).map { it.id })
        assertEquals(emptyList(), store.loadByKind("other", DM_CACHE_KIND).map { it.id })
    }

    @Test
    fun `eviction never drops DMs but trims group messages`() = runTest {
        val store = InMemoryCacheStore()
        store.upsertMessages("acct", "groupX", listOf(groupMsg("g1", "groupX", "x".repeat(1000))))
        store.upsertMessages("acct", "peerA", listOf(dm("d1", "peerA", "x".repeat(1000))))

        store.evictToByteBudget("acct", maxBytes = 100)

        assertEquals(emptyList(), store.loadLatest("acct", "groupX", 100).map { it.id })
        assertEquals(listOf("d1"), store.loadByKind("acct", DM_CACHE_KIND).map { it.id })
    }
}
