@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.storage

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("(key) => localStorage.getItem(key)")
private external fun jsGetItem(key: String): String?

@JsFun("(key, value) => localStorage.setItem(key, value)")
private external fun jsSetItem(key: String, value: String)

@JsFun("(key) => localStorage.removeItem(key)")
private external fun jsRemoveItem(key: String)

@JsFun("() => localStorage.clear()")
private external fun jsClear()

@JsFun("(prefix) => { const keys = []; for (let i = 0; i < localStorage.length; i++) { const key = localStorage.key(i); if (key && key.startsWith(prefix)) keys.push(key); } return keys; }")
private external fun jsGetKeysWithPrefix(prefix: String): JsArray<JsString>

// Opens IndexedDB, creates kv store if needed, stores the open DB in globalThis.__nostrordIdb,
// reads all entries into globalThis.__nostrordIdbData (JSON string), and sets globalThis.__nostrordIdbReady.
// Uses globalThis instead of window to work in all JS environments (Workers, bundlers, etc.).
@JsFun("""
() => {
    globalThis.__nostrordIdbReady = false;
    globalThis.__nostrordIdbData = '{}';
    if (typeof indexedDB === 'undefined') {
        console.error('[IDB-WASM] indexedDB not available in this context');
        globalThis.__nostrordIdbData = '{}';
        globalThis.__nostrordIdbReady = true;
        return;
    }
    console.log('[IDB-WASM] opening nostrord_meta_db...');
    var req = indexedDB.open('nostrord_meta_db', 1);
    req.onupgradeneeded = function(e) {
        console.log('[IDB-WASM] onupgradeneeded — creating kv store');
        var db = e.target.result;
        if (!db.objectStoreNames.contains('kv')) db.createObjectStore('kv');
    };
    req.onsuccess = function(e) {
        console.log('[IDB-WASM] DB opened — reading all entries');
        globalThis.__nostrordIdb = e.target.result;
        globalThis.__nostrordIdb.onclose = function() {
            console.error('[IDB-WASM] DB connection closed unexpectedly');
            globalThis.__nostrordIdb = null;
        };
        globalThis.__nostrordIdb.onversionchange = function() {
            console.error('[IDB-WASM] DB versionchange — closing connection');
            globalThis.__nostrordIdb.close();
            globalThis.__nostrordIdb = null;
        };
        var db = globalThis.__nostrordIdb;
        var tx = db.transaction('kv', 'readonly');
        var store = tx.objectStore('kv');
        var keysReq = store.getAllKeys();
        var valsReq = store.getAll();
        var keys = null, vals = null;
        function tryDone() {
            if (keys !== null && vals !== null) {
                var result = {};
                for (var i = 0; i < keys.length; i++) {
                    if (vals[i] !== null && vals[i] !== undefined) result[keys[i]] = vals[i];
                }
                var jsonStr = JSON.stringify(result);
                console.log('[IDB-WASM] loaded ' + keys.length + ' entries from IDB');
                globalThis.__nostrordIdbData = jsonStr;
                globalThis.__nostrordIdbReady = true;
            }
        }
        keysReq.onsuccess = function(e2) { keys = e2.target.result; tryDone(); };
        valsReq.onsuccess = function(e2) { vals = e2.target.result; tryDone(); };
        keysReq.onerror = function(e2) {
            console.error('[IDB-WASM] getAllKeys error: ' + e2.target.error);
            globalThis.__nostrordIdbData = '{}';
            globalThis.__nostrordIdbReady = true;
        };
        valsReq.onerror = function(e2) {
            console.error('[IDB-WASM] getAll error: ' + e2.target.error);
            globalThis.__nostrordIdbData = '{}';
            globalThis.__nostrordIdbReady = true;
        };
    };
    req.onerror = function(e) {
        console.error('[IDB-WASM] DB open error: ' + e.target.error);
        globalThis.__nostrordIdbData = '{}';
        globalThis.__nostrordIdbReady = true;
    };
}
""")
private external fun jsStartIdbPreload()

// Returns true once jsStartIdbPreload() has finished and globalThis.__nostrordIdb is set.
@JsFun("() => globalThis.__nostrordIdbReady === true")
private external fun jsIsIdbReady(): Boolean

// Returns the JSON string produced by jsStartIdbPreload().
@JsFun("() => globalThis.__nostrordIdbData || '{}'")
private external fun jsGetIdbData(): JsString

