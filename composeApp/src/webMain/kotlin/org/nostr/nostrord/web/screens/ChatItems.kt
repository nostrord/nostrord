package org.nostr.nostrord.web.screens

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.getDateLabel
import kotlin.math.abs

// Mirrors the native buildChatItems grouping windows.
private const val MESSAGE_GROUP_WINDOW = 5 * 60
private const val SYSTEM_EVENT_GROUP_WINDOW = 2 * 60

/** System (moderation) event kind, used to pick a distinct icon and colour. Mirrors native. */
enum class SystemEventType { JOINED, ROLE_CHANGED, REMOVED, LEFT }

/** One rendered row in the chat: a date separator, a moderation/system event, or a message. */
sealed class WebChatItem {
    data class DateSeparator(val date: String) : WebChatItem()

    /** Divider shown to indicate where new (unread) messages begin. Mirrors native. */
    data object NewMessagesDivider : WebChatItem()

    data class SystemEvent(
        val pubkey: String,
        val action: String,
        val createdAt: Long,
        val id: String,
        val type: SystemEventType,
        val additionalUsers: List<String> = emptyList(),
    ) : WebChatItem() {
        val totalUsers: Int get() = 1 + additionalUsers.size
    }

    data class Message(
        val message: NostrGroupClient.NostrMessage,
        val firstInGroup: Boolean,
    ) : WebChatItem()
}

/** Stable identity for a chat row — used by Virtuoso's computeItemKey and the
 *  firstItemIndex prepend-diff so reorders/prepends never remount the wrong node. */
fun chatItemKey(item: WebChatItem): String = when (item) {
    is WebChatItem.DateSeparator -> "date-${item.date}"
    WebChatItem.NewMessagesDivider -> "new-messages-divider"
    is WebChatItem.SystemEvent -> "sys-${item.id}"
    is WebChatItem.Message -> item.message.id
}

/**
 * Build the ordered chat-item list from raw messages — a faithful port of the native
 * `buildChatItems`: date separators per calendar day, kind-9 messages grouped by author within
 * 5 min, and NIP-29 moderation events (9021 join, 9022 leave, 9000 role-change, 9001 removed)
 * turned into system rows. Consecutive same-action events within 2 min are merged. A no-role
 * 9000 (ambiguous add vs. role removal) and a 9001 that just confirms a recent 9022 leave are
 * suppressed, exactly like native.
 */
fun buildWebChatItems(
    messages: List<NostrGroupClient.NostrMessage>,
    lastReadTimestamp: Long? = null,
    currentUserPubkey: String? = null,
): List<WebChatItem> {
    val items = mutableListOf<WebChatItem>()
    val sorted = messages.sortedBy { it.createdAt }

    val leaveRequestTimestamps: Map<String, List<Long>> =
        sorted.filter { it.kind == 9022 }.groupBy({ it.pubkey }, { it.createdAt })

    var lastDate: String? = null
    var lastMessagePubkey: String? = null
    var lastMessageTime: Long? = null
    var pending: WebChatItem.SystemEvent? = null
    val pendingUsers = mutableListOf<String>()
    // Insert the "New messages" divider exactly once, before the first message
    // from someone else that's newer than the last-read snapshot. Skipping our
    // own messages avoids the obvious bug where the divider sits above what we
    // just wrote (issue #83).
    var dividerInserted = false

    fun flush() {
        pending?.let { items.add(it.copy(additionalUsers = pendingUsers.toList())) }
        pending = null
        pendingUsers.clear()
    }

    // Append a system event, merging with the pending one when it's the same action in-window.
    fun addSystemEvent(pubkey: String, action: String, createdAt: Long, id: String, type: SystemEventType) {
        val p = pending
        if (p != null && p.action == action && createdAt - p.createdAt <= SYSTEM_EVENT_GROUP_WINDOW) {
            pendingUsers.add(pubkey)
        } else {
            flush()
            pending = WebChatItem.SystemEvent(pubkey, action, createdAt, id, type)
        }
        lastMessagePubkey = null
        lastMessageTime = null
    }

    for (message in sorted) {
        val date = getDateLabel(message.createdAt)
        if (date != lastDate) {
            flush()
            items.add(WebChatItem.DateSeparator(date))
            lastDate = date
            lastMessagePubkey = null
            lastMessageTime = null
        }

        // Insert the divider before the first unread chat message from another
        // user. Restricted to kind:9 because the divider is a "new chat
        // messages" marker — placing it above a 9021 join or 9022 leave would
        // both look wrong and (worse) make the entry-alignment effect anchor
        // the viewport to a moderation row in the middle of the feed when the
        // socket streamed joins / leaves before any new chat.
        if (!dividerInserted &&
            message.kind == 9 &&
            lastReadTimestamp != null &&
            message.createdAt > lastReadTimestamp &&
            message.pubkey != currentUserPubkey
        ) {
            flush()
            items.add(WebChatItem.NewMessagesDivider)
            dividerInserted = true
        }

        when (message.kind) {
            9 -> {
                flush()
                val gap = lastMessageTime?.let { message.createdAt - it } ?: Long.MAX_VALUE
                val firstInGroup = message.pubkey != lastMessagePubkey || gap > MESSAGE_GROUP_WINDOW
                items.add(WebChatItem.Message(message, firstInGroup))
                lastMessagePubkey = message.pubkey
                lastMessageTime = message.createdAt
            }

            9000 -> {
                val pTag = message.tags.firstOrNull { it.firstOrNull() == "p" }
                val target = pTag?.getOrNull(1)
                val roles = pTag?.drop(2)?.filter { it.isNotBlank() } ?: emptyList()
                if (target != null && roles.isNotEmpty()) {
                    addSystemEvent(
                        target,
                        "is now ${roles.joinToString(", ")}",
                        message.createdAt,
                        message.id,
                        SystemEventType.ROLE_CHANGED,
                    )
                }
            }

            9001 -> {
                val target = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
                val recentLeave =
                    target != null &&
                        leaveRequestTimestamps[target]?.any { abs(message.createdAt - it) <= 5 * 60 } == true
                if (target != null && !recentLeave) {
                    addSystemEvent(target, "was removed from the group", message.createdAt, message.id, SystemEventType.REMOVED)
                }
            }

            9021 -> addSystemEvent(message.pubkey, "joined the group", message.createdAt, message.id, SystemEventType.JOINED)

            9022 -> addSystemEvent(message.pubkey, "left the group", message.createdAt, message.id, SystemEventType.LEFT)
        }
    }
    flush()
    return items
}
