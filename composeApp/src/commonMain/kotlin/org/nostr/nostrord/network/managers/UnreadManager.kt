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
    private val findMessageAuthor: (messageId: String) -> String? = { null },
    private val onUnreadIncrement: ((groupId: String, latestMessage: NostrGroupClient.NostrMessage, delta: Int) -> Unit)? = null,
    private val onReplyNotify: ((groupId: String, message: NostrGroupClient.NostrMessage) -> Unit)? = null,
    private val onMentionNotify: ((groupId: String, message: NostrGroupClient.NostrMessage) -> Unit)? = null,
    private val onReactionNotify: ((groupId: String, reaction: NostrGroupClient.NostrReaction) -> Unit)? = null,
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

        val latestReply = qualifying.filter { msg ->
            msg.tags.any { tag -> tag.size >= 2 && tag[0] == "e" && findMessageAuthor(tag[1]) == pubkey }
        }.maxByOrNull { it.createdAt }

        val latestMention = qualifying.filter { msg ->
            msg.tags.any { tag -> tag.size >= 2 && tag[0] == "p" && tag[1] == pubkey }
        }.maxByOrNull { it.createdAt }

        when {
            latestReply != null -> onReplyNotify?.invoke(groupId, latestReply)
            latestMention != null -> onMentionNotify?.invoke(groupId, latestMention)
            else -> onUnreadIncrement?.invoke(groupId, qualifying.maxBy { it.createdAt }, qualifying.size)
        }
    }

    fun onReactionReceived(groupId: String, reaction: NostrGroupClient.NostrReaction) {
        val pubkey = currentPubkey ?: return
        if (reaction.pubkey == pubkey) return
        if (!isJoined(groupId) || isRestricted(groupId)) return
        val lastRead = SecureStorage.getLastReadTimestamp(pubkey, groupId)
        val previousHighWater = _latestMessageTimestamps.value[groupId] ?: 0L
        val anchor = maxOf(
            lastRead ?: firstSeenAtByGroup.getOrPut(groupId) { epochSeconds() },
            previousHighWater
        )
        if (reaction.createdAt <= anchor) return

        // Reactions on the user's own message are direct interactions worth
        // surfacing on the group/relay badges, not just the notification feed.
        // Caller already verified the target message author == self.
        val highWaterAdvanced = reaction.createdAt > previousHighWater
        if (highWaterAdvanced) {
            _latestMessageTimestamps.update { it + (groupId to reaction.createdAt) }
        }

        val isActive = groupId == activeGroupId
        if (!(isActive && isAppFocused())) {
            _unreadCounts.update { current ->
                current + (groupId to ((current[groupId] ?: 0) + 1))
            }
        }
        persistEntries()

        onReactionNotify?.invoke(groupId, reaction)
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
