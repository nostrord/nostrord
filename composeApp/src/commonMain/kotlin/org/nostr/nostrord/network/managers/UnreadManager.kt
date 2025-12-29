package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.storage.SecureStorage

/**
 * Manages unread message state for groups.
 * Tracks last read timestamps and calculates unread counts.
 */
class UnreadManager {

    // Map of groupId to unread message count
    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    // Map of groupId to latest message timestamp (for comparison)
    private val _latestMessageTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestMessageTimestamps: StateFlow<Map<String, Long>> = _latestMessageTimestamps.asStateFlow()

    private var currentPubkey: String? = null

    /**
     * Initialize the manager with the current user's pubkey.
     */
    fun initialize(pubkey: String?) {
        currentPubkey = pubkey
        if (pubkey == null) {
            _unreadCounts.value = emptyMap()
            _latestMessageTimestamps.value = emptyMap()
        }
    }

    /**
     * Mark a group as read (user has viewed the messages).
     * Saves the current time as the last read timestamp.
     */
    fun markAsRead(groupId: String) {
        val pubkey = currentPubkey ?: return
        val currentTime = epochSeconds()
        SecureStorage.saveLastReadTimestamp(pubkey, groupId, currentTime)

        // Update unread count to 0 for this group
        _unreadCounts.value = _unreadCounts.value + (groupId to 0)
    }

    /**
     * Get the last read timestamp for a group.
     */
    fun getLastReadTimestamp(groupId: String): Long? {
        val pubkey = currentPubkey ?: return null
        return SecureStorage.getLastReadTimestamp(pubkey, groupId)
    }

    /**
     * Update unread counts based on messages.
     * Call this when messages are loaded/updated for a group.
     */
    fun updateUnreadCount(groupId: String, messages: List<NostrGroupClient.NostrMessage>) {
        val pubkey = currentPubkey ?: return
        if (messages.isEmpty()) return

        // Get last read timestamp
        val lastReadTimestamp = SecureStorage.getLastReadTimestamp(pubkey, groupId)

        // Update latest message timestamp
        val latestTimestamp = messages.maxOfOrNull { it.createdAt } ?: 0L
        _latestMessageTimestamps.value = _latestMessageTimestamps.value + (groupId to latestTimestamp)

        // Count unread messages (messages after last read, excluding user's own messages)
        val unreadCount = if (lastReadTimestamp == null) {
            // If never read, all messages are "unread", but cap at a reasonable number
            // to avoid overwhelming badge. Just show indicator that there are messages.
            messages.count { it.pubkey != pubkey }.coerceAtMost(99)
        } else {
            messages.count { it.createdAt > lastReadTimestamp && it.pubkey != pubkey }
        }

        _unreadCounts.value = _unreadCounts.value + (groupId to unreadCount)
    }

    /**
     * Check if a group has unread messages.
     */
    fun hasUnread(groupId: String): Boolean {
        return (_unreadCounts.value[groupId] ?: 0) > 0
    }

    /**
     * Get unread count for a specific group.
     */
    fun getUnreadCount(groupId: String): Int {
        return _unreadCounts.value[groupId] ?: 0
    }

    /**
     * Clear all unread state (e.g., on logout).
     */
    fun clear() {
        currentPubkey = null
        _unreadCounts.value = emptyMap()
        _latestMessageTimestamps.value = emptyMap()
    }
}
