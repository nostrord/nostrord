@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package org.nostr.nostrord.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.dataUsingEncoding
import platform.Foundation.getBytes
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

// Non-sensitive data lives in NSUserDefaults; sensitive slots (private key, bunker
// credentials, ad-hoc secrets) go to the Keychain, which the OS encrypts at rest.
actual object SecureStorage {
    private val defaults = NSUserDefaults.standardUserDefaults
    private const val KEYCHAIN_SERVICE = "org.nostr.nostrord"

    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val RELAY_LIST = "relay_list"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    private const val LAST_READ_PREFIX = "last_read_"
    private const val LAST_VIEWED_GROUP_PREFIX = "last_viewed_group_"
    private const val LAST_ROUTE_PREFIX = "last_route_"
    private const val MESSAGES_PREFIX = "messages_"
    private const val PENDING_EVENTS_PREFIX = "pending_events_"
    private const val RELAY_GROUPS_PREFIX = "relay_groups_"
    private const val JOINED_GROUP_META_PREFIX = "joined_group_meta_"
    private const val LIVE_CURSORS_PREFIX = "live_cursors_"
    private const val RELAY_METADATA = "relay_metadata"
    private const val USER_METADATA_CACHE = "user_metadata_cache"
    private const val USER_GROUP_LISTS_CACHE = "user_group_lists_cache"

    // --- Keychain (sensitive) -------------------------------------------------

    private fun keychainSet(account: String, value: String): Boolean {
        keychainDelete(account)
        val nsData = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        val cfService = CFBridgingRetain(KEYCHAIN_SERVICE as NSString)
        val cfAccount = CFBridgingRetain(account as NSString)
        val cfData = CFBridgingRetain(nsData)
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 4, null, null)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfService)
        CFDictionaryAddValue(dict, kSecAttrAccount, cfAccount)
        CFDictionaryAddValue(dict, kSecValueData, cfData)
        val status = SecItemAdd(dict, null)
        CFRelease(dict)
        CFBridgingRelease(cfService)
        CFBridgingRelease(cfAccount)
        CFBridgingRelease(cfData)
        return status == errSecSuccess
    }

    private fun keychainGet(account: String): String? {
        val cfService = CFBridgingRetain(KEYCHAIN_SERVICE as NSString)
        val cfAccount = CFBridgingRetain(account as NSString)
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 5, null, null)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfService)
        CFDictionaryAddValue(dict, kSecAttrAccount, cfAccount)
        CFDictionaryAddValue(dict, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(dict, kSecMatchLimit, kSecMatchLimitOne)
        val raw =
            memScoped {
                val out = alloc<CFTypeRefVar>()
                val status = SecItemCopyMatching(dict, out.ptr)
                if (status == errSecSuccess) out.value else null
            }
        CFRelease(dict)
        CFBridgingRelease(cfService)
        CFBridgingRelease(cfAccount)
        val nsData = CFBridgingRelease(raw) as? NSData ?: return null
        val length = nsData.length.toInt()
        if (length == 0) return ""
        val bytes = ByteArray(length)
        bytes.usePinned { nsData.getBytes(it.addressOf(0), nsData.length) }
        return bytes.decodeToString()
    }

    private fun keychainDelete(account: String) {
        val cfService = CFBridgingRetain(KEYCHAIN_SERVICE as NSString)
        val cfAccount = CFBridgingRetain(account as NSString)
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 3, null, null)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfService)
        CFDictionaryAddValue(dict, kSecAttrAccount, cfAccount)
        SecItemDelete(dict)
        CFRelease(dict)
        CFBridgingRelease(cfService)
        CFBridgingRelease(cfAccount)
    }

    private fun keychainDeleteAll() {
        val cfService = CFBridgingRetain(KEYCHAIN_SERVICE as NSString)
        val dict = CFDictionaryCreateMutable(kCFAllocatorDefault, 2, null, null)
        CFDictionaryAddValue(dict, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dict, kSecAttrService, cfService)
        SecItemDelete(dict)
        CFRelease(dict)
        CFBridgingRelease(cfService)
    }

    // --- NSUserDefaults (non-sensitive) --------------------------------------

    private fun saveString(key: String, value: String) {
        defaults.setObject(value, key)
    }

    private fun getString(key: String): String? = defaults.stringForKey(key)

    private fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    // --- Private key (Keychain) ----------------------------------------------

    actual fun savePrivateKey(privateKeyHex: String) {
        keychainSet(PRIVATE_KEY_PREF, privateKeyHex)
    }

    actual fun getPrivateKey(): String? = keychainGet(PRIVATE_KEY_PREF)

    actual fun hasPrivateKey(): Boolean = keychainGet(PRIVATE_KEY_PREF) != null

    actual fun clearPrivateKey() {
        keychainDelete(PRIVATE_KEY_PREF)
    }

    // --- Relay URL / list -----------------------------------------------------

    actual fun saveCurrentRelayUrl(relayUrl: String) {
        saveString(CURRENT_RELAY_URL, relayUrl)
    }

    actual fun getCurrentRelayUrl(): String? = getString(CURRENT_RELAY_URL)

    actual fun clearCurrentRelayUrl() {
        remove(CURRENT_RELAY_URL)
    }

    actual fun saveRelayList(relays: List<String>) {
        saveString(RELAY_LIST, relays.joinToString(","))
    }

    actual fun loadRelayList(): List<String> {
        val raw = getString(RELAY_LIST) ?: return emptyList()
        return if (raw.isBlank()) emptyList() else raw.split(",").filter { it.isNotBlank() }
    }

    // --- Joined groups per relay ---------------------------------------------

    actual fun saveJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
        groupIds: Set<String>,
    ) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        saveString(key, Json.encodeToString(groupIds.toList()))
    }

    actual fun getJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
    ): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = getString(key) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(json).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    actual fun clearJoinedGroupsForRelay(
        pubkey: String,
        relayUrl: String,
    ) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        remove(key)
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        removeKeysWithPrefix(JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_")
    }

    // --- Bunker credentials (Keychain) ---------------------------------------

    actual fun saveBunkerUrl(bunkerUrl: String) {
        keychainSet(BUNKER_URL_PREF, bunkerUrl)
    }

    actual fun getBunkerUrl(): String? = keychainGet(BUNKER_URL_PREF)

    actual fun hasBunkerUrl(): Boolean = keychainGet(BUNKER_URL_PREF) != null

    actual fun clearBunkerUrl() {
        keychainDelete(BUNKER_URL_PREF)
    }

    actual fun saveBunkerUserPubkey(pubkey: String) {
        keychainSet(BUNKER_USER_PUBKEY_PREF, pubkey)
    }

    actual fun getBunkerUserPubkey(): String? = keychainGet(BUNKER_USER_PUBKEY_PREF)

    actual fun clearBunkerUserPubkey() {
        keychainDelete(BUNKER_USER_PUBKEY_PREF)
    }

    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        keychainSet(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey)
    }

    actual fun getBunkerClientPrivateKey(): String? = keychainGet(BUNKER_CLIENT_PRIVATE_KEY_PREF)

    actual fun clearBunkerClientPrivateKey() {
        keychainDelete(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }

    // NIP-07 is browser-only; nothing to persist on iOS.
    actual fun saveNip07UserPubkey(pubkey: String) {}

    actual fun getNip07UserPubkey(): String? = null

    actual fun clearNip07UserPubkey() {}

    actual fun clearAll() {
        allKeys().forEach { defaults.removeObjectForKey(it) }
        keychainDeleteAll()
    }

    // --- Last-read timestamps -------------------------------------------------

    actual fun saveLastReadTimestamp(
        pubkey: String,
        groupId: String,
        timestamp: Long,
    ) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        defaults.setInteger(timestamp, key)
    }

    actual fun getLastReadTimestamp(
        pubkey: String,
        groupId: String,
    ): Long? {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        if (defaults.objectForKey(key) == null) return null
        return defaults.integerForKey(key)
    }

    actual fun clearLastReadTimestamp(
        pubkey: String,
        groupId: String,
    ) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        remove(key)
    }

    actual fun getAllLastReadTimestamps(pubkey: String): Map<String, Long> {
        val prefix = LAST_READ_PREFIX + pubkey.hashCode() + "_"
        val result = mutableMapOf<String, Long>()
        allKeys().forEach { key ->
            if (key.startsWith(prefix)) {
                result[key.removePrefix(prefix)] = defaults.integerForKey(key)
            }
        }
        return result
    }

    // --- Last viewed group / route -------------------------------------------

    actual fun saveLastViewedGroup(
        pubkey: String,
        groupId: String,
        groupName: String?,
    ) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        saveString(key, "$groupId|${groupName ?: ""}")
    }

    actual fun getLastViewedGroup(pubkey: String): Pair<String, String?>? {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = getString(key) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val groupName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return Pair(parts[0], groupName)
    }

    actual fun clearLastViewedGroup(pubkey: String) {
        remove(LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode())
    }

    actual fun saveLastRoute(
        pubkey: String,
        routeHash: String,
    ) {
        saveString(LAST_ROUTE_PREFIX + pubkey.hashCode(), routeHash)
    }

    actual fun getLastRoute(pubkey: String): String? = getString(LAST_ROUTE_PREFIX + pubkey.hashCode())

    actual fun clearLastRoute(pubkey: String) {
        remove(LAST_ROUTE_PREFIX + pubkey.hashCode())
    }

    // --- Messages / pending events -------------------------------------------

    actual fun saveMessagesForGroup(
        pubkey: String,
        groupId: String,
        messagesJson: String,
    ) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        saveString(key, messagesJson)
    }

    actual fun getMessagesForGroup(
        pubkey: String,
        groupId: String,
    ): String? {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return getString(key)
    }

    actual fun clearMessagesForGroup(
        pubkey: String,
        groupId: String,
    ) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        remove(key)
    }

    actual fun clearAllMessagesForAccount(pubkey: String) {
        removeKeysWithPrefix(MESSAGES_PREFIX + pubkey.hashCode() + "_")
    }

    actual fun savePendingEvents(
        pubkey: String,
        eventsJson: String,
    ) {
        saveString(PENDING_EVENTS_PREFIX + pubkey.hashCode(), eventsJson)
    }

    actual fun getPendingEvents(pubkey: String): String? = getString(PENDING_EVENTS_PREFIX + pubkey.hashCode())

    actual fun clearPendingEvents(pubkey: String) {
        remove(PENDING_EVENTS_PREFIX + pubkey.hashCode())
    }

    // --- Relay groups / joined metadata --------------------------------------

    actual fun saveGroupsForRelay(
        relayUrl: String,
        groupsJson: String,
    ) {
        saveString(RELAY_GROUPS_PREFIX + relayUrl.hashCode(), groupsJson)
    }

    actual fun getGroupsForRelay(relayUrl: String): String? = getString(RELAY_GROUPS_PREFIX + relayUrl.hashCode())

    actual fun clearGroupsForRelay(relayUrl: String) {
        remove(RELAY_GROUPS_PREFIX + relayUrl.hashCode())
    }

    actual fun saveJoinedGroupMetadata(
        pubkey: String,
        relayUrl: String,
        groupsJson: String,
    ) {
        val key = JOINED_GROUP_META_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        saveString(key, groupsJson)
    }

    actual fun getJoinedGroupMetadata(
        pubkey: String,
        relayUrl: String,
    ): String? {
        val key = JOINED_GROUP_META_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        return getString(key)
    }

    actual fun clearAllJoinedGroupMetadataForAccount(pubkey: String) {
        removeKeysWithPrefix(JOINED_GROUP_META_PREFIX + pubkey.hashCode() + "_")
    }

    // --- Non-sensitive caches (public NIP-11 / metadata) ---------------------

    actual fun saveRelayMetadata(json: String) {
        saveString(RELAY_METADATA, json)
    }

    actual fun getRelayMetadata(): String? = getString(RELAY_METADATA)

    actual fun saveUserMetadataCache(json: String) {
        saveString(USER_METADATA_CACHE, json)
    }

    actual fun getUserMetadataCache(): String? = getString(USER_METADATA_CACHE)

    actual fun saveUserGroupListsCache(json: String) {
        saveString(USER_GROUP_LISTS_CACHE, json)
    }

    actual fun getUserGroupListsCache(): String? = getString(USER_GROUP_LISTS_CACHE)

    actual fun saveLiveCursors(
        relayUrl: String,
        json: String,
    ) {
        saveString(LIVE_CURSORS_PREFIX + relayUrl.hashCode(), json)
    }

    actual fun getLiveCursors(relayUrl: String): String? = getString(LIVE_CURSORS_PREFIX + relayUrl.hashCode())

    actual fun clearLiveCursors(relayUrl: String) {
        remove(LIVE_CURSORS_PREFIX + relayUrl.hashCode())
    }

    // --- Generic prefs --------------------------------------------------------

    actual fun saveBooleanPref(
        key: String,
        value: Boolean,
    ) {
        defaults.setBool(value, key)
    }

    actual fun getBooleanPref(
        key: String,
        default: Boolean,
    ): Boolean = if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    actual fun saveStringPref(
        key: String,
        value: String,
    ) {
        saveString(key, value)
    }

    actual fun getStringPref(
        key: String,
        default: String,
    ): String = getString(key) ?: default

    // --- Ad-hoc secrets (Keychain) -------------------------------------------

    actual fun saveSensitive(
        key: String,
        value: String,
    ) {
        keychainSet(key, value)
    }

    actual fun getSensitive(key: String): String? = keychainGet(key)

    actual fun clearSensitive(key: String) {
        keychainDelete(key)
    }

    actual suspend fun preloadMetadata() {}

    // --- Helpers --------------------------------------------------------------

    private fun allKeys(): List<String> = defaults.dictionaryRepresentation().keys.mapNotNull { it as? String }

    private fun removeKeysWithPrefix(prefix: String) {
        allKeys().forEach { if (it.startsWith(prefix)) defaults.removeObjectForKey(it) }
    }
}
