package org.nostr.nostrord.ui.screens.group.model

import androidx.compose.runtime.Immutable
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.getDateLabel

// Time window for grouping consecutive messages from the same author (5 minutes)
private const val MESSAGE_GROUP_WINDOW_SECONDS = 5 * 60

// Time window for grouping consecutive system events (2 minutes)
private const val SYSTEM_EVENT_GROUP_WINDOW_SECONDS = 2 * 60

/**
 * Sealed class representing different types of chat items.
 * Marked as @Immutable for Compose stability to prevent unnecessary recompositions.
 */
@Immutable
sealed class ChatItem {
    @Immutable
    data class DateSeparator(val date: String) : ChatItem()

    /**
     * Divider shown to indicate where new (unread) messages begin.
     */
    @Immutable
    data object NewMessagesDivider : ChatItem()

    /**
     * System event (join/leave) with optional grouping.
     * When multiple users perform the same action close together, they are grouped.
     */
    @Immutable
    data class SystemEvent(
        val pubkey: String,
        val action: String,
        val createdAt: Long,
        val id: String,
        val additionalUsers: List<String> = emptyList() // Additional pubkeys for grouped events
    ) : ChatItem() {
        val totalUsers: Int get() = 1 + additionalUsers.size
        val isGrouped: Boolean get() = additionalUsers.isNotEmpty()
    }

    /**
     * Message item with grouping information.
     * @param message The actual message
     * @param isFirstInGroup True if this is the first message in a group (shows avatar/name)
     * @param isLastInGroup True if this is the last message in a group (adds bottom spacing)
     */
    @Immutable
    data class Message(
        val message: NostrGroupClient.NostrMessage,
        val isFirstInGroup: Boolean = true,
        val isLastInGroup: Boolean = true
    ) : ChatItem()
}

/**
 * Build chat items from messages.
 * @param messages The list of messages to process
 * @param lastReadTimestamp Optional timestamp of last read message (for new messages divider)
 */
fun buildChatItems(
    messages: List<NostrGroupClient.NostrMessage>,
    lastReadTimestamp: Long? = null
): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var lastDate: String? = null
    var lastMessagePubkey: String? = null
    var lastMessageTime: Long? = null
    var newMessagesDividerInserted = false

    val sortedMessages = messages.sortedBy { it.createdAt }

    // Track system events for grouping
    var pendingSystemEvent: ChatItem.SystemEvent? = null
    val pendingSystemEventUsers = mutableListOf<String>()

    fun flushPendingSystemEvent() {
        pendingSystemEvent?.let { event ->
            items.add(event.copy(additionalUsers = pendingSystemEventUsers.toList()))
        }
        pendingSystemEvent = null
        pendingSystemEventUsers.clear()
    }

    sortedMessages.forEachIndexed { index, message ->
        val messageDate = getDateLabel(message.createdAt)

        // Check if we need a date separator
        if (messageDate != lastDate) {
            flushPendingSystemEvent()
            items.add(ChatItem.DateSeparator(messageDate))
            lastDate = messageDate
            // Reset grouping after date separator
            lastMessagePubkey = null
            lastMessageTime = null
        }

        // Insert new messages divider before first unread message
        if (!newMessagesDividerInserted &&
            lastReadTimestamp != null &&
            message.createdAt > lastReadTimestamp
        ) {
            flushPendingSystemEvent()
            items.add(ChatItem.NewMessagesDivider)
            newMessagesDividerInserted = true
        }

        when (message.kind) {
            9 -> {
                // Flush any pending system events before adding a message
                flushPendingSystemEvent()

                // Determine if this message should be grouped with the previous one
                val timeSinceLastMessage = lastMessageTime?.let { message.createdAt - it } ?: Long.MAX_VALUE
                val isSameAuthor = message.pubkey == lastMessagePubkey
                val isWithinGroupWindow = timeSinceLastMessage <= MESSAGE_GROUP_WINDOW_SECONDS
                val isFirstInGroup = !isSameAuthor || !isWithinGroupWindow

                // Look ahead to determine if this is the last in group
                val nextMessage = sortedMessages.getOrNull(index + 1)
                val nextMessageDate = nextMessage?.let { getDateLabel(it.createdAt) }
                val isNextDifferentDate = nextMessageDate != null && nextMessageDate != messageDate
                val isNextDifferentAuthor = nextMessage?.pubkey != message.pubkey
                val isNextOutsideWindow = nextMessage?.let {
                    it.createdAt - message.createdAt > MESSAGE_GROUP_WINDOW_SECONDS
                } ?: true
                val isNextSystemEvent = nextMessage?.kind in listOf(9021, 9022)
                val isLastInGroup = nextMessage == null || isNextDifferentDate ||
                    isNextDifferentAuthor || isNextOutsideWindow || isNextSystemEvent

                items.add(ChatItem.Message(
                    message = message,
                    isFirstInGroup = isFirstInGroup,
                    isLastInGroup = isLastInGroup
                ))

                lastMessagePubkey = message.pubkey
                lastMessageTime = message.createdAt
            }
            9021 -> {
                val action = "joined the group"
                val pending = pendingSystemEvent

                // Check if we can group with pending system event
                if (pending != null &&
                    pending.action == action &&
                    message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                ) {
                    // Add to existing group
                    pendingSystemEventUsers.add(message.pubkey)
                } else {
                    // Flush previous and start new group
                    flushPendingSystemEvent()
                    pendingSystemEvent = ChatItem.SystemEvent(
                        pubkey = message.pubkey,
                        action = action,
                        createdAt = message.createdAt,
                        id = message.id
                    )
                }

                // Reset message grouping after system event
                lastMessagePubkey = null
                lastMessageTime = null
            }
            9022 -> {
                val action = "left the group"
                val pending = pendingSystemEvent

                // Check if we can group with pending system event
                if (pending != null &&
                    pending.action == action &&
                    message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                ) {
                    // Add to existing group
                    pendingSystemEventUsers.add(message.pubkey)
                } else {
                    // Flush previous and start new group
                    flushPendingSystemEvent()
                    pendingSystemEvent = ChatItem.SystemEvent(
                        pubkey = message.pubkey,
                        action = action,
                        createdAt = message.createdAt,
                        id = message.id
                    )
                }

                // Reset message grouping after system event
                lastMessagePubkey = null
                lastMessageTime = null
            }
        }
    }

    // Flush any remaining pending system event
    flushPendingSystemEvent()

    return items
}
