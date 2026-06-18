package org.nostr.nostrord.storage.cache

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.json

/**
 * IndexedDB-backed [CacheStore] for the web. SQLDelight has no stable web driver and the app
 * is Kotlin/JS (not WASM), so web persists message/event history in IndexedDB object stores
 * with the same logical schema as the native SQLite: one store per entity, compound primary
 * key [account, id], and an index for range queries over created_at.
 *
 * NOTE: written and compiled on Linux, but IndexedDB semantics (cursors, key ranges) can only
 * be behavior-verified in a browser. Smoke-test on the dev server before relying on it.
 *
 * Kotlin/JS `Long` is a boxed object, not a JS number, so created_at is stored and queried as a
 * JS number (Double) and converted back at the boundary; nostr timestamps are seconds, well
 * within Double's exact-integer range.
 */
class IndexedDbCacheStore : CacheStore {
    private var cachedDb: dynamic = null

    override suspend fun init() {
        db()
    }

    private suspend fun db(): dynamic {
        val existing = cachedDb
        if (existing != null) return existing
        val idb = window.asDynamic().indexedDB
        val req = idb.open(DB_NAME, 1)
        req.onupgradeneeded = { _: dynamic ->
            val database = req.result
            if (!database.objectStoreNames.contains(MESSAGE).unsafeCast<Boolean>()) {
                val store = database.createObjectStore(MESSAGE, json("keyPath" to arrayOf("account", "id")))
                store.createIndex(MSG_GROUP_TIME, arrayOf("account", "group_id", "created_at"), json("unique" to false))
            }
            if (!database.objectStoreNames.contains(EVENT).unsafeCast<Boolean>()) {
                database.createObjectStore(EVENT, json("keyPath" to arrayOf("account", "id")))
            }
        }
        val opened = awaitRequest<dynamic>(req)
        cachedDb = opened
        return opened
    }

    override suspend fun upsertMessages(
        account: String,
        groupId: String,
        messages: List<CachedMsg>,
    ) {
        if (messages.isEmpty()) return
        val tx = db().transaction(MESSAGE, "readwrite")
        val store = tx.objectStore(MESSAGE)
        messages.forEach { m ->
            store.put(
                json(
                    "account" to account,
                    "id" to m.id,
                    "group_id" to m.groupId,
                    "pubkey" to m.pubkey,
                    "created_at" to m.createdAt.toDouble(),
                    "kind" to m.kind,
                    "content" to m.content,
                    "tags_json" to m.tagsJson,
                ),
            )
        }
        awaitTx(tx)
    }

    override suspend fun loadLatest(
        account: String,
        groupId: String,
        limit: Int,
    ): List<CachedMsg> {
        val range = boundGroup(account, groupId, upperCreatedAt = null, upperExclusive = false)
        // Newest-first via a reverse cursor, capped at limit, then reversed to oldest-first.
        return cursorMessages(range, limit).asReversed()
    }

    override suspend fun loadBefore(
        account: String,
        groupId: String,
        beforeCreatedAt: Long,
        limit: Int,
    ): List<CachedMsg> {
        val range = boundGroup(account, groupId, upperCreatedAt = beforeCreatedAt, upperExclusive = true)
        return cursorMessages(range, limit).asReversed()
    }

    override suspend fun oldestCreatedAt(
        account: String,
        groupId: String,
    ): Long? {
        val range = boundGroup(account, groupId, upperCreatedAt = null, upperExclusive = false)
        val store = db().transaction(MESSAGE, "readonly").objectStore(MESSAGE)
        val cursorReq = store.index(MSG_GROUP_TIME).openCursor(range, "next")
        val first = awaitRequest<dynamic>(cursorReq) ?: return null
        return (first.value.created_at.unsafeCast<Double>()).toLong()
    }

    override suspend fun upsertEvents(
        account: String,
        events: List<CachedEventRow>,
    ) {
        if (events.isEmpty()) return
        val tx = db().transaction(EVENT, "readwrite")
        val store = tx.objectStore(EVENT)
        events.forEach { e ->
            store.put(
                json(
                    "account" to account,
                    "id" to e.id,
                    "pubkey" to e.pubkey,
                    "created_at" to e.createdAt.toDouble(),
                    "kind" to e.kind,
                    "content" to e.content,
                    "tags_json" to e.tagsJson,
                ),
            )
        }
        awaitTx(tx)
    }

    override suspend fun getEvent(
        account: String,
        id: String,
    ): CachedEventRow? {
        val store = db().transaction(EVENT, "readonly").objectStore(EVENT)
        val row = awaitRequest<dynamic>(store.get(arrayOf(account, id))) ?: return null
        return toEventRow(row)
    }

    override suspend fun getEvents(
        account: String,
        ids: List<String>,
    ): List<CachedEventRow> {
        if (ids.isEmpty()) return emptyList()
        val store = db().transaction(EVENT, "readonly").objectStore(EVENT)
        return ids.mapNotNull { id ->
            val row = awaitRequest<dynamic>(store.get(arrayOf(account, id)))
            if (row == null) null else toEventRow(row)
        }
    }

