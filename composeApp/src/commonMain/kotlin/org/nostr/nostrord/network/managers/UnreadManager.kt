package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.UnreadEntry
import org.nostr.nostrord.storage.getUnreadEntries
import org.nostr.nostrord.storage.saveUnreadEntries

class UnreadManager(
    private val isJoined: (String) -> Boolean = { true },
    private val isRestricted: (String) -> Boolean = { false },
    private val isAppFocused: () -> Boolean = { true },
    private val onUnreadIncrement: ((groupId: String, latestMessage: NostrGroupClient.NostrMessage, delta: Int) -> Unit)? = null,
) {

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    // High-water mark per group: createdAt of the newest message we've already
    // processed. Used as an extra anchor floor so re-delivered history (across
    // reconnects or restarts) doesn't double-count.
    private val _latestMessageTimestamps = MutableStateFlow<Map<String, Long>>(emptyMap())
    val latestMessageTimestamps: StateFlow<Map<String, Long>> = _latestMessageTimestamps.asStateFlow()

    private var currentPubkey: String? = null
    private var activeGroupId: String? = null

    // First-time-seen anchor for groups with no persisted lastRead. Prevents the
    // initial history sync from inflating the badge for groups never opened.
    private val firstSeenAtByGroup = mutableMapOf<String, Long>()

    fun setActiveGroup(groupId: String?) {
        val previous = activeGroupId
        activeGroupId = groupId
        // markAsRead the outgoing group so messages buffered inside GroupManager's
        // 300ms ordering window — flushed after the screen unmounts — don't
        // retroactively count as unread.
        if (previous != null && previous != groupId) markAsRead(previous)
    }

    fun initialize(pubkey: String?) {
        currentPubkey = pubkey
        firstSeenAtByGroup.clear()
        if (pubkey == null) {
            _unreadCounts.value = emptyMap()
            _latestMessageTimestamps.value = emptyMap()
            return
        }
        val persisted = SecureStorage.getUnreadEntries(pubkey)
        _unreadCounts.value = persisted.mapValues { it.value.count }
        _latestMessageTimestamps.value = persisted.mapValues { it.value.highWater }
    }

    fun markAsRead(groupId: String) {
        val pubkey = currentPubkey ?: return
        SecureStorage.saveLastReadTimestamp(pubkey, groupId, epochSeconds())
        _unreadCounts.update { it + (groupId to 0) }
        persistEntries()
    }

    fun getLastReadTimestamp(groupId: String): Long? =
        currentPubkey?.let { SecureStorage.getLastReadTimestamp(it, groupId) }

    fun getUnreadCount(groupId: String): Int = _unreadCounts.value[groupId] ?: 0

    fun onMessagesFlushed(groupId: String, newMessages: List<NostrGroupClient.NostrMessage>) {
        val pubkey = currentPubkey ?: return
        if (newMessages.isEmpty()) return
        if (!isJoined(groupId) || isRestricted(groupId)) return

        // Capture the previous high-water *before* advancing it, so the anchor
        // can use it to filter re-delivered history that was already counted.
        val previousHighWater = _latestMessageTimestamps.value[groupId] ?: 0L
        val latestInBatch = newMessages.maxOfOrNull { it.createdAt } ?: 0L
        val highWaterAdvanced = latestInBatch > previousHighWater
        if (highWaterAdvanced) {
            _latestMessageTimestamps.update { it + (groupId to latestInBatch) }
        }

        val isActive = groupId == activeGroupId
        // Active group + app focused: user is reading live — silent. Persist
        // any high-water advance so a future session doesn't re-process.
        if (isActive && isAppFocused()) {
            if (highWaterAdvanced) persistEntries()
            return
        }

        val lastRead = SecureStorage.getLastReadTimestamp(pubkey, groupId)
        val anchor = maxOf(
            lastRead ?: firstSeenAtByGroup.getOrPut(groupId) { epochSeconds() },
            previousHighWater,
        )
        val qualifying = newMessages.filter {
            it.kind == 9 && it.pubkey != pubkey && it.createdAt > anchor
        }
        if (qualifying.isEmpty()) {
            if (highWaterAdvanced) persistEntries()
            return
        }

        // Active-but-unfocused: still notify (sound + popup) but don't bump the
        // badge — refocus shows the messages immediately, marking them as read.
        if (!isActive) {
            _unreadCounts.update { current ->
                current + (groupId to ((current[groupId] ?: 0) + qualifying.size))
            }
        }
        persistEntries()
        onUnreadIncrement?.invoke(groupId, qualifying.maxBy { it.createdAt }, qualifying.size)
    }

    fun clear() {
        currentPubkey = null
        activeGroupId = null
        _unreadCounts.value = emptyMap()
        _latestMessageTimestamps.value = emptyMap()
        firstSeenAtByGroup.clear()
    }

    private fun persistEntries() {
        val pubkey = currentPubkey ?: return
        val counts = _unreadCounts.value
        val highWaters = _latestMessageTimestamps.value
        val entries = (counts.keys + highWaters.keys).associateWith { id ->
            UnreadEntry(counts[id] ?: 0, highWaters[id] ?: 0L)
        }
        SecureStorage.saveUnreadEntries(pubkey, entries)
    }
}
