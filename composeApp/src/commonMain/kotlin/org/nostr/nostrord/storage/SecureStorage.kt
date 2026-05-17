package org.nostr.nostrord.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.nostr.nostrord.nostr.Crypto
import org.nostr.nostrord.nostr.toHexString

// 128-bit (32 hex) SHA-256 prefix of the pubkey. Used as the storage subkey
// for all per-account slots. Replaces the original `pubkey.hashCode()` form
// (32-bit, collision-prone — see fix(auth): commit) and a brief literal-pubkey
// form which exceeded java.util.prefs.Preferences' 80-char key limit on JVM.
// 128 bits keeps second-preimage astronomically out of reach (~2^128).
private fun pubkeyDigest(pubkey: String): String =
    Crypto.sha256(pubkey).toHexString().take(32)

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

    // Sensitive credential blob. Uses the platform's encrypted/secure path
    // (EncryptedSharedPreferences on Android, AES-GCM on JVM, localStorage on web).
    // Use these directly only for credentials. Account-scoped wrappers live as
    // extension functions below (`savePrivateKeyFor`, `saveBunkerUrlFor`, ...).
    fun saveSensitive(key: String, value: String)
    fun getSensitive(key: String): String?
    fun clearSensitive(key: String)

    // Preload large metadata blobs from async storage (IndexedDB on web) into the in-memory cache.
    // Must be called once before restoreJoinedGroupMetadataFromStorage to ensure synchronous reads work.
    // No-op on Android and JVM (EncryptedSharedPreferences / Preferences are synchronous).
    suspend fun preloadMetadata()
}

// Per-relay last-viewed group. Stored as "groupId|groupName" with `|` and `%`
// percent-escaped so the pipe stays unambiguous as the field separator.
private fun lastGroupForRelayKey(pubkey: String, relayUrl: String): String =
    "last_group_${pubkeyDigest(pubkey)}_${relayUrl.hashCode()}"

// Legacy key — used only by the one-shot read-time migration in getLastGroupForRelay.
// The pubkey portion used to be String.hashCode() (32-bit, collision-prone).
private fun legacyLastGroupForRelayKey(pubkey: String, relayUrl: String): String =
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
    if (raw.isNotBlank()) return decodeLastGroupValue(raw)
    // One-shot migration from legacy hashCode-based key.
    val legacy = getStringPref(legacyLastGroupForRelayKey(pubkey, relayUrl), "")
    if (legacy.isBlank()) return null
    saveStringPref(lastGroupForRelayKey(pubkey, relayUrl), legacy)
    saveStringPref(legacyLastGroupForRelayKey(pubkey, relayUrl), "")
    return decodeLastGroupValue(legacy)
}

