package org.nostr.nostrord.storage.cache

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.nostr.nostrord.storage.cache.db.CacheDb

/**
 * SQLDelight-backed [CacheStore], shared by the native targets (Android / JVM / iOS). Each
 * platform constructs the [SqlDriver] it needs (device SQLite, JDBC, or Kotlin/Native) and
 * hands it here; the query logic is identical because it all runs through the generated
 * [CacheDb]. Web does NOT use this — it has its own IndexedDB actual.
 *
 * Queries are blocking, so each op hops to [Dispatchers.Default] (the only IO-capable
 * dispatcher common to every native target). The driver's schema is created by the platform
 * factory before the store is used.
 */
class SqlDelightCacheStore(
    private val driver: SqlDriver,
) : CacheStore {
    private val db = CacheDb(driver)
    private val q = db.cacheDbQueries

    override suspend fun init() {
        // The driver factory creates the schema; nothing else to do here.
    }

    override suspend fun upsertMessages(
        account: String,
        groupId: String,
        messages: List<CachedMsg>,
    ) = withContext(Dispatchers.Default) {
        if (messages.isEmpty()) return@withContext
        db.transaction {
            messages.forEach { m ->
                q.upsertMessage(account, m.id, m.groupId, m.pubkey, m.createdAt, m.kind.toLong(), m.content, m.tagsJson)
            }
        }
    }

    override suspend fun loadLatest(
        account: String,
        groupId: String,
        limit: Int,
    ): List<CachedMsg> = withContext(Dispatchers.Default) {
        // Query is newest-first; reverse to oldest-first for the UI.
        q.loadLatest(account, groupId, limit.toLong()).executeAsList().map(::toCachedMsg).asReversed()
    }

    override suspend fun loadBefore(
        account: String,
        groupId: String,
        beforeCreatedAt: Long,
        limit: Int,
    ): List<CachedMsg> = withContext(Dispatchers.Default) {
        q.loadBefore(account, groupId, beforeCreatedAt, limit.toLong()).executeAsList().map(::toCachedMsg).asReversed()
    }

    override suspend fun oldestCreatedAt(
        account: String,
        groupId: String,
    ): Long? = withContext(Dispatchers.Default) {
        q.oldestMessageCreatedAt(account, groupId).executeAsOneOrNull()?.MIN
    }

    override suspend fun upsertEvents(
        account: String,
        events: List<CachedEventRow>,
    ) = withContext(Dispatchers.Default) {
        if (events.isEmpty()) return@withContext
        db.transaction {
            events.forEach { e ->
                q.upsertEvent(account, e.id, e.pubkey, e.createdAt, e.kind.toLong(), e.content, e.tagsJson)
            }
        }
    }

    override suspend fun getEvent(
        account: String,
        id: String,
    ): CachedEventRow? = withContext(Dispatchers.Default) {
        q.getEvent(account, id).executeAsOneOrNull()?.let { toEventRow(it.id, it.pubkey, it.created_at, it.kind, it.content, it.tags_json) }
    }

    override suspend fun getEvents(
        account: String,
        ids: List<String>,
    ): List<CachedEventRow> = withContext(Dispatchers.Default) {
        if (ids.isEmpty()) return@withContext emptyList()
        q.getEvents(account, ids).executeAsList().map { toEventRow(it.id, it.pubkey, it.created_at, it.kind, it.content, it.tags_json) }
    }

    override suspend fun evictToByteBudget(
        account: String,
        maxBytes: Long,
    ) = withContext(Dispatchers.Default) {
        val total = q.totalMessageBytes(account).executeAsOne() + q.totalEventBytes(account).executeAsOne()
        if (total <= maxBytes) return@withContext
        // Merge both tables' rows oldest-first and delete until under budget, in one transaction.
        val rows =
            (
                q.messageSizesOldestFirst(account).executeAsList().map { EvictRow(it.created_at, it.bytes, it.id, isMessage = true) } +
                    q.eventSizesOldestFirst(account).executeAsList().map { EvictRow(it.created_at, it.bytes, it.id, isMessage = false) }
                ).sortedBy { it.createdAt }
        var remaining = total
        db.transaction {
            for (row in rows) {
                if (remaining <= maxBytes) break
                if (row.isMessage) q.deleteMessageById(account, row.id) else q.deleteEventById(account, row.id)
                remaining -= row.bytes
            }
        }
    }

    override suspend fun clearAccount(account: String) = withContext(Dispatchers.Default) {
        db.transaction {
            q.clearMessagesForAccount(account)
            q.clearEventsForAccount(account)
        }
    }

    private fun toCachedMsg(m: org.nostr.nostrord.storage.cache.db.Message): CachedMsg = CachedMsg(
        id = m.id,
        groupId = m.group_id,
        pubkey = m.pubkey,
        createdAt = m.created_at,
        kind = m.kind.toInt(),
        content = m.content,
        tagsJson = m.tags_json,
    )

    private fun toEventRow(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Long,
        content: String,
        tagsJson: String,
    ): CachedEventRow = CachedEventRow(id = id, pubkey = pubkey, createdAt = createdAt, kind = kind.toInt(), content = content, tagsJson = tagsJson)

    private class EvictRow(
        val createdAt: Long,
        val bytes: Long,
        val id: String,
        val isMessage: Boolean,
    )
}
