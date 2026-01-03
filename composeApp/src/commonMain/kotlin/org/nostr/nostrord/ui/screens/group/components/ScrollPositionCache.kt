package org.nostr.nostrord.ui.screens.group.components

/**
 * In-memory cache for scroll positions per group.
 * Allows restoring scroll position when returning to a previously viewed group.
 */
object ScrollPositionCache {
    data class ScrollPosition(
        val anchorKey: String,
        val offset: Int
    )

    private val positions = mutableMapOf<String, ScrollPosition>()

    fun save(groupId: String, anchorKey: String, offset: Int) {
        positions[groupId] = ScrollPosition(anchorKey, offset)
    }

    fun get(groupId: String): ScrollPosition? = positions[groupId]

    fun remove(groupId: String) {
        positions.remove(groupId)
    }

    fun clear() {
        positions.clear()
    }
}