fun SecureStorage.clearLastGroupForRelay(pubkey: String, relayUrl: String) {
    if (pubkey.isBlank() || relayUrl.isBlank()) return
    saveStringPref(lastGroupForRelayKey(pubkey, relayUrl), "")
    saveStringPref(legacyLastGroupForRelayKey(pubkey, relayUrl), "")
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

// Persisted timestamp of the most recently published (or locally applied) kind:10009 event.
// Survives app restarts so that handleKind10009Event can reject stale network events that
// would otherwise restore relays/groups the user explicitly removed while offline.
//
// Pubkey-scoped: a timestamp from account A must NOT bleed into account B's freshness
// check after a logout+login, otherwise B's kind:10009 (with an older createdAt) gets
// rejected as "stale" even though it's fresh for B. Without this, B sees no relays
// because `_kind10009Relays` never populates.
private fun kind10009TimestampKey(pubkey: String) = "kind10009_latest_ts_${pubkeyDigest(pubkey)}"
private fun legacyKind10009TimestampKey(pubkey: String) = "kind10009_latest_ts_${pubkey.hashCode()}"

fun SecureStorage.saveKind10009Timestamp(pubkey: String, timestamp: Long) {
    saveStringPref(kind10009TimestampKey(pubkey), timestamp.toString())
    // Drop legacy slot so a colliding pubkey can't read a stale write.
    saveStringPref(legacyKind10009TimestampKey(pubkey), "")
}

fun SecureStorage.loadKind10009Timestamp(pubkey: String): Long {
    val current = getStringPref(kind10009TimestampKey(pubkey), "").toLongOrNull()
    if (current != null) return current
    val legacy = getStringPref(legacyKind10009TimestampKey(pubkey), "0").toLongOrNull() ?: 0L
    if (legacy > 0L) {
        saveStringPref(kind10009TimestampKey(pubkey), legacy.toString())
        saveStringPref(legacyKind10009TimestampKey(pubkey), "")
    }
    return legacy
}

// Legacy global key — kept for one-shot migration on first run after the upgrade.
// Removed once a fresh kind:10009 arrives for any user.
internal fun SecureStorage.loadLegacyKind10009Timestamp(): Long {
    return getStringPref("kind10009_latest_ts", "0").toLongOrNull() ?: 0L
}

// ── Per-account NIP-29 relay list ───────────────────────────────────────────
// Pubkey-scoped wrappers around the legacy global `saveRelayList`/`loadRelayList`
// slot, which would otherwise leak relays across accounts. The first read for
// any pubkey adopts the legacy slot once and clears it.
private fun relayListForAccountKey(pubkey: String) = "relay_list_${pubkeyDigest(pubkey)}"
private fun legacyHashRelayListForAccountKey(pubkey: String) = "relay_list_${pubkey.hashCode()}"
private const val RELAY_LIST_MIGRATION_DONE_KEY = "relay_list_legacy_migrated"

fun SecureStorage.saveRelayListFor(pubkey: String, relays: List<String>) {
    if (pubkey.isBlank()) return
    try {
        saveStringPref(relayListForAccountKey(pubkey), Json.encodeToString<List<String>>(relays))
        saveStringPref(legacyHashRelayListForAccountKey(pubkey), "")
    } catch (_: Exception) {}
}

fun SecureStorage.loadRelayListFor(pubkey: String): List<String> {
    if (pubkey.isBlank()) return emptyList()
    val raw = getStringPref(relayListForAccountKey(pubkey), "")
    if (raw.isNotBlank()) {
        return try { Json.decodeFromString(raw) } catch (_: Exception) { emptyList() }
    }
    // First try the legacy hashCode-keyed slot from earlier builds of this branch.
    val legacyHash = getStringPref(legacyHashRelayListForAccountKey(pubkey), "")
    if (legacyHash.isNotBlank()) {
        val parsed: List<String> = try { Json.decodeFromString(legacyHash) } catch (_: Exception) { emptyList() }
        if (parsed.isNotEmpty()) {
            saveRelayListFor(pubkey, parsed)
            return parsed
        }
    }
    // Per-account slot is empty. Migrate from the legacy global slot once —
    // the first pubkey to read after the rollout adopts the legacy list.
    if (!getBooleanPref(RELAY_LIST_MIGRATION_DONE_KEY, false)) {
        val legacy = loadRelayList()
        if (legacy.isNotEmpty()) {
            saveRelayListFor(pubkey, legacy)
            saveRelayList(emptyList())
            saveBooleanPref(RELAY_LIST_MIGRATION_DONE_KEY, true)
            return legacy
        }
        saveBooleanPref(RELAY_LIST_MIGRATION_DONE_KEY, true)
    }
    return emptyList()
}

fun SecureStorage.clearRelayListFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(relayListForAccountKey(pubkey), "")
    saveStringPref(legacyHashRelayListForAccountKey(pubkey), "")
}

// ── Per-account "current relay" pointer ─────────────────────────────────────
// Pubkey-scoped wrappers around the legacy global `saveCurrentRelayUrl`, so a
// freshly added account doesn't inherit the previous account's last-used relay.
// The first read for any pubkey adopts the legacy slot once and clears it.
private fun currentRelayUrlForAccountKey(pubkey: String) =
    "current_relay_url_${pubkeyDigest(pubkey)}"
private fun legacyHashCurrentRelayUrlForAccountKey(pubkey: String) =
    "current_relay_url_${pubkey.hashCode()}"
private const val CURRENT_RELAY_URL_MIGRATION_DONE_KEY = "current_relay_url_legacy_migrated"

fun SecureStorage.saveCurrentRelayUrlFor(pubkey: String, relayUrl: String) {
    if (pubkey.isBlank()) return
    saveStringPref(currentRelayUrlForAccountKey(pubkey), relayUrl)
    saveStringPref(legacyHashCurrentRelayUrlForAccountKey(pubkey), "")
}

fun SecureStorage.getCurrentRelayUrlFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    val raw = getStringPref(currentRelayUrlForAccountKey(pubkey), "")
    if (raw.isNotBlank()) return raw
    // First try the legacy hashCode-keyed slot from earlier builds of this branch.
    val legacyHash = getStringPref(legacyHashCurrentRelayUrlForAccountKey(pubkey), "")
    if (legacyHash.isNotBlank()) {
        saveCurrentRelayUrlFor(pubkey, legacyHash)
        return legacyHash
    }
    if (!getBooleanPref(CURRENT_RELAY_URL_MIGRATION_DONE_KEY, false)) {
        val legacy = getCurrentRelayUrl()
        if (!legacy.isNullOrBlank()) {
            saveCurrentRelayUrlFor(pubkey, legacy)
            clearCurrentRelayUrl()
            saveBooleanPref(CURRENT_RELAY_URL_MIGRATION_DONE_KEY, true)
            return legacy
        }
        saveBooleanPref(CURRENT_RELAY_URL_MIGRATION_DONE_KEY, true)
    }
    return null
}

