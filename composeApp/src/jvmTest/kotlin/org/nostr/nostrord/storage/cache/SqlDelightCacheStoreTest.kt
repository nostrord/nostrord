package org.nostr.nostrord.storage.cache

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.storage.cache.db.CacheDb
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Exercises the real SQLDelight queries against an in-memory SQLite, which is the same
// generated SQL the Android/iOS native backends run.
class SqlDelightCacheStoreTest {
    private val account = "acct-1"
    private val group = "g1"

    private fun newStore(): SqlDelightCacheStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), CacheDb.Schema)
        return SqlDelightCacheStore(driver)
    }

    private fun msg(id: String, createdAt: Long) = CachedMsg(id = id, groupId = group, pubkey = "p", createdAt = createdAt, kind = 9, content = "c-$id", tagsJson = "[]")

    @Test
    fun `loadLatest returns the most recent messages oldest-first, bounded by limit`() = runTest {
        val store = newStore()
        store.upsertMessages(account, group, (1L..10L).map { msg("m$it", it) })
        assertEquals(listOf("m8", "m9", "m10"), store.loadLatest(account, group, 3).map { it.id })
    }

    @Test
    fun `loadBefore paginates older messages oldest-first and oldestCreatedAt is correct`() = runTest {
        val store = newStore()
        store.upsertMessages(account, group, (1L..10L).map { msg("m$it", it) })
        assertEquals(listOf("m5", "m6", "m7"), store.loadBefore(account, group, 8, 3).map { it.id })
        assertEquals(1L, store.oldestCreatedAt(account, group))
        assertNull(store.oldestCreatedAt(account, "empty-group"))
    }

    @Test
    fun `upsert is idempotent by id`() = runTest {
        val store = newStore()
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        assertEquals(1, store.loadLatest(account, group, 100).size)
    }

    @Test
    fun `events round-trip by id and batch, and account isolation holds`() = runTest {
        val store = newStore()
        val e = CachedEventRow(id = "e1", pubkey = "p", createdAt = 5, kind = 1, content = "note", tagsJson = "[]")
        store.upsertEvents(account, listOf(e))
        assertEquals(e, store.getEvent(account, "e1"))
        assertEquals(listOf(e), store.getEvents(account, listOf("e1", "missing")))
        assertNull(store.getEvent("other-acct", "e1"))
    }

    @Test
    fun `clearAccount wipes only one account`() = runTest {
        val store = newStore()
        store.upsertMessages(account, group, listOf(msg("m1", 1)))
        store.upsertMessages("acct-2", group, listOf(msg("m2", 2)))
        store.clearAccount(account)
        assertTrue(store.loadLatest(account, group, 100).isEmpty())
        assertEquals(listOf("m2"), store.loadLatest("acct-2", group, 100).map { it.id })
    }

    @Test
    fun `evictToByteBudget drops the oldest rows across messages and events`() = runTest {
        val store = newStore()
        store.upsertMessages(account, group, (1L..5L).map { msg("m$it", it) })
        val perRow = "c-m1".length + "[]".length + 120L
        store.evictToByteBudget(account, maxBytes = perRow * 2)
        assertEquals(listOf("m4", "m5"), store.loadLatest(account, group, 100).map { it.id })
    }
}
