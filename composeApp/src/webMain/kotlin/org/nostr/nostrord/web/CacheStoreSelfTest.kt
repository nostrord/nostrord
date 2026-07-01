package org.nostr.nostrord.web

import org.nostr.nostrord.storage.cache.CachedEventRow
import org.nostr.nostrord.storage.cache.CachedMsg
import org.nostr.nostrord.storage.cache.createCacheStore

/**
 * Browser self-test for the web [createCacheStore] (IndexedDB-backed). Run via `?cachetest` on
 * the web entry, it exercises the real store against a real IndexedDB so the cursor/key-range
 * semantics (which compilation can't check) are verified in a browser. Returns "PASS" or
 * "FAIL: <reason>". Uses the commonMain CacheStore seam, since webMain can't name the jsMain
 * IndexedDbCacheStore directly; on web the factory returns exactly that store.
 */
suspend fun runCacheStoreSelfTest(): String {
    val account = "__selftest__"
    val group = "g1"
    return try {
        val store = createCacheStore()
        store.init()
        store.clearAccount(account) // start clean even if a prior run left data

        fun msg(id: String, createdAt: Long) = CachedMsg(id = id, groupId = group, pubkey = "p", createdAt = createdAt, kind = 9, content = "c-$id", tagsJson = "[]")

        store.upsertMessages(account, group, (1L..10L).map { msg("m$it", it) })

        check(store.loadLatest(account, group, 3).map { it.id } == listOf("m8", "m9", "m10")) { "loadLatest order/limit" }
        check(store.loadBefore(account, group, 8, 3).map { it.id } == listOf("m5", "m6", "m7")) { "loadBefore page" }
        check(store.oldestCreatedAt(account, group) == 1L) { "oldestCreatedAt" }
        check(store.oldestCreatedAt(account, "empty") == null) { "oldestCreatedAt empty" }

        // Idempotent upsert.
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        check(store.loadLatest(account, group, 100).size == 10) { "upsert idempotent" }

        // Events (e1 newest so it survives eviction below).
        val event = CachedEventRow(id = "e1", pubkey = "p", createdAt = 100, kind = 1, content = "note", tagsJson = "[]")
        store.upsertEvents(account, listOf(event))
        check(store.getEvent(account, "e1") == event) { "getEvent round-trip" }
        check(store.getEvents(account, listOf("e1", "missing")) == listOf(event)) { "getEvents batch" }
        check(store.getEvent("other-account", "e1") == null) { "account isolation" }

        // Byte-budget eviction: a budget that holds ~2 rows (each ~126 bytes) keeps the two
        // newest overall (e1@100, m10@10) and drops the older messages.
        store.evictToByteBudget(account, 320)
        check(store.loadLatest(account, group, 100).map { it.id } == listOf("m10")) { "evict oldest-first" }
        check(store.getEvent(account, "e1") == event) { "evict keeps newest event" }

        store.clearAccount(account)
        check(store.loadLatest(account, group, 100).isEmpty()) { "clearAccount" }

        "PASS"
    } catch (e: Throwable) {
        "FAIL: ${e.message}"
    }
}
