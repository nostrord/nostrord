package org.nostr.nostrord.storage

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// In-memory caches for IndexedDB-backed data (synchronous read access after preloadMetadata).
private var relayMetaIdbCache: String? = null
private val joinedGroupMetaIdbCache = mutableMapOf<String, String>()

// Single persistent DB connection — set once by preloadMetadata(), reused for all writes.
private var _idbDb: dynamic = null

private fun idbWrite(key: String, value: String) {
    val db = _idbDb ?: return
    try {
        db.transaction("kv", "readwrite").objectStore("kv").put(value, key)
    } catch (_: Throwable) {}
}

private fun idbDeleteWithPrefix(prefix: String) {
    val db = _idbDb ?: return
    try {
        val store: dynamic = db.transaction("kv", "readwrite").objectStore("kv")
        val keysReq: dynamic = store.getAllKeys()
        keysReq.onsuccess = { ke: dynamic ->
            val keys = ke.target.result.unsafeCast<Array<String>>()
            val toDelete = keys.filter { it.startsWith(prefix) }
            if (toDelete.isNotEmpty()) {
                val store2: dynamic = db.transaction("kv", "readwrite").objectStore("kv")
                for (k in toDelete) store2.delete(k)
            }
        }
    } catch (_: Throwable) {}
}

