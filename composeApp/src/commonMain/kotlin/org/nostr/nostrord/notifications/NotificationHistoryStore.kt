package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getPersistedNotifications
import org.nostr.nostrord.storage.savePersistedNotifications

@Serializable
enum class NotificationType { REPLY, MENTION, REACTION, MESSAGE }

@Serializable
data class NotificationEntry(
    val id: String,
    val type: NotificationType,
    val groupId: String,
    val relayUrl: String,
    val actorPubkey: String,
    val createdAt: Long,
    val preview: String,
    val messageId: String? = null,
    val emoji: String? = null,
    val read: Boolean = false,
    // Snapshot of the group/relay display names at the moment the notification
    // was generated. Stored so the feed remains readable even when the group is
    // later forgotten or the relay's metadata cache misses on cold startup.
    // Pictures/icons are intentionally NOT snapshotted — those should refresh.
    val groupName: String? = null,
    val relayName: String? = null,
)

class NotificationHistoryStore {
    private val _entries = MutableStateFlow<List<NotificationEntry>>(emptyList())
    val entries: StateFlow<List<NotificationEntry>> = _entries.asStateFlow()

    private var currentPubkey: String? = null

    fun initialize(pubkey: String?) {
        currentPubkey = pubkey
        _entries.value = if (pubkey == null) emptyList()
        else SecureStorage.getPersistedNotifications(pubkey)
    }

    fun add(entry: NotificationEntry) {
        _entries.update { current ->
            if (current.any { it.id == entry.id }) current
            else (listOf(entry) + current).take(MAX_ENTRIES)
        }
        persist()
    }

    fun markRead(id: String) {
        var changed = false
        _entries.update { current ->
            current.map {
                if (it.id == id && !it.read) {
                    changed = true
                    it.copy(read = true)
                } else it
            }
        }
        if (changed) persist()
    }

    fun markAllRead() {
        _entries.update { current -> current.map { it.copy(read = true) } }
        persist()
    }

    /** Drop every entry from the in-memory feed and erase the persisted blob. */
    fun clearHistory() {
        _entries.value = emptyList()
        persist()
    }

    fun clear() {
        currentPubkey = null
        _entries.value = emptyList()
    }

    private fun persist() {
        val pubkey = currentPubkey ?: return
        SecureStorage.savePersistedNotifications(pubkey, _entries.value)
    }

    companion object {
        private const val MAX_ENTRIES = 50
    }
}
