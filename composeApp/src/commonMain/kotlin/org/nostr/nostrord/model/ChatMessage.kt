package org.nostr.nostrord.model

data class ChatMessage(
    val author: String,
    val text: String,
    val channel: String,
    val timestamp: Long,
)
