package org.nostr.nostrord.utils

import androidx.compose.ui.graphics.ImageBitmap

/**
 * LRU image cache bounded by estimated pixel byte usage (width × height × 4 RGBA).
 *
 * Unlike a count-based LruCache, this evicts entries when total byte usage exceeds
 * [maxBytes], preventing OOM from a few large images filling the cache.
 *
 * Single-entry cap: entries larger than [maxBytes] / 2 are not cached at all to
 * prevent one oversized image from evicting everything else.
 */
class ByteBoundedImageCache(
    private val maxBytes: Long
) {
    private data class Entry(
        val bitmap: ImageBitmap,
        val estimatedBytes: Long
    )

    // Doubly-linked list + map for O(1) LRU, same pattern as LruCache
    private class Node(
        val key: String,
        var entry: Entry,
        var prev: Node? = null,
        var next: Node? = null
    )

    private val head = Node("", Entry(ImageBitmap(1, 1), 0)).also { it.next = null }
    private val tail = Node("", Entry(ImageBitmap(1, 1), 0)).also { it.prev = null }
    private val map = HashMap<String, Node>()
    private var currentBytes = 0L

    init {
        head.next = tail
        tail.prev = head
    }

    private fun unlink(node: Node) {
        node.prev!!.next = node.next
        node.next!!.prev = node.prev
    }

    private fun addFirst(node: Node) {
        node.next = head.next
        node.prev = head
        head.next!!.prev = node
        head.next = node
    }

    fun get(key: String): ImageBitmap? {
        val node = map[key] ?: return null
        unlink(node)
        addFirst(node)
        return node.entry.bitmap
    }

    fun put(key: String, bitmap: ImageBitmap) {
        val bytes = bitmap.width.toLong() * bitmap.height * 4
        // Don't cache entries larger than half the budget
        if (bytes > maxBytes / 2) return

        // Remove existing if replacing
        val existing = map.remove(key)
        if (existing != null) {
            currentBytes -= existing.entry.estimatedBytes
            unlink(existing)
        }

        // Evict LRU until we fit
        while (currentBytes + bytes > maxBytes && tail.prev !== head) {
            val lru = tail.prev!!
            currentBytes -= lru.entry.estimatedBytes
            map.remove(lru.key)
            unlink(lru)
        }

        val node = Node(key, Entry(bitmap, bytes))
        map[key] = node
        addFirst(node)
        currentBytes += bytes
    }
}
