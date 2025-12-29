package org.nostr.nostrord.ui.screens.group.model

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.utils.getDateLabel

// Time window for grouping consecutive messages from the same author (5 minutes)
private const val MESSAGE_GROUP_WINDOW_SECONDS = 5 * 60

sealed class ChatItem {
    data class DateSeparator(val date: String) : ChatItem()
    data class SystemEvent(
        val pubkey: String,
        val action: String,
        val createdAt: Long,
        val id: String
    ) : ChatItem()
    /**
     * Message item with grouping information.
     * @param message The actual message
     * @param isFirstInGroup True if this is the first message in a group (shows avatar/name)
     * @param isLastInGroup True if this is the last message in a group (adds bottom spacing)
     */
    data class Message(
        val message: NostrGroupClient.NostrMessage,
        val isFirstInGroup: Boolean = true,
        val isLastInGroup: Boolean = true
    ) : ChatItem()
}

fun buildChatItems(messages: List<NostrGroupClient.NostrMessage>): List<ChatItem> {
    val items = mutableListOf<ChatItem>()
    var lastDate: String? = null
    var lastMessagePubkey: String? = null
    var lastMessageTime: Long? = null

    val sortedMessages = messages.sortedBy { it.createdAt }

    sortedMessages.forEachIndexed { index, message ->
        val messageDate = getDateLabel(message.createdAt)

        // Check if we need a date separator
        if (messageDate != lastDate) {
            items.add(ChatItem.DateSeparator(messageDate))
            lastDate = messageDate
            // Reset grouping after date separator
            lastMessagePubkey = null
            lastMessageTime = null
        }

        when (message.kind) {
            9 -> {
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
                items.add(ChatItem.SystemEvent(
                    pubkey = message.pubkey,
                    action = "joined the group",
                    createdAt = message.createdAt,
                    id = message.id
                ))
                // Reset grouping after system event
                lastMessagePubkey = null
                lastMessageTime = null
            }
            9022 -> {
                items.add(ChatItem.SystemEvent(
                    pubkey = message.pubkey,
                    action = "left the group",
                    createdAt = message.createdAt,
                    id = message.id
                ))
                // Reset grouping after system event
                lastMessagePubkey = null
                lastMessageTime = null
            }
        }
    }

    return items
}
