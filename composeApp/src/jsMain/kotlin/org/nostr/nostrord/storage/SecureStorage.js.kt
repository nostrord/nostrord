package org.nostr.nostrord.storage

import kotlinx.browser.localStorage

actual object SecureStorage {
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    private const val LAST_READ_PREFIX = "last_read_"
    private const val LAST_VIEWED_GROUP_PREFIX = "last_viewed_group_"
    private const val MESSAGES_PREFIX = "messages_"
    private const val PENDING_EVENTS_PREFIX = "pending_events_"

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
    
    // NIP-46 Bunker URL support
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
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        localStorage.setItem(BUNKER_USER_PUBKEY_PREF, pubkey)
    }
    
    actual fun getBunkerUserPubkey(): String? {
        return localStorage.getItem(BUNKER_USER_PUBKEY_PREF)
    }
    
    actual fun clearBunkerUserPubkey() {
        localStorage.removeItem(BUNKER_USER_PUBKEY_PREF)
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        localStorage.setItem(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey)
    }
    
    actual fun getBunkerClientPrivateKey(): String? {
        return localStorage.getItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }
    
    actual fun clearBunkerClientPrivateKey() {
        localStorage.removeItem(BUNKER_CLIENT_PRIVATE_KEY_PREF)
    }
    
    actual fun clearAll() {
        localStorage.clear()
    }

    // Last read timestamp tracking
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

    // Last viewed group persistence
    actual fun saveLastViewedGroup(pubkey: String, groupId: String, groupName: String?) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        // Store as "groupId|groupName" (groupName can be empty)
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

    // Message persistence
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

    // Pending events persistence
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
}
