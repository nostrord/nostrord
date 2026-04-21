package org.nostr.nostrord.storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

expect object SecureStorage {
    fun savePrivateKey(privateKeyHex: String)
    fun getPrivateKey(): String?
    fun hasPrivateKey(): Boolean
    fun clearPrivateKey()
    
    fun saveCurrentRelayUrl(relayUrl: String)
    fun getCurrentRelayUrl(): String?
    fun clearCurrentRelayUrl()

    // Persisted relay list — all NIP-29 relays the user has added
    fun saveRelayList(relays: List<String>)
    fun loadRelayList(): List<String>
    
    // Account-scoped joined groups (namespaced by pubkey)
    fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>)
    fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String>
    fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String)
    fun clearAllJoinedGroupsForAccount(pubkey: String)
    
    // NIP-46 Bunker support
    fun saveBunkerUrl(bunkerUrl: String)
    fun getBunkerUrl(): String?
    fun hasBunkerUrl(): Boolean
    fun clearBunkerUrl()
    
    // NIP-46 Bunker User Pubkey support
    fun saveBunkerUserPubkey(pubkey: String)
    fun getBunkerUserPubkey(): String?
    fun clearBunkerUserPubkey()
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    fun saveBunkerClientPrivateKey(privateKey: String)
    fun getBunkerClientPrivateKey(): String?
    fun clearBunkerClientPrivateKey()

    // NIP-07 Browser Extension (for session persistence)
    fun saveNip07UserPubkey(pubkey: String)
    fun getNip07UserPubkey(): String?
    fun clearNip07UserPubkey()

    fun clearAll()

    // Last read timestamp tracking (account-scoped, per group)
    fun saveLastReadTimestamp(pubkey: String, groupId: String, timestamp: Long)
    fun getLastReadTimestamp(pubkey: String, groupId: String): Long?
    fun clearLastReadTimestamp(pubkey: String, groupId: String)
    fun getAllLastReadTimestamps(pubkey: String): Map<String, Long>

    // Last viewed group persistence (for restoring state on app restart)
    // Stores the group ID and name so the app can restore the last screen
    fun saveLastViewedGroup(pubkey: String, groupId: String, groupName: String?)
    fun getLastViewedGroup(pubkey: String): Pair<String, String?>?
    fun clearLastViewedGroup(pubkey: String)

    // Message persistence (for offline-first behavior)
    // Stores recent messages per group to survive app restarts
    fun saveMessagesForGroup(pubkey: String, groupId: String, messagesJson: String)
    fun getMessagesForGroup(pubkey: String, groupId: String): String?
    fun clearMessagesForGroup(pubkey: String, groupId: String)
    fun clearAllMessagesForAccount(pubkey: String)

    // Pending events persistence (for offline queue)
    fun savePendingEvents(pubkey: String, eventsJson: String)
    fun getPendingEvents(pubkey: String): String?
    fun clearPendingEvents(pubkey: String)

    // Group metadata cache (for offline-first relay switching)
    fun saveGroupsForRelay(relayUrl: String, groupsJson: String)
    fun getGroupsForRelay(relayUrl: String): String?
    fun clearGroupsForRelay(relayUrl: String)

    // NIP-11 relay metadata cache (persisted across restarts)
    fun saveRelayMetadata(json: String)
    fun getRelayMetadata(): String?

    // Live subscription cursors — last-seen event timestamp per group per relay
    fun saveLiveCursors(relayUrl: String, json: String)
    fun getLiveCursors(relayUrl: String): String?
    fun clearLiveCursors(relayUrl: String)

    // Generic boolean preference — backs user-facing feature flags / settings.
    fun saveBooleanPref(key: String, value: Boolean)
    fun getBooleanPref(key: String, default: Boolean): Boolean
}

// Legacy support functions (deprecated - use account-scoped versions)
@Deprecated("Use account-scoped saveJoinedGroupsForRelay with pubkey")
suspend fun SecureStorage.saveJoinedGroups(groups: Set<String>) {
    saveJoinedGroupsForRelay("legacy", "legacy", groups)
}

@Deprecated("Use account-scoped getJoinedGroupsForRelay with pubkey")
suspend fun SecureStorage.getJoinedGroups(): Set<String> {
    return getJoinedGroupsForRelay("legacy", "legacy")
}

@Deprecated("Use account-scoped clearJoinedGroupsForRelay with pubkey")
suspend fun SecureStorage.clearJoinedGroups() {
    clearJoinedGroupsForRelay("legacy", "legacy")
}
