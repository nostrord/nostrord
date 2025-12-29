@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.storage

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

actual object SecureStorage {
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    private const val LAST_READ_PREFIX = "last_read_"
    
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
    
    // NIP-46 Bunker URL support
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
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        jsSetItem(BUNKER_USER_PUBKEY_PREF, pubkey)
    }
    
    actual fun getBunkerUserPubkey(): String? {
        return jsGetItem(BUNKER_USER_PUBKEY_PREF)
    }
    
    actual fun clearBunkerUserPubkey() {
        jsRemoveItem(BUNKER_USER_PUBKEY_PREF)
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        jsSetItem(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey)
    }
    
    actual fun getBunkerClientPrivateKey(): String? {
        return jsGetItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }
    
    actual fun clearBunkerClientPrivateKey() {
        jsRemoveItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }
    
    actual fun clearAll() {
        jsClear()
    }

    // Last read timestamp tracking
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
}
