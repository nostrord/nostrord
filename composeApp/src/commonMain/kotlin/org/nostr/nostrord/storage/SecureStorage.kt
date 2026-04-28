package org.nostr.nostrord.storage

import kotlinx.serialization.Serializable
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

// Per-relay last-viewed group. Stored as "groupId|groupName" with `|` and `%`
// percent-escaped so the pipe stays unambiguous as the field separator.
private fun lastGroupForRelayKey(pubkey: String, relayUrl: String): String =
    "last_group_${pubkey.hashCode()}_${relayUrl.hashCode()}"

private fun encodeLastGroupValue(groupId: String, groupName: String?): String {
    fun esc(s: String) = s.replace("%", "%25").replace("|", "%7C")
    return if (groupName == null) esc(groupId) else "${esc(groupId)}|${esc(groupName)}"
}

private fun decodeLastGroupValue(raw: String): Pair<String, String?>? {
    if (raw.isBlank()) return null
    fun unesc(s: String) = s.replace("%7C", "|").replace("%25", "%")
    val parts = raw.split("|", limit = 2)
    val id = unesc(parts[0])
    if (id.isBlank()) return null
    val name = parts.getOrNull(1)?.let(::unesc)?.takeIf { it.isNotBlank() }
    return id to name
}

fun SecureStorage.saveLastGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String,
    groupName: String?
) {
    if (pubkey.isBlank() || relayUrl.isBlank() || groupId.isBlank()) return
    saveStringPref(lastGroupForRelayKey(pubkey, relayUrl), encodeLastGroupValue(groupId, groupName))
}

fun SecureStorage.getLastGroupForRelay(pubkey: String, relayUrl: String): Pair<String, String?>? {
    if (pubkey.isBlank() || relayUrl.isBlank()) return null
    val raw = getStringPref(lastGroupForRelayKey(pubkey, relayUrl), "")
    return decodeLastGroupValue(raw)
}

fun SecureStorage.clearLastGroupForRelay(pubkey: String, relayUrl: String) {
    if (pubkey.isBlank() || relayUrl.isBlank()) return
    saveStringPref(lastGroupForRelayKey(pubkey, relayUrl), "")
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

// Per-relay restricted groups — groupIds the relay CLOSED with "restricted" for this
// pubkey. Persisted so subsequent sessions exclude them from batched #d/#h REQs from
// the start (otherwise pyramid-style relays CLOSE the whole batch on the first
// connect, starving non-restricted groups of metadata until per-group CLOSEDs arrive).
// Scoped by pubkey+relay so different accounts don't leak each other's state.
// Entries auto-expire after RESTRICTED_GROUPS_TTL_S (7 days) — approval may have been
// granted since the last session and we want to retry.
private const val RESTRICTED_GROUPS_TTL_S = 7 * 24 * 3600L

@Serializable
private data class RestrictedGroupEntry(val reason: String, val ts: Long)

private fun restrictedGroupsKey(pubkey: String, relayUrl: String): String =
    "restricted_groups_${pubkey.hashCode()}_${relayUrl.hashCode()}"

fun SecureStorage.getRestrictedGroupsForRelay(
    pubkey: String,
    relayUrl: String,
    nowSeconds: Long
): Map<String, String> {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    if (raw.isBlank()) return emptyMap()
    val parsed: Map<String, RestrictedGroupEntry> = try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        return emptyMap()
    }
    val fresh = parsed.filterValues { nowSeconds - it.ts < RESTRICTED_GROUPS_TTL_S }
    if (fresh.size != parsed.size) {
        // Prune stale entries from storage so the blob doesn't grow unbounded.
        try { saveStringPref(key, Json.encodeToString(fresh)) } catch (_: Exception) {}
    }
    return fresh.mapValues { it.value.reason }
}

fun SecureStorage.addRestrictedGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String,
    reason: String,
    nowSeconds: Long
) {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    val current: MutableMap<String, RestrictedGroupEntry> = if (raw.isBlank()) {
        mutableMapOf()
    } else try {
        Json.decodeFromString<Map<String, RestrictedGroupEntry>>(raw).toMutableMap()
    } catch (_: Exception) {
        mutableMapOf()
    }
    current[groupId] = RestrictedGroupEntry(reason, nowSeconds)
    try { saveStringPref(key, Json.encodeToString<Map<String, RestrictedGroupEntry>>(current)) } catch (_: Exception) {}
}

fun SecureStorage.removeRestrictedGroupForRelay(
    pubkey: String,
    relayUrl: String,
    groupId: String
) {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    val raw = getStringPref(key, "")
    if (raw.isBlank()) return
    val current: MutableMap<String, RestrictedGroupEntry> = try {
        Json.decodeFromString<Map<String, RestrictedGroupEntry>>(raw).toMutableMap()
    } catch (_: Exception) {
        return
    }
    if (current.remove(groupId) != null) {
        try { saveStringPref(key, Json.encodeToString<Map<String, RestrictedGroupEntry>>(current)) } catch (_: Exception) {}
    }
}

// ── Unread state persistence ────────────────────────────────────────────────
// Persists per-account unread counters + high-water timestamps so badges,
// rail bubbles, and the title counter survive app restarts. The high-water
// guards against double-counting when relays re-deliver history on reconnect:
// any incoming message with createdAt <= highWater is treated as already-seen.

@Serializable
internal data class UnreadEntry(val count: Int, val highWater: Long)

private fun unreadEntriesKey(pubkey: String): String =
    "unread_entries_${pubkey.hashCode()}"

internal fun SecureStorage.getUnreadEntries(pubkey: String): Map<String, UnreadEntry> {
    val raw = getStringPref(unreadEntriesKey(pubkey), "")
    if (raw.isBlank()) return emptyMap()
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun SecureStorage.saveUnreadEntries(pubkey: String, entries: Map<String, UnreadEntry>) {
    try {
        saveStringPref(unreadEntriesKey(pubkey), Json.encodeToString<Map<String, UnreadEntry>>(entries))
    } catch (_: Exception) {}
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
