package org.nostr.nostrord.ui.components.emoji

import androidx.compose.runtime.mutableStateListOf

object RecentEmojiStore {
    private const val MAX_RECENTS = 24

    private val _recents = mutableStateListOf<String>()
    val recents: List<String> get() = _recents

    fun recordUsage(emoji: String) {
        _recents.remove(emoji)
        _recents.add(0, emoji)
        if (_recents.size > MAX_RECENTS) _recents.removeAt(_recents.lastIndex)
    }
}
