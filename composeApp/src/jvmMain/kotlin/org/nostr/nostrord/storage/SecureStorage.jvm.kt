package org.nostr.nostrord.storage

import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

actual object SecureStorage {
    private val prefs = Preferences.userNodeForPackage(SecureStorage::class.java)
    private const val PRIVATE_KEY_PREF = "nostr_private_key"
    private const val ENCRYPTION_KEY_PREF = "encryption_key"
    private const val JOINED_GROUPS_PREFIX = "joined_groups_"
    private const val CURRENT_RELAY_URL = "current_relay_url"
    private const val RELAY_LIST = "relay_list"
    private const val BUNKER_URL_PREF = "nostr_bunker_url"
    private const val BUNKER_USER_PUBKEY_PREF = "nostr_bunker_user_pubkey"
    private const val BUNKER_CLIENT_PRIVATE_KEY_PREF = "nostr_bunker_client_private_key"
    private const val LAST_READ_PREFIX = "last_read_"
    private const val LAST_VIEWED_GROUP_PREFIX = "last_viewed_group_"
    private const val MESSAGES_PREFIX = "messages_"
    private const val PENDING_EVENTS_PREFIX = "pending_events_"
    private const val RELAY_GROUPS_PREFIX = "relay_groups_"

    init {
        if (prefs.get(ENCRYPTION_KEY_PREF, null) == null) {
            val key = generateEncryptionKey()
            prefs.put(ENCRYPTION_KEY_PREF, Base64.getEncoder().encodeToString(key.encoded))
        }
    }
    
    private fun generateEncryptionKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        return keyGen.generateKey()
    }
    
    private fun getEncryptionKey(): SecretKey {
        val keyString = prefs.get(ENCRYPTION_KEY_PREF, null)
            ?: throw IllegalStateException("Encryption key not found")
        val keyBytes = Base64.getDecoder().decode(keyString)
        return SecretKeySpec(keyBytes, "AES")
    }
    
    private fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, getEncryptionKey())
        val encrypted = cipher.doFinal(data.toByteArray())
        return Base64.getEncoder().encodeToString(encrypted)
    }
    
    private fun decrypt(encryptedData: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.DECRYPT_MODE, getEncryptionKey())
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedData))
        return String(decrypted)
    }
    
    actual fun savePrivateKey(privateKeyHex: String) {
        val encrypted = encrypt(privateKeyHex)
        prefs.put(PRIVATE_KEY_PREF, encrypted)
        prefs.flush()
    }
    
    actual fun getPrivateKey(): String? {
        val encrypted = prefs.get(PRIVATE_KEY_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }
    
    actual fun hasPrivateKey(): Boolean {
        return prefs.get(PRIVATE_KEY_PREF, null) != null
    }
    
    actual fun clearPrivateKey() {
        prefs.remove(PRIVATE_KEY_PREF)
        prefs.flush()
    }
    
    actual fun saveCurrentRelayUrl(relayUrl: String) {
        saveString(CURRENT_RELAY_URL, relayUrl)
    }
    
    actual fun getCurrentRelayUrl(): String? {
        return getString(CURRENT_RELAY_URL)
    }
    
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

    actual fun saveJoinedGroupsForRelay(pubkey: String, relayUrl: String, groupIds: Set<String>) {
        // Account-scoped key: prefix + pubkey hash + relay hash
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = Json.encodeToString(groupIds.toList())
        saveString(key, json)
    }

    actual fun getJoinedGroupsForRelay(pubkey: String, relayUrl: String): Set<String> {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        val json = getString(key) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(json).toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    actual fun clearJoinedGroupsForRelay(pubkey: String, relayUrl: String) {
        val key = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_" + relayUrl.hashCode()
        remove(key)
    }

    actual fun clearAllJoinedGroupsForAccount(pubkey: String) {
        try {
            val accountPrefix = JOINED_GROUPS_PREFIX + pubkey.hashCode() + "_"
            prefs.keys().forEach { key ->
                if (key.startsWith(accountPrefix)) {
                    prefs.remove(key)
                }
            }
            prefs.flush()
        } catch (e: Exception) {
        }
    }
    
    // NIP-46 Bunker URL support
    actual fun saveBunkerUrl(bunkerUrl: String) {
        val encrypted = encrypt(bunkerUrl)
        prefs.put(BUNKER_URL_PREF, encrypted)
        prefs.flush()
    }
    
    actual fun getBunkerUrl(): String? {
        val encrypted = prefs.get(BUNKER_URL_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }
    
    actual fun hasBunkerUrl(): Boolean {
        return prefs.get(BUNKER_URL_PREF, null) != null
    }
    
    actual fun clearBunkerUrl() {
        prefs.remove(BUNKER_URL_PREF)
        prefs.flush()
    }
    
    // NIP-46 Bunker User Pubkey support
    actual fun saveBunkerUserPubkey(pubkey: String) {
        val encrypted = encrypt(pubkey)
        prefs.put(BUNKER_USER_PUBKEY_PREF, encrypted)
        prefs.flush()
    }
    
    actual fun getBunkerUserPubkey(): String? {
        val encrypted = prefs.get(BUNKER_USER_PUBKEY_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }
    
    actual fun clearBunkerUserPubkey() {
        prefs.remove(BUNKER_USER_PUBKEY_PREF)
        prefs.flush()
    }
    
    // NIP-46 Bunker Client Private Key (for session persistence)
    actual fun saveBunkerClientPrivateKey(privateKey: String) {
        val encrypted = encrypt(privateKey)
        prefs.put(BUNKER_CLIENT_PRIVATE_KEY_PREF, encrypted)
        prefs.flush()
    }

    actual fun getBunkerClientPrivateKey(): String? {
        val encrypted = prefs.get(BUNKER_CLIENT_PRIVATE_KEY_PREF, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }

    actual fun clearBunkerClientPrivateKey() {
        prefs.remove(BUNKER_CLIENT_PRIVATE_KEY_PREF)
        prefs.flush()
    }

    // NIP-07 Browser Extension (not used on JVM, but required by expect)
    actual fun saveNip07UserPubkey(pubkey: String) {}
    actual fun getNip07UserPubkey(): String? = null
    actual fun clearNip07UserPubkey() {}

    actual fun clearAll() {
        prefs.clear()
        prefs.flush()
    }

    // Last read timestamp tracking
    actual fun saveLastReadTimestamp(pubkey: String, groupId: String, timestamp: Long) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.putLong(key, timestamp)
        prefs.flush()
    }

    actual fun getLastReadTimestamp(pubkey: String, groupId: String): Long? {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        val value = prefs.getLong(key, -1L)
        return if (value == -1L) null else value
    }

    actual fun clearLastReadTimestamp(pubkey: String, groupId: String) {
        val key = LAST_READ_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        prefs.remove(key)
        prefs.flush()
    }

    actual fun getAllLastReadTimestamps(pubkey: String): Map<String, Long> {
        val prefix = LAST_READ_PREFIX + pubkey.hashCode() + "_"
        val result = mutableMapOf<String, Long>()
        try {
            prefs.keys().forEach { key ->
                if (key.startsWith(prefix)) {
                    val groupHash = key.removePrefix(prefix)
                    val timestamp = prefs.getLong(key, -1L)
                    if (timestamp != -1L) {
                        result[groupHash] = timestamp
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore errors
        }
        return result
    }

    private fun saveString(key: String, value: String) {
        val encrypted = encrypt(value)
        prefs.put(key, encrypted)
        prefs.flush()
    }
    
    private fun getString(key: String): String? {
        val encrypted = prefs.get(key, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }

    // Last viewed group persistence
    actual fun saveLastViewedGroup(pubkey: String, groupId: String, groupName: String?) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        // Store as "groupId|groupName" (groupName can be empty)
        val value = "$groupId|${groupName ?: ""}"
        saveString(key, value)
    }

    actual fun getLastViewedGroup(pubkey: String): Pair<String, String?>? {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        val value = getString(key) ?: return null
        val parts = value.split("|", limit = 2)
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val groupId = parts[0]
        val groupName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        return Pair(groupId, groupName)
    }

    actual fun clearLastViewedGroup(pubkey: String) {
        val key = LAST_VIEWED_GROUP_PREFIX + pubkey.hashCode()
        remove(key)
    }

    // Message persistence
    actual fun saveMessagesForGroup(pubkey: String, groupId: String, messagesJson: String) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        saveString(key, messagesJson)
    }

    actual fun getMessagesForGroup(pubkey: String, groupId: String): String? {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        return getString(key)
    }

    actual fun clearMessagesForGroup(pubkey: String, groupId: String) {
        val key = MESSAGES_PREFIX + pubkey.hashCode() + "_" + groupId.hashCode()
        remove(key)
    }

    actual fun clearAllMessagesForAccount(pubkey: String) {
        try {
            val accountPrefix = MESSAGES_PREFIX + pubkey.hashCode() + "_"
            prefs.keys().forEach { key ->
                if (key.startsWith(accountPrefix)) {
                    prefs.remove(key)
                }
            }
            prefs.flush()
        } catch (e: Exception) {
            // Ignore errors
        }
    }

    // Pending events persistence
    actual fun savePendingEvents(pubkey: String, eventsJson: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        saveString(key, eventsJson)
    }

    actual fun getPendingEvents(pubkey: String): String? {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        return getString(key)
    }

    actual fun clearPendingEvents(pubkey: String) {
        val key = PENDING_EVENTS_PREFIX + pubkey.hashCode()
        remove(key)
    }

    // Group metadata cache
    actual fun saveGroupsForRelay(relayUrl: String, groupsJson: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        saveString(key, groupsJson)
    }

    actual fun getGroupsForRelay(relayUrl: String): String? {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        return getString(key)
    }

    actual fun clearGroupsForRelay(relayUrl: String) {
        val key = RELAY_GROUPS_PREFIX + relayUrl.hashCode()
        remove(key)
    }
}