actual object SecureStorage {
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val RELAY_LIST = "relay_list"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    private const val NIP07_USER_PUBKEY_PREF = "nostr_nip07_user_pubkey"
    private const val LAST_READ_PREFIX = "last_read_"
    private const val LAST_VIEWED_GROUP_PREFIX = "last_viewed_group_"
    private const val MESSAGES_PREFIX = "messages_"
    private const val PENDING_EVENTS_PREFIX = "pending_events_"
    private const val RELAY_GROUPS_PREFIX = "relay_groups_"
    private const val LIVE_CURSORS_PREFIX = "live_cursors_"

    // IDB key constants
    private const val IDB_RELAY_META_KEY = "relay_meta:relay_metadata"
    private const val IDB_JOINED_GROUP_META_PREFIX = "joined_group_meta:"

    actual fun savePrivateKey(privateKeyHex: String) {
        localStorage.setItem(PRIVATE_KEY_PREF, privateKeyHex)
    }

    actual fun getPrivateKey(): String? {
        return localStorage.getItem(PRIVATE_KEY_PREF)
    }

    actual fun hasPrivateKey(): Boolean {
        return localStorage.getItem(PRIVATE_KEY_PREF) != null
    }

    actual fun clearPrivateKey() {
        localStorage.removeItem(PRIVATE_KEY_PREF)
    }

    actual fun saveCurrentRelayUrl(relayUrl: String) {
        localStorage.setItem(CURRENT_RELAY_URL, relayUrl)
    }

    actual fun getCurrentRelayUrl(): String? {
        return localStorage.getItem(CURRENT_RELAY_URL)
    }

    actual fun clearCurrentRelayUrl() {
        localStorage.removeItem(CURRENT_RELAY_URL)
    }

    actual fun saveRelayList(relays: List<String>) {
        localStorage.setItem(RELAY_LIST, relays.joinToString(","))
    }

    actual fun loadRelayList(): List<String> {
        val raw = localStorage.getItem(RELAY_LIST) ?: return emptyList()
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    actual fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = groupIds.joinToString(",")
        localStorage.setItem(key, json)
    }

    actual fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = localStorage.getItem(key) ?: return emptySet()
        return if (json.isBlank()) emptySet() else json.split(",").toSet()
    }

    actual fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        localStorage.removeItem(key)
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
        val keysToRemove = mutableListOf<String>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i)
            if (key != null && key.startsWith(accountPrefix)) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { localStorage.removeItem(it) }
    }

    actual fun saveBunkerUrl(bunkerUrl: String) {
        localStorage.setItem(BUNKER_URL_PREF, bunkerUrl)
    }

    actual fun getBunkerUrl(): String? {
        return localStorage.getItem(BUNKER_URL_PREF)
    }

    actual fun hasBunkerUrl(): Boolean {
        return localStorage.getItem(BUNKER_URL_PREF) != null
    }

    actual fun clearBunkerUrl() {
        localStorage.removeItem(BUNKER_URL_PREF)
    }

    actual fun saveBunkerUserPubkey(pubkey: String) {
        localStorage.setItem(BUNKER_USER_PUBKEY_PREF, pubkey)
    }

    actual fun getBunkerUserPubkey(): String? {
        return localStorage.getItem(BUNKER_USER_PUBKEY_PREF)
    }

    actual fun clearBunkerUserPubkey() {
        localStorage.removeItem(BUNKER_USER_PUBKEY_PREF)
    }

    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        localStorage.setItem(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey)
    }

    actual fun getBunkerClientPrivateKey(): String? {
        return localStorage.getItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }

    actual fun clearBunkerClientPrivateKey() {
        localStorage.removeItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }

    actual fun saveNip07UserPubkey(pubkey: String) {
        localStorage.setItem(NIP07_USER_PUBKEY_PREF, pubkey)
    }

    actual fun getNip07UserPubkey(): String? {
        return localStorage.getItem(NIP07_USER_PUBKEY_PREF)
    }

    actual fun clearNip07UserPubkey() {
        localStorage.removeItem(NIP07_USER_PUBKEY_PREF)
    }

    actual fun clearAll() {
        localStorage.clear()
        relayMetaIdbCache = null
        joinedGroupMetaIdbCache.clear()
    }

    actual fun saveLastReadTimestamp(pubkey: String, groupId: String, timestamp: Long) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        localStorage.setItem(key, timestamp.toString())
    }

    actual fun getLastReadTimestamp(pubkey: String, groupId: String): Long? {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return localStorage.getItem(key)?.toLongOrNull()
    }

    actual fun clearLastReadTimestamp(pubkey: String, groupId: String) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        localStorage.removeItem(key)
    }

    actual fun getAllLastReadTimestamps(pubkey: String): Map<String, Long> {
        val prefix = LAST_READ_PREFIX + pubkey.hashCode() + "_"
        val result = mutableMapOf<String, Long>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i)
            if (key != null && key.startsWith(prefix)) {
                val groupHash = key.removePrefix(prefix)
                localStorage.getItem(key)?.toLongOrNull()?.let { timestamp ->
                    result[groupHash] = timestamp
                }
            }
        }
        return result
    }

    actual fun saveLastViewedGroup(pubkey: String, groupId: String, groupName: String?) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = "$groupId|${groupName ?: ""}"
        localStorage.setItem(key, value)
    }

    actual fun getLastViewedGroup(pubkey: String): Pair<String, String?>? {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = localStorage.getItem(key) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val groupId = parts[0]
        val groupName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return Pair(groupId, groupName)
    }

    actual fun clearLastViewedGroup(pubkey: String) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        localStorage.removeItem(key)
    }

    actual fun saveMessagesForGroup(pubkey: String, groupId: String, messagesJson: String) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        localStorage.setItem(key, messagesJson)
    }

    actual fun getMessagesForGroup(pubkey: String, groupId: String): String? {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return localStorage.getItem(key)
    }

    actual fun clearMessagesForGroup(pubkey: String, groupId: String) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        localStorage.removeItem(key)
    }

    actual fun clearAllMessagesForAccount(pubkey: String) {
        val accountPrefix = MESSAGES_PREFIX + pubkey.hashCode() + "_"
        val keysToRemove = mutableListOf<String>()
        for (i in 0 until localStorage.length) {
            val key = localStorage.key(i)
            if (key != null && key.startsWith(accountPrefix)) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { localStorage.removeItem(it) }
    }

    actual fun savePendingEvents(pubkey: String, eventsJson: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        localStorage.setItem(key, eventsJson)
    }

    actual fun getPendingEvents(pubkey: String): String? {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        return localStorage.getItem(key)
    }

    actual fun clearPendingEvents(pubkey: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        localStorage.removeItem(key)
    }

    actual fun saveGroupsForRelay(relayUrl: String, groupsJson: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        localStorage.setItem(key, groupsJson)
    }

    actual fun getGroupsForRelay(relayUrl: String): String? {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        return localStorage.getItem(key)
    }

    actual fun clearGroupsForRelay(relayUrl: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        localStorage.removeItem(key)
    }

    // Joined-group metadata — backed by IndexedDB, read via in-memory cache.
    actual fun saveJoinedGroupMetadata(pubkey: String, relayUrl: String, groupsJson: String) {
        val cacheKey = "${pubkey.hashCode()}_${relayUrl.hashCode()}"
        joinedGroupMetaIdbCache[cacheKey] = groupsJson
        idbWrite(IDB_JOINED_GROUP_META_PREFIX + cacheKey, groupsJson)
    }

    actual fun getJoinedGroupMetadata(pubkey: String, relayUrl: String): String? {
        val cacheKey = "${pubkey.hashCode()}_${relayUrl.hashCode()}"
        return joinedGroupMetaIdbCache[cacheKey]
    }

    actual fun clearAllJoinedGroupMetadataForAccount(pubkey: String) {
        val accountPrefix = "${pubkey.hashCode()}_"
        joinedGroupMetaIdbCache.keys.filter { it.startsWith(accountPrefix) }
            .forEach { joinedGroupMetaIdbCache.remove(it) }
        idbDeleteWithPrefix(IDB_JOINED_GROUP_META_PREFIX + accountPrefix)
    }

    // NIP-11 relay metadata — backed by IndexedDB, read via in-memory cache.
    actual fun saveRelayMetadata(json: String) {
        relayMetaIdbCache = json
        idbWrite(IDB_RELAY_META_KEY, json)
    }

    actual fun getRelayMetadata(): String? = relayMetaIdbCache

    actual fun saveLiveCursors(relayUrl: String, json: String) {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        localStorage.setItem(key, json)
    }

    actual fun getLiveCursors(relayUrl: String): String? {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        return localStorage.getItem(key)
    }

    actual fun clearLiveCursors(relayUrl: String) {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        localStorage.removeItem(key)
    }

    actual fun saveBooleanPref(key: String, value: Boolean) {
        localStorage.setItem(key, if (value) "1" else "0")
    }

    actual fun getBooleanPref(key: String, default: Boolean): Boolean {
        val raw = localStorage.getItem(key) ?: return default
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    actual fun saveStringPref(key: String, value: String) {
        localStorage.setItem(key, value)
    }

    actual fun getStringPref(key: String, default: String): String {
        return localStorage.getItem(key) ?: default
    }

    // Open IndexedDB, read all kv entries, and populate in-memory caches.
    // Stores the DB connection for subsequent writes — must be called before any reads or writes.
    actual suspend fun preloadMetadata() {
        try {
            val loaded = mutableMapOf<String, String>()
            suspendCoroutine<Unit> { cont ->
                var resumed = false
                fun safeResume() {
                    if (!resumed) { resumed = true; cont.resume(Unit) }
                }

                val idb: dynamic = window.asDynamic().indexedDB
                if (idb == null || idb == undefined) {
                    safeResume()
                    return@suspendCoroutine
                }
                val req: dynamic = idb.open("nostrord_meta_db", 1)
                req.onupgradeneeded = { e: dynamic ->
                    val db: dynamic = e.target.result
                    if (!(db.objectStoreNames.contains("kv").unsafeCast<Boolean>())) {
                        db.createObjectStore("kv")
                    }
                }
                req.onerror = { _: dynamic -> safeResume() }
                req.onsuccess = { e: dynamic ->
                    _idbDb = e.target.result
                    val db: dynamic = _idbDb
                    // Drop the handle if the browser closes it (e.g. version change from another tab).
                    db.onclose = { _: dynamic -> _idbDb = null }
                    db.onversionchange = { _: dynamic ->
                        db.close()
                        _idbDb = null
                    }

                    val tx: dynamic = db.transaction("kv", "readonly")
                    val store: dynamic = tx.objectStore("kv")
                    val keysReq: dynamic = store.getAllKeys()
                    val valsReq: dynamic = store.getAll()
                    var dbKeys: Array<String>? = null
                    var dbVals: Array<dynamic>? = null

                    fun tryDone() {
                        val k = dbKeys ?: return
                        val v = dbVals ?: return
                        for (i in k.indices) {
                            val value = v[i]
                            if (value != null) loaded[k[i]] = value.toString()
                        }
                        safeResume()
                    }

                    keysReq.onsuccess = { ke: dynamic ->
                        dbKeys = ke.target.result.unsafeCast<Array<String>>()
                        tryDone()
                    }
                    valsReq.onsuccess = { ve: dynamic ->
                        dbVals = ve.target.result.unsafeCast<Array<dynamic>>()
                        tryDone()
                    }
                    keysReq.onerror = { _: dynamic -> safeResume() }
                    valsReq.onerror = { _: dynamic -> safeResume() }
                    tx.onerror = { _: dynamic -> safeResume() }
                }
            }

            loaded[IDB_RELAY_META_KEY]?.let { relayMetaIdbCache = it }
            loaded.forEach { (key, value) ->
                if (key.startsWith(IDB_JOINED_GROUP_META_PREFIX)) {
                    joinedGroupMetaIdbCache[key.removePrefix(IDB_JOINED_GROUP_META_PREFIX)] = value
                }
            }
        } catch (_: Throwable) {}
    }
}
