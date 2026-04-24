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

    // Joined-group metadata cache — only the groups from kind:10009, scoped by pubkey.
    // Written on every kind:39000 event for a joined group and on leave/create.
    // Read on startup for instant sidebar display without waiting for the network.
    fun saveJoinedGroupMetadata(pubkey: String, relayUrl: String, groupsJson: String)
    fun getJoinedGroupMetadata(pubkey: String, relayUrl: String): String?
    fun clearAllJoinedGroupMetadataForAccount(pubkey: String)

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

    // Generic string preference — backs per-relay settings that aren't booleans (e.g. timestamps).
    fun saveStringPref(key: String, value: String)
    fun getStringPref(key: String, default: String): String

    // Preload large metadata blobs from async storage (IndexedDB on web) into the in-memory cache.
    // Must be called once before restoreJoinedGroupMetadataFromStorage to ensure synchronous reads work.
    // No-op on Android and JVM (EncryptedSharedPreferences / Preferences are synchronous).
    suspend fun preloadMetadata()
}

// Per-relay group-list EOSE timestamp — lets the app skip requestGroups() when the
// cached group list is fresh enough (< GROUP_CACHE_TTL_S seconds old).
private const val GROUP_CACHE_TTL_S = 3600L // 1 hour

fun SecureStorage.saveGroupListEoseTimestamp(relayUrl: String, timestampSeconds: Long) {
    saveStringPref("group_eose_ts_${relayUrl.hashCode()}", timestampSeconds.toString())
}

fun SecureStorage.getGroupListEoseTimestamp(relayUrl: String): Long {
    return getStringPref("group_eose_ts_${relayUrl.hashCode()}", "0").toLongOrNull() ?: 0L
}

fun SecureStorage.isGroupListCacheFresh(relayUrl: String, nowSeconds: Long): Boolean {
    val ts = getGroupListEoseTimestamp(relayUrl)
    return ts > 0L && (nowSeconds - ts) < GROUP_CACHE_TTL_S
}

// Per-relay full group-list EOSE timestamp — set only when requestGroups() (unfiltered) EOSE
// arrives, distinct from the joined-only timestamp saved by requestGroupsForIds().
// Lets hasFullGroupListBeenFetched() return true after an app restart without re-fetching.
fun SecureStorage.saveFullGroupListEoseTimestamp(relayUrl: String, timestampSeconds: Long) {
    saveStringPref("group_full_eose_ts_${relayUrl.hashCode()}", timestampSeconds.toString())
}

fun SecureStorage.isFullGroupListCacheFresh(relayUrl: String, nowSeconds: Long): Boolean {
    val ts = getStringPref("group_full_eose_ts_${relayUrl.hashCode()}", "0").toLongOrNull() ?: 0L
    return ts > 0L && (nowSeconds - ts) < GROUP_CACHE_TTL_S
}

// Per-relay lazy fetch mode — when true (the default), only joined-group metadata is fetched on
// connect; the full group list is deferred until the user expands "OTHER GROUPS".
// Set to false on relays where you always want the full list loaded at startup (EAGER mode).
fun SecureStorage.saveGroupFetchLazy(relayUrl: String, lazy: Boolean) {
    saveBooleanPref("group_fetch_lazy_${relayUrl.hashCode()}", lazy)
}

fun SecureStorage.isGroupFetchLazy(relayUrl: String): Boolean {
    return getBooleanPref("group_fetch_lazy_${relayUrl.hashCode()}", true)
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