    override suspend fun evictToByteBudget(
        account: String,
        maxBytes: Long,
    ) {
        // Gather (store, key, created_at, bytes) for the account, oldest-first, delete until under.
        val rows = (sizeRows(MESSAGE, account) + sizeRows(EVENT, account)).sortedBy { it.createdAt }
        var total = rows.sumOf { it.bytes }
        if (total <= maxBytes) return
        val tx = db().transaction(arrayOf(MESSAGE, EVENT), "readwrite")
        for (row in rows) {
            if (total <= maxBytes) break
            tx.objectStore(row.store).delete(arrayOf(account, row.id))
            total -= row.bytes
        }
        awaitTx(tx)
    }

    override suspend fun clearAccount(account: String) {
        val tx = db().transaction(arrayOf(MESSAGE, EVENT), "readwrite")
        // Delete every row whose compound key starts with [account].
        val range = boundAccount(account)
        deleteByRange(tx.objectStore(MESSAGE), range)
        deleteByRange(tx.objectStore(EVENT), range)
        awaitTx(tx)
    }

    // ── Cursor + range helpers ──────────────────────────────────────────────

    private suspend fun cursorMessages(
        range: dynamic,
        limit: Int,
    ): List<CachedMsg> {
        val store = db().transaction(MESSAGE, "readonly").objectStore(MESSAGE)
        val out = ArrayList<CachedMsg>(limit)
        val cursorReq = store.index(MSG_GROUP_TIME).openCursor(range, "prev")
        while (out.size < limit) {
            val cursor = awaitRequest<dynamic>(cursorReq) ?: break
            out.add(toCachedMsg(cursor.value))
            cursor.`continue`()
            // Re-await the same request object: IDB reuses it for each cursor step.
            if (out.size >= limit) break
        }
        return out
    }

    private class SizeRow(val store: String, val id: String, val createdAt: Long, val bytes: Long)

    private suspend fun sizeRows(
        storeName: String,
        account: String,
    ): List<SizeRow> {
        val store = db().transaction(storeName, "readonly").objectStore(storeName)
        val out = ArrayList<SizeRow>()
        val cursorReq = store.openCursor(boundAccount(account), "next")
        while (true) {
            val cursor = awaitRequest<dynamic>(cursorReq) ?: break
            val v = cursor.value
            val bytes = (v.content.unsafeCast<String>().length + v.tags_json.unsafeCast<String>().length + 120).toLong()
            out.add(SizeRow(storeName, v.id.unsafeCast<String>(), (v.created_at.unsafeCast<Double>()).toLong(), bytes))
            cursor.`continue`()
        }
        return out
    }

    private suspend fun deleteByRange(
        store: dynamic,
        range: dynamic,
    ) {
        val cursorReq = store.openCursor(range, "next")
        while (true) {
            val cursor = awaitRequest<dynamic>(cursorReq) ?: break
            cursor.delete()
            cursor.`continue`()
        }
    }

    private fun boundGroup(
        account: String,
        groupId: String,
        upperCreatedAt: Long?,
        upperExclusive: Boolean,
    ): dynamic {
        val keyRange = window.asDynamic().IDBKeyRange
        val lower = arrayOf<dynamic>(account, groupId, MIN_TIME)
        val upper = arrayOf<dynamic>(account, groupId, upperCreatedAt?.toDouble() ?: MAX_TIME)
        return keyRange.bound(lower, upper, false, upperExclusive)
    }

    private fun boundAccount(account: String): dynamic {
        val keyRange = window.asDynamic().IDBKeyRange
        // Compound-key prefix range: [account] .. [account, "￿"].
        return keyRange.bound(arrayOf<dynamic>(account), arrayOf<dynamic>(account, "￿"), false, false)
    }

    private fun toCachedMsg(v: dynamic): CachedMsg = CachedMsg(
        id = v.id.unsafeCast<String>(),
        groupId = v.group_id.unsafeCast<String>(),
        pubkey = v.pubkey.unsafeCast<String>(),
        createdAt = (v.created_at.unsafeCast<Double>()).toLong(),
        kind = v.kind.unsafeCast<Int>(),
        content = v.content.unsafeCast<String>(),
        tagsJson = v.tags_json.unsafeCast<String>(),
    )

    private fun toEventRow(v: dynamic): CachedEventRow = CachedEventRow(
        id = v.id.unsafeCast<String>(),
        pubkey = v.pubkey.unsafeCast<String>(),
        createdAt = (v.created_at.unsafeCast<Double>()).toLong(),
        kind = v.kind.unsafeCast<Int>(),
        content = v.content.unsafeCast<String>(),
        tagsJson = v.tags_json.unsafeCast<String>(),
    )

    private suspend fun <T> awaitRequest(req: dynamic): T = suspendCancellableCoroutine { cont ->
        req.onsuccess = { _: dynamic -> cont.resume(req.result.unsafeCast<T>()) }
        req.onerror = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB request failed")) }
    }

    private suspend fun awaitTx(tx: dynamic): Unit = suspendCancellableCoroutine { cont ->
        tx.oncomplete = { _: dynamic -> cont.resume(Unit) }
        tx.onerror = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB transaction failed")) }
        tx.onabort = { _: dynamic -> cont.resumeWithException(RuntimeException("IndexedDB transaction aborted")) }
    }

    private companion object {
        const val DB_NAME = "nostrord_cache_db"
        const val MESSAGE = "message"
        const val EVENT = "event"
        const val MSG_GROUP_TIME = "group_time"

        // created_at bounds for compound-key ranges (nostr seconds sit well inside).
        const val MIN_TIME = 0.0
        const val MAX_TIME = 1.0e15
    }
}