fun SecureStorage.clearCurrentRelayUrlFor(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(currentRelayUrlForAccountKey(pubkey), "")
    saveStringPref(legacyHashCurrentRelayUrlForAccountKey(pubkey), "")
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
    "restricted_groups_${pubkeyDigest(pubkey)}_${relayUrl.hashCode()}"

private fun legacyRestrictedGroupsKey(pubkey: String, relayUrl: String): String =
    "restricted_groups_${pubkey.hashCode()}_${relayUrl.hashCode()}"

fun SecureStorage.getRestrictedGroupsForRelay(
    pubkey: String,
    relayUrl: String,
    nowSeconds: Long
): Map<String, String> {
    val key = restrictedGroupsKey(pubkey, relayUrl)
    var raw = getStringPref(key, "")
    if (raw.isBlank()) {
        // One-shot migration from legacy hashCode-based key.
        val legacyKey = legacyRestrictedGroupsKey(pubkey, relayUrl)
        val legacyRaw = getStringPref(legacyKey, "")
        if (legacyRaw.isBlank()) return emptyMap()
        saveStringPref(key, legacyRaw)
        saveStringPref(legacyKey, "")
        raw = legacyRaw
    }
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
    "unread_entries_${pubkeyDigest(pubkey)}"

private fun legacyUnreadEntriesKey(pubkey: String): String =
    "unread_entries_${pubkey.hashCode()}"

internal fun SecureStorage.getUnreadEntries(pubkey: String): Map<String, UnreadEntry> {
    var raw = getStringPref(unreadEntriesKey(pubkey), "")
    if (raw.isBlank()) {
        // One-shot migration from legacy hashCode-based key.
        val legacyRaw = getStringPref(legacyUnreadEntriesKey(pubkey), "")
        if (legacyRaw.isBlank()) return emptyMap()
        saveStringPref(unreadEntriesKey(pubkey), legacyRaw)
        saveStringPref(legacyUnreadEntriesKey(pubkey), "")
        raw = legacyRaw
    }
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

// Notification history — persisted feed of cross-relay notifications shown in
// the notification center. Scoped by pubkey so multi-account devices stay isolated.
private fun notificationHistoryKey(pubkey: String): String =
    "notification_history_${pubkeyDigest(pubkey)}"

private fun legacyNotificationHistoryKey(pubkey: String): String =
    "notification_history_${pubkey.hashCode()}"

fun SecureStorage.getPersistedNotifications(
    pubkey: String
): List<org.nostr.nostrord.notifications.NotificationEntry> {
    var raw = getStringPref(notificationHistoryKey(pubkey), "")
    if (raw.isBlank()) {
        // One-shot migration from legacy hashCode-based key.
        val legacyRaw = getStringPref(legacyNotificationHistoryKey(pubkey), "")
        if (legacyRaw.isBlank()) return emptyList()
        saveStringPref(notificationHistoryKey(pubkey), legacyRaw)
        saveStringPref(legacyNotificationHistoryKey(pubkey), "")
        raw = legacyRaw
    }
    return try {
        Json.decodeFromString(raw)
    } catch (_: Exception) {
        emptyList()
    }
}

fun SecureStorage.savePersistedNotifications(
    pubkey: String,
    entries: List<org.nostr.nostrord.notifications.NotificationEntry>
) {
    try {
        saveStringPref(
            notificationHistoryKey(pubkey),
            Json.encodeToString<List<org.nostr.nostrord.notifications.NotificationEntry>>(entries)
        )
    } catch (_: Exception) {}
}

// Per-account "last active" timestamp in Unix seconds. Written when the user
// switches away from this account and as a periodic heartbeat while active so
// it survives crashes. Used to compute the catch-up `since` for notification
// subscriptions on the next switch-in.
private fun lastActiveAtKey(pubkey: String): String =
    "account_last_active_at_${pubkeyDigest(pubkey)}"
private fun legacyHashLastActiveAtKey(pubkey: String): String =
    "account_last_active_at_${pubkey.hashCode()}"

fun SecureStorage.saveLastActiveAt(pubkey: String, unixSeconds: Long) {
    if (pubkey.isBlank()) return
    saveStringPref(lastActiveAtKey(pubkey), unixSeconds.toString())
    saveStringPref(legacyHashLastActiveAtKey(pubkey), "")
}

fun SecureStorage.getLastActiveAt(pubkey: String): Long {
    if (pubkey.isBlank()) return 0L
    val current = getStringPref(lastActiveAtKey(pubkey), "").toLongOrNull()
    if (current != null) return current
    val legacy = getStringPref(legacyHashLastActiveAtKey(pubkey), "0").toLongOrNull() ?: 0L
    if (legacy > 0L) {
        saveStringPref(lastActiveAtKey(pubkey), legacy.toString())
        saveStringPref(legacyHashLastActiveAtKey(pubkey), "")
    }
    return legacy
}

fun SecureStorage.clearLastActiveAt(pubkey: String) {
    if (pubkey.isBlank()) return
    saveStringPref(lastActiveAtKey(pubkey), "")
    saveStringPref(legacyHashLastActiveAtKey(pubkey), "")
}

// ── Account-scoped credential storage ──────────────────────────────────────
// Each login method has its own pubkey-keyed slot so multiple accounts coexist.
// Legacy single-slot variants remain as a transient "active session" pointer;
// the one-shot legacy → per-account migration lives in StartupResolver.

// Storage subkey uses a 128-bit SHA-256 digest of the pubkey, not its
// String.hashCode(). The hashCode space is only 32 bits; second-preimage on
// 64-char hex is ~2^32 keygens and would let a colliding pubkey overwrite
// or read another account's credentials.
private fun privKeyForAccountKey(pubkey: String) = "priv_key_${pubkeyDigest(pubkey)}"
private fun bunkerUrlForAccountKey(pubkey: String) = "bunker_url_${pubkeyDigest(pubkey)}"
private fun bunkerClientPrivForAccountKey(pubkey: String) = "bunker_client_priv_${pubkeyDigest(pubkey)}"

// Legacy hashCode-based keys — used only by the read-time migration below.
private fun legacyHashPrivKeyForAccountKey(pubkey: String) = "priv_key_${pubkey.hashCode()}"
private fun legacyHashBunkerUrlForAccountKey(pubkey: String) = "bunker_url_${pubkey.hashCode()}"
private fun legacyHashBunkerClientPrivForAccountKey(pubkey: String) = "bunker_client_priv_${pubkey.hashCode()}"

private fun SecureStorage.migrateSensitiveSlot(newKey: String, legacyKey: String): String? {
    val legacy = getSensitive(legacyKey) ?: return null
    saveSensitive(newKey, legacy)
    clearSensitive(legacyKey)
    return legacy
}

fun SecureStorage.savePrivateKeyFor(pubkey: String, privateKeyHex: String) {
    if (pubkey.isBlank()) return
    saveSensitive(privKeyForAccountKey(pubkey), privateKeyHex)
    clearSensitive(legacyHashPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.getPrivateKeyFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    getSensitive(privKeyForAccountKey(pubkey))?.let { return it }
    return migrateSensitiveSlot(privKeyForAccountKey(pubkey), legacyHashPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.clearPrivateKeyFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(privKeyForAccountKey(pubkey))
    clearSensitive(legacyHashPrivKeyForAccountKey(pubkey))
}

fun SecureStorage.saveBunkerUrlFor(pubkey: String, bunkerUrl: String) {
    if (pubkey.isBlank()) return
    saveSensitive(bunkerUrlForAccountKey(pubkey), bunkerUrl)
    clearSensitive(legacyHashBunkerUrlForAccountKey(pubkey))
}

fun SecureStorage.getBunkerUrlFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    getSensitive(bunkerUrlForAccountKey(pubkey))?.let { return it }
    return migrateSensitiveSlot(bunkerUrlForAccountKey(pubkey), legacyHashBunkerUrlForAccountKey(pubkey))
}

fun SecureStorage.clearBunkerUrlFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(bunkerUrlForAccountKey(pubkey))
    clearSensitive(legacyHashBunkerUrlForAccountKey(pubkey))
}

fun SecureStorage.saveBunkerClientPrivateKeyFor(pubkey: String, clientPrivateKey: String) {
    if (pubkey.isBlank()) return
    saveSensitive(bunkerClientPrivForAccountKey(pubkey), clientPrivateKey)
    clearSensitive(legacyHashBunkerClientPrivForAccountKey(pubkey))
}

fun SecureStorage.getBunkerClientPrivateKeyFor(pubkey: String): String? {
    if (pubkey.isBlank()) return null
    getSensitive(bunkerClientPrivForAccountKey(pubkey))?.let { return it }
    return migrateSensitiveSlot(
        bunkerClientPrivForAccountKey(pubkey),
        legacyHashBunkerClientPrivForAccountKey(pubkey),
    )
}

fun SecureStorage.clearBunkerClientPrivateKeyFor(pubkey: String) {
    if (pubkey.isBlank()) return
    clearSensitive(bunkerClientPrivForAccountKey(pubkey))
    clearSensitive(legacyHashBunkerClientPrivForAccountKey(pubkey))
}

/** Convenience: wipe every credential slot belonging to [pubkey]. */
fun SecureStorage.clearAllCredentialsForAccount(pubkey: String) {
    clearPrivateKeyFor(pubkey)
    clearBunkerUrlFor(pubkey)
    clearBunkerClientPrivateKeyFor(pubkey)
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