// Write a value to the kv store using the already-open DB connection.
@JsFun("""
(key, value) => {
    var db = globalThis.__nostrordIdb;
    if (!db) {
        console.error('[IDB-WASM] idbWrite skipped — DB not ready. key=' + key);
        return;
    }
    try {
        var tx = db.transaction('kv', 'readwrite');
        var req = tx.objectStore('kv').put(value, key);
        req.onsuccess = function() { console.log('[IDB-WASM] put ok: ' + key); };
        req.onerror = function(e) { console.error('[IDB-WASM] put error: ' + key + ' — ' + e.target.error); };
        tx.oncomplete = function() { console.log('[IDB-WASM] tx committed: ' + key); };
        tx.onerror = function(e) { console.error('[IDB-WASM] tx error: ' + key + ' — ' + e.target.error); };
        tx.onabort = function(e) { console.error('[IDB-WASM] tx aborted: ' + key + ' — ' + e.target.error); };
    } catch(e) {
        console.error('[IDB-WASM] idbWrite threw: ' + key + ' — ' + e);
    }
}
""")
private external fun jsIdbWrite(key: String, value: String)

// Delete a single key from the kv store.
@JsFun("""
(key) => {
    var db = globalThis.__nostrordIdb;
    if (!db) {
        console.error('[IDB-WASM] idbDelete skipped — DB not ready. key=' + key);
        return;
    }
    try {
        var tx = db.transaction('kv', 'readwrite');
        var req = tx.objectStore('kv').delete(key);
        req.onerror = function(e) { console.error('[IDB-WASM] delete error: ' + key + ' — ' + e.target.error); };
        tx.onerror = function(e) { console.error('[IDB-WASM] delete tx error: ' + key + ' — ' + e.target.error); };
        tx.onabort = function(e) { console.error('[IDB-WASM] delete tx aborted: ' + key + ' — ' + e.target.error); };
    } catch(e) {
        console.error('[IDB-WASM] idbDelete threw: ' + key + ' — ' + e);
    }
}
""")
private external fun jsIdbDelete(key: String)

// Delete all keys that start with prefix.
@JsFun("""
(prefix) => {
    var db = globalThis.__nostrordIdb;
    if (!db) {
        console.error('[IDB-WASM] idbDeleteWithPrefix skipped — DB not ready. prefix=' + prefix);
        return;
    }
    try {
        var tx = db.transaction('kv', 'readwrite');
        var store = tx.objectStore('kv');
        var req = store.getAllKeys();
        req.onsuccess = function(e) {
            var keys = e.target.result;
            var toDelete = keys.filter(function(k) { return k.startsWith(prefix); });
            if (toDelete.length === 0) return;
            console.log('[IDB-WASM] deleteWithPrefix: ' + toDelete.length + ' keys for prefix=' + prefix);
            var tx2 = db.transaction('kv', 'readwrite');
            var store2 = tx2.objectStore('kv');
            for (var i = 0; i < toDelete.length; i++) store2.delete(toDelete[i]);
            tx2.onerror = function(e2) { console.error('[IDB-WASM] deleteWithPrefix tx2 error: ' + e2.target.error); };
            tx2.onabort = function(e2) { console.error('[IDB-WASM] deleteWithPrefix tx2 aborted: ' + e2.target.error); };
        };
        req.onerror = function(e) { console.error('[IDB-WASM] deleteWithPrefix getAllKeys error: ' + e.target.error); };
    } catch(e) {
        console.error('[IDB-WASM] idbDeleteWithPrefix threw: ' + prefix + ' — ' + e);
    }
}
""")
private external fun jsIdbDeleteWithPrefix(prefix: String)

