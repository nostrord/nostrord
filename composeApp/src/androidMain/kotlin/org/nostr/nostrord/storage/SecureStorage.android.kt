package org.nostr.nostrord.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

actual object SecureStorage {
    private const val PREFS_NAME = "nostr_secure_prefs"
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

    private lateinit var prefs: SharedPreferences
    
    fun initialize(context: Context) {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private fun ensureInitialized() {
        if (!::prefs.isInitialized) {
            throw IllegalStateException("SecureStorage not initialized. Call initialize(context) first.")
        }
    }
    
    actual fun savePrivateKey(privateKeyHex: String) {
        ensureInitialized()
        prefs.edit().putString(PRIVATE_KEY_PREF, privateKeyHex).apply()
    }
    
    actual fun getPrivateKey(): String? {
        ensureInitialized()
        return prefs.getString(PRIVATE_KEY_PREF, null)
    }
    
    actual fun hasPrivateKey(): Boolean {
        ensureInitialized()
        return prefs.contains(PRIVATE_KEY_PREF)
    }
    
    actual fun clearPrivateKey() {
        ensureInitialized()
        prefs.edit().remove(PRIVATE_KEY_PREF).apply()
    }
    
    actual fun saveCurrentRelayUrl(relayUrl: String) {
        ensureInitialized()
        prefs.edit().putString(CURRENT_RELAY_URL, relayUrl).apply()
    }
    
    actual fun getCurrentRelayUrl(): String? {
        ensureInitialized()
        return prefs.getString(CURRENT_RELAY_URL, null)
    }
    
    actual fun clearCurrentRelayUrl() {
        ensureInitialized()
        prefs.edit().remove(CURRENT_RELAY_URL).apply()
    }
    
    actual fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>) {
        ensureInitialized()
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = Json.encodeToString(groupIds.toList())
        prefs.edit().putString(key, json).apply()
    }

    actual fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String> {
        ensureInitialized()
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(json).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    actual fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String) {
        ensureInitialized()
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        prefs.edit().remove(key).apply()
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        ensureInitialized()
        val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
        try {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(accountPrefix) }.forEach { key ->
                editor.remove(key)
            }
            editor.apply()
        } catch (e: Exception) {
        }
    }
    
    // NIP-46 Bunker URL support
    actual fun saveBunkerUrl(bunkerUrl: String) {
        ensureInitialized()
        prefs.edit().putString(BUNKER_URL_PREF, bunkerUrl).apply()
    }
    
    actual fun getBunkerUrl(): String? {
        ensureInitialized()
        return prefs.getString(BUNKER_URL_PREF, null)
    }
    
    actual fun hasBunkerUrl(): Boolean {
        ensureInitialized()
        return prefs.contains(BUNKER_URL_PREF)
    }
    
    actual fun clearBunkerUrl() {
        ensureInitialized()
        prefs.edit().remove(BUNKER_URL_PREF).apply()
    }
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        ensureInitialized()
        prefs.edit().putString(BUNKER_USER_PUBKEY_PREF, pubkey).apply()
    }
    
    actual fun getBunkerUserPubkey(): String? {
        ensureInitialized()
        return prefs.getString(BUNKER_USER_PUBKEY_PREF, null)
    }
    
    actual fun clearBunkerUserPubkey() {
        ensureInitialized()
        prefs.edit().remove(BUNKER_USER_PUBKEY_PREF).apply()
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        ensureInitialized()
        prefs.edit().putString(BUNKER_CLIENT_PRIVATE_KEY_PREF, privateKey).apply()
    }

    actual fun getBunkerClientPrivateKey(): String? {
        ensureInitialized()
        return prefs.getString(BUNKER_CLIENT_PRIVATE_KEY_PREF, null)
    }

    actual fun clearBunkerClientPrivateKey() {
        ensureInitialized()
        prefs.edit().remove(BUNKER_CLIENT_PRIVATE_KEY_PREF).apply()
    }

    // NIP-07 Browser Extension (not used on Android, but required by expect)
    actual fun saveNip07UserPubkey(pubkey: String) {}
    actual fun getNip07UserPubkey(): String? = null
    actual fun clearNip07UserPubkey() {}

    actual fun clearAll() {
        ensureInitialized()
        prefs.edit().clear().apply()
    }

    // Last read timestamp tracking
    actual fun saveLastReadTimestamp(pubkey: String, groupId: String, timestamp: Long) {
        ensureInitialized()
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.edit().putLong(key, timestamp).apply()
    }

    actual fun getLastReadTimestamp(pubkey: String, groupId: String): Long? {
        ensureInitialized()
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        val value = prefs.getLong(key, -1L)
        return if (value == -1L) null else value
    }

    actual fun clearLastReadTimestamp(pubkey: String, groupId: String) {
        ensureInitialized()
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.edit().remove(key).apply()
    }

    actual fun getAllLastReadTimestamps(pubkey: String): Map<String, Long> {
        ensureInitialized()
        val prefix = LAST_READ_PREFIX + pubkey.hashCode() + "_"
        val result = mutableMapOf<String, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(prefix) && value is Long) {
                val groupHash = key.removePrefix(prefix)
                result[groupHash] = value
            }
        }
        return result
    }

    // Last viewed group persistence
    actual fun saveLastViewedGroup(pubkey: String, groupId: String, groupName: String?) {
        ensureInitialized()
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        // Store as "groupId|groupName" (groupName can be empty)
        val value = "$groupId|${groupName ?: ""}"
        prefs.edit().putString(key, value).apply()
    }

    actual fun getLastViewedGroup(pubkey: String): Pair<String, String?>? {
        ensureInitialized()
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = prefs.getString(key, null) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val groupId = parts[0]
        val groupName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return Pair(groupId, groupName)
    }

    actual fun clearLastViewedGroup(pubkey: String) {
        ensureInitialized()
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        prefs.edit().remove(key).apply()
    }

    // Message persistence
    actual fun saveMessagesForGroup(pubkey: String, groupId: String, messagesJson: String) {
        ensureInitialized()
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.edit().putString(key, messagesJson).apply()
    }

    actual fun getMessagesForGroup(pubkey: String, groupId: String): String? {
        ensureInitialized()
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return prefs.getString(key, null)
    }

    actual fun clearMessagesForGroup(pubkey: String, groupId: String) {
        ensureInitialized()
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.edit().remove(key).apply()
    }

    actual fun clearAllMessagesForAccount(pubkey: String) {
        ensureInitialized()
        val accountPrefix = MESSAGES_PREFIX + pubkey.hashCode() + "_"
        try {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith(accountPrefix) }.forEach { key ->
                editor.remove(key)
            }
            editor.apply()
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    // Pending events persistence
    actual fun savePendingEvents(pubkey: String, eventsJson: String) {
        ensureInitialized()
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        prefs.edit().putString(key, eventsJson).apply()
    }

    actual fun getPendingEvents(pubkey: String): String? {
        ensureInitialized()
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        return prefs.getString(key, null)
    }

    actual fun clearPendingEvents(pubkey: String) {
        ensureInitialized()
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        prefs.edit().remove(key).apply()
    }
}
