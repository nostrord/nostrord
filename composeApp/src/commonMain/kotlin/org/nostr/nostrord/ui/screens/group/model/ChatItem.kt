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

    /**
     * Zap event (kind 9321) - Lightning payment request.
     * Shows sender avatar, amount, recipient info, and optional emoji/message content.
     */
    @Immutable
    data class ZapEvent(
        val id: String,
        val senderPubkey: String,    // Who sent the zap
        val recipientPubkey: String, // Who received the zap
        val amount: Long,            // Amount in sats
        val content: String,         // Emoji or message
        val createdAt: Long
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

    // Map of pubkey → list of timestamps for join requests (kind 9021).
    // Used to suppress kind 9000 that is just the relay auto-confirming a join.
    val joinRequestTimestamps: Map<String, List<Long>> = sortedMessages
        .filter { it.kind == 9021 }
        .groupBy({ it.pubkey }, { it.createdAt })

    // Map of pubkey → list of timestamps for leave requests (kind 9022).
    // Used to suppress kind 9001 that is just the relay auto-confirming a leave.
    val leaveRequestTimestamps: Map<String, List<Long>> = sortedMessages
        .filter { it.kind == 9022 }
        .groupBy({ it.pubkey }, { it.createdAt })

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
                val isNextSystemEvent = nextMessage?.kind in listOf(9000, 9001, 9021, 9022, 9321)
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
            9321 -> {
                // Zap request event (nutzap)
                flushPendingSystemEvent()

                // Parse amount from tags - "amount" tag contains sats as string
                val amountSats = message.tags
                    .find { it.size >= 2 && it[0] == "amount" }
                    ?.getOrNull(1)
                    ?.toLongOrNull() ?: 0L

                // Parse recipient from "p" tag
                val recipientPubkey = message.tags
                    .find { it.size >= 2 && it[0] == "p" }
                    ?.getOrNull(1)
                    ?: message.pubkey // Fallback to sender if no recipient

                items.add(ChatItem.ZapEvent(
                    id = message.id,
                    senderPubkey = message.pubkey,
                    recipientPubkey = recipientPubkey,
                    amount = amountSats,
                    content = message.content,
                    createdAt = message.createdAt
                ))

                // Reset message grouping after zap event
                lastMessagePubkey = null
                lastMessageTime = null
            }
            // kind 9000 (put-user): not shown in chat — entry is already
            // covered by kind 9021 "joined the group". The relay emits 9000
            // as an automatic confirmation; only kind 9001 (removal) is a
            // meaningful moderation action to display.
            9000 -> {
                // Show "was added" only when an admin manually added someone.
                // Suppress if the target sent a kind 9021 join request within 5 min
                // (that means the relay auto-confirmed the join, already shown as "joined").
                val targetPubkey = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
                val hasRecentJoinRequest = targetPubkey != null &&
                    joinRequestTimestamps[targetPubkey]?.any {
                        kotlin.math.abs(message.createdAt - it) <= 5 * 60
                    } == true
                if (targetPubkey != null && !hasRecentJoinRequest) {
                    val action = "was added to the group"
                    val pending = pendingSystemEvent

                    if (pending != null &&
                        pending.action == action &&
                        message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                    ) {
                        pendingSystemEventUsers.add(targetPubkey)
                    } else {
                        flushPendingSystemEvent()
                        pendingSystemEvent = ChatItem.SystemEvent(
                            pubkey = targetPubkey,
                            action = action,
                            createdAt = message.createdAt,
                            id = message.id
                        )
                    }

                    lastMessagePubkey = null
                    lastMessageTime = null
                }
            }
            9001 -> {
                // Show "was removed" only when an admin manually removed someone.
                // Suppress if the target sent a kind 9022 leave request within 5 min
                // (that means the relay auto-confirmed the leave, already shown as "left").
                val targetPubkey = message.tags.firstOrNull { it.firstOrNull() == "p" }?.getOrNull(1)
                val hasRecentLeaveRequest = targetPubkey != null &&
                    leaveRequestTimestamps[targetPubkey]?.any {
                        kotlin.math.abs(message.createdAt - it) <= 5 * 60
                    } == true
                if (targetPubkey != null && !hasRecentLeaveRequest) {
                    val action = "was removed from the group"
                    val pending = pendingSystemEvent

                    if (pending != null &&
                        pending.action == action &&
                        message.createdAt - pending.createdAt <= SYSTEM_EVENT_GROUP_WINDOW_SECONDS
                    ) {
                        pendingSystemEventUsers.add(targetPubkey)
                    } else {
                        flushPendingSystemEvent()
                        pendingSystemEvent = ChatItem.SystemEvent(
                            pubkey = targetPubkey,
                            action = action,
                            createdAt = message.createdAt,
                            id = message.id
                        )
                    }

                    lastMessagePubkey = null
                    lastMessageTime = null
                }
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