// In-memory caches for IndexedDB-backed data (synchronous read access after preloadMetadata).
private var relayMetaIdbCache: String? = null
private val joinedGroupMetaIdbCache = mutableMapOf<String, String>()

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
        jsSetItem(PRIVATE_KEY_PREF, privateKeyHex)
    }

    actual fun getPrivateKey(): String? {
        return jsGetItem(PRIVATE_KEY_PREF)
    }

    actual fun hasPrivateKey(): Boolean {
        return jsGetItem(PRIVATE_KEY_PREF) != null
    }

    actual fun clearPrivateKey() {
        jsRemoveItem(PRIVATE_KEY_PREF)
    }

    actual fun saveCurrentRelayUrl(relayUrl: String) {
        jsSetItem(CURRENT_RELAY_URL, relayUrl)
    }

    actual fun getCurrentRelayUrl(): String? {
        return jsGetItem(CURRENT_RELAY_URL)
    }

    actual fun clearCurrentRelayUrl() {
        jsRemoveItem(CURRENT_RELAY_URL)
    }

    actual fun saveRelayList(relays: List<String>) {
        jsSetItem(RELAY_LIST, relays.joinToString(","))
    }

    actual fun loadRelayList(): List<String> {
        val raw = jsGetItem(RELAY_LIST) ?: return emptyList()
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    actual fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = groupIds.joinToString(",")
        jsSetItem(key, json)
    }

    actual fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = jsGetItem(key) ?: return emptySet()
        return if (json.isBlank()) emptySet() else json.split(",").toSet()
    }

    actual fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        jsRemoveItem(key)
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
        val keys = jsGetKeysWithPrefix(accountPrefix)
        for (i in 0 until keys.length) {
            jsRemoveItem(keys[i].toString())
        }
    }

    actual fun saveBunkerUrl(bunkerUrl: String) {
        jsSetItem(BUNKER_URL_PREF, bunkerUrl)
    }

    actual fun getBunkerUrl(): String? {
        return jsGetItem(BUNKER_URL_PREF)
    }

    actual fun hasBunkerUrl(): Boolean {
        return jsGetItem(BUNKER_URL_PREF) != null
    }

    actual fun clearBunkerUrl() {
        jsRemoveItem(BUNKER_URL_PREF)
    }

    actual fun saveBunkerUserPubkey(pubkey: String) {
        jsSetItem(BUNKER_USER_PUBKEY_PREF, pubkey)
    }

    actual fun getBunkerUserPubkey(): String? {
        return jsGetItem(BUNKER_USER_PUBKEY_PREF)
    }

    actual fun clearBunkerUserPubkey() {
        jsRemoveItem(BUNKER_USER_PUBKEY_PREF)
    }

    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        jsSetItem(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey)
    }

    actual fun getBunkerClientPrivateKey(): String? {
        return jsGetItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }

    actual fun clearBunkerClientPrivateKey() {
        jsRemoveItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }

    actual fun saveNip07UserPubkey(pubkey: String) {
        jsSetItem(NIP07_USER_PUBKEY_PREF, pubkey)
    }

    actual fun getNip07UserPubkey(): String? {
        return jsGetItem(NIP07_USER_PUBKEY_PREF)
    }

    actual fun clearNip07UserPubkey() {
        jsRemoveItem(NIP07_USER_PUBKEY_PREF)
    }

    actual fun clearAll() {
        jsClear()
        relayMetaIdbCache = null
        joinedGroupMetaIdbCache.clear()
    }

    actual fun saveLastReadTimestamp(pubkey: String, groupId: String, timestamp: Long) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        jsSetItem(key, timestamp.toString())
    }

    actual fun getLastReadTimestamp(pubkey: String, groupId: String): Long? {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return jsGetItem(key)?.toLongOrNull()
    }

    actual fun clearLastReadTimestamp(pubkey: String, groupId: String) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        jsRemoveItem(key)
    }

    actual fun getAllLastReadTimestamps(pubkey: String): Map<String, Long> {
        val prefix = LAST_READ_PREFIX + pubkey.hashCode() + "_"
        val result = mutableMapOf<String, Long>()
        val keys = jsGetKeysWithPrefix(prefix)
        for (i in 0 until keys.length) {
            val key = keys[i].toString()
            val groupHash = key.removePrefix(prefix)
            jsGetItem(key)?.toLongOrNull()?.let { timestamp ->
                result[groupHash] = timestamp
            }
        }
        return result
    }

    actual fun saveLastViewedGroup(pubkey: String, groupId: String, groupName: String?) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = "$groupId|${groupName ?: ""}"
        jsSetItem(key, value)
    }

    actual fun getLastViewedGroup(pubkey: String): Pair<String, String?>? {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = jsGetItem(key) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val groupId = parts[0]
        val groupName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return Pair(groupId, groupName)
    }

    actual fun clearLastViewedGroup(pubkey: String) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        jsRemoveItem(key)
    }

    actual fun saveMessagesForGroup(pubkey: String, groupId: String, messagesJson: String) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        jsSetItem(key, messagesJson)
    }

    actual fun getMessagesForGroup(pubkey: String, groupId: String): String? {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return jsGetItem(key)
    }

    actual fun clearMessagesForGroup(pubkey: String, groupId: String) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        jsRemoveItem(key)
    }

    actual fun clearAllMessagesForAccount(pubkey: String) {
        val accountPrefix = MESSAGES_PREFIX + pubkey.hashCode() + "_"
        val keys = jsGetKeysWithPrefix(accountPrefix)
        for (i in 0 until keys.length) {
            jsRemoveItem(keys[i].toString())
        }
    }

    actual fun savePendingEvents(pubkey: String, eventsJson: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        jsSetItem(key, eventsJson)
    }

    actual fun getPendingEvents(pubkey: String): String? {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        return jsGetItem(key)
    }

    actual fun clearPendingEvents(pubkey: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        jsRemoveItem(key)
    }

    actual fun saveGroupsForRelay(relayUrl: String, groupsJson: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        jsSetItem(key, groupsJson)
    }

    actual fun getGroupsForRelay(relayUrl: String): String? {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        return jsGetItem(key)
    }

    actual fun clearGroupsForRelay(relayUrl: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        jsRemoveItem(key)
    }

    // Joined-group metadata — backed by IndexedDB, read via in-memory cache.
    actual fun saveJoinedGroupMetadata(pubkey: String, relayUrl: String, groupsJson: String) {
        val cacheKey = "${pubkey.hashCode()}_${relayUrl.hashCode()}"
        joinedGroupMetaIdbCache[cacheKey] = groupsJson
        jsIdbWrite(IDB_JOINED_GROUP_META_PREFIX + cacheKey, groupsJson)
    }

    actual fun getJoinedGroupMetadata(pubkey: String, relayUrl: String): String? {
        val cacheKey = "${pubkey.hashCode()}_${relayUrl.hashCode()}"
        return joinedGroupMetaIdbCache[cacheKey]
    }

    actual fun clearJoinedGroupMetadata(pubkey: String, relayUrl: String) {
        val cacheKey = "${pubkey.hashCode()}_${relayUrl.hashCode()}"
        joinedGroupMetaIdbCache.remove(cacheKey)
        jsIdbDelete(IDB_JOINED_GROUP_META_PREFIX + cacheKey)
    }

    actual fun clearAllJoinedGroupMetadataForAccount(pubkey: String) {
        val accountPrefix = "${pubkey.hashCode()}_"
        joinedGroupMetaIdbCache.keys.filter { it.startsWith(accountPrefix) }
            .forEach { joinedGroupMetaIdbCache.remove(it) }
        jsIdbDeleteWithPrefix(IDB_JOINED_GROUP_META_PREFIX + accountPrefix)
    }

    // NIP-11 relay metadata — backed by IndexedDB, read via in-memory cache.
    actual fun saveRelayMetadata(json: String) {
        relayMetaIdbCache = json
        jsIdbWrite(IDB_RELAY_META_KEY, json)
    }

    actual fun getRelayMetadata(): String? = relayMetaIdbCache

    actual fun saveLiveCursors(relayUrl: String, json: String) {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        jsSetItem(key, json)
    }

    actual fun getLiveCursors(relayUrl: String): String? {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        return jsGetItem(key)
    }

    actual fun clearLiveCursors(relayUrl: String) {
        val key = LIVE_CURSORS_PREFIX + relayUrl.hashCode()
        jsRemoveItem(key)
    }

    actual fun saveBooleanPref(key: String, value: Boolean) {
        jsSetItem(key, if (value) "1" else "0")
    }

    actual fun getBooleanPref(key: String, default: Boolean): Boolean {
        val raw = jsGetItem(key) ?: return default
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    actual fun saveStringPref(key: String, value: String) {
        jsSetItem(key, value)
    }

    actual fun getStringPref(key: String, default: String): String {
        return jsGetItem(key) ?: default
    }

    // Opens IndexedDB (storing the connection in globalThis.__nostrordIdb) and reads all kv entries
    // into globalThis.__nostrordIdbData. Polls until ready, then populates in-memory caches.
    // Avoids passing Kotlin lambdas to JS — uses a global ready-flag instead.
    actual suspend fun preloadMetadata() {
        println("[IDB-WASM] preloadMetadata: starting")
        try {
            jsStartIdbPreload()
            // Yield to the JS event loop until the IDB open + read completes (max 3s).
            var attempts = 0
            while (!jsIsIdbReady() && attempts < 600) {
                delay(5)
                attempts++
            }
            if (!jsIsIdbReady()) {
                println("[IDB-WASM] preloadMetadata: timed out after ${attempts * 5}ms — continuing without cache")
                return
            }
            println("[IDB-WASM] preloadMetadata: IDB ready after ${attempts * 5}ms")

            val json = jsGetIdbData().toString()
            if (json == "{}") {
                println("[IDB-WASM] preloadMetadata: IDB empty — nothing to restore")
                return
            }

            val parsed = Json.parseToJsonElement(json).jsonObject
            parsed[IDB_RELAY_META_KEY]?.jsonPrimitive?.contentOrNull?.let {
                relayMetaIdbCache = it
                println("[IDB-WASM] preloadMetadata: restored relay metadata (${it.length} bytes)")
            }
            var groupCount = 0
            parsed.forEach { (key, value) ->
                if (key.startsWith(IDB_JOINED_GROUP_META_PREFIX)) {
                    value.jsonPrimitive.contentOrNull?.let { v ->
                        joinedGroupMetaIdbCache[key.removePrefix(IDB_JOINED_GROUP_META_PREFIX)] = v
                        groupCount++
                    }
                }
            }
            println("[IDB-WASM] preloadMetadata: restored $groupCount joined-group-meta entries")
        } catch (e: Throwable) {
            println("[IDB-WASM] preloadMetadata: uncaught exception — ${e.message}")
        }
    }
}
