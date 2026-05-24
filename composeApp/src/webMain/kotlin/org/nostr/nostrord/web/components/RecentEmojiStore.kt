package org.nostr.nostrord.web.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Most-recently-used emoji, surfaced as the first picker category (mirrors native, in-memory). */
object RecentEmojiStore {
    private const val MAX_RECENTS = 24

    private val _recents = MutableStateFlow<List<String>>(emptyList())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    fun record(emoji: String) {
        _recents.value = (listOf(emoji) + _recents.value.filter { it != emoji }).take(MAX_RECENTS)
    }
}
