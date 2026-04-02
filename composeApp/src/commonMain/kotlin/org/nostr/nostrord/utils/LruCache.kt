package org.nostr.nostrord.utils

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * LRU (Least Recently Used) cache using a doubly-linked list + HashMap for O(1) operations.
 *
 * Thread-safe: all operations are synchronized via atomicfu (works on all KMP targets).
 *
 * KMP-compatible: does not rely on java.util.LinkedHashMap's removeEldestEntry which is
 * unavailable on JS/WasmJS targets.
 *
 * @param maxSize Maximum number of entries to keep in the cache
 */
class LruCache<K, V>(
    private val maxSize: Int
) : SynchronizedObject() {
    private class Node<K, V>(
        val key: K,
        var value: V,
        var prev: Node<K, V>? = null,
        var next: Node<K, V>? = null
    )

    // Sentinel nodes — avoids null checks in link/unlink
    private val head = Node<K, V>(null as K, null as V) // most recent
    private val tail = Node<K, V>(null as K, null as V) // least recent
    private val map = HashMap<K, Node<K, V>>(maxSize + 1)

    init {
        head.next = tail
        tail.prev = head
    }

    private fun unlink(node: Node<K, V>) {
        node.prev!!.next = node.next
        node.next!!.prev = node.prev
    }

    private fun addFirst(node: Node<K, V>) {
        node.next = head.next
        node.prev = head
        head.next!!.prev = node
        head.next = node
    }

    /** Get a value from the cache (promotes to most-recently-used). O(1). */
    fun get(key: K): V? = synchronized(this) {
        val node = map[key] ?: return@synchronized null
        unlink(node)
        addFirst(node)
        node.value
    }

    /** Put a value in the cache, evicting the LRU entry if over capacity. O(1). */
    fun put(key: K, value: V): Unit = synchronized(this) {
        val existing = map[key]
        if (existing != null) {
            unlink(existing)
            existing.value = value
            addFirst(existing)
        } else {
            val node = Node(key, value)
            map[key] = node
            addFirst(node)
            if (map.size > maxSize) {
                val lru = tail.prev!!
                unlink(lru)
                map.remove(lru.key)
            }
        }
    }

    /** Put all entries from another map. */
    fun putAll(entries: Map<K, V>): Unit = synchronized(this) {
        entries.forEach { (key, value) -> put(key, value) }
    }

    /** Check if a key exists (does NOT promote access order). */
    fun containsKey(key: K): Boolean = synchronized(this) { map.containsKey(key) }

    /** Remove a key from the cache. */
    fun remove(key: K): V? = synchronized(this) {
        val node = map.remove(key) ?: return@synchronized null
        unlink(node)
        node.value
    }

    /** Get current size. */
    fun size(): Int = synchronized(this) { map.size }

    /** Clear the cache. */
    fun clear(): Unit = synchronized(this) {
        map.clear()
        head.next = tail
        tail.prev = head
    }

    /** Get all entries as an immutable map (snapshot). */
    fun toMap(): Map<K, V> = synchronized(this) {
        val result = HashMap<K, V>(map.size)
        for (entry in map) {
            result[entry.key] = entry.value.value
        }
        result
    }

    /** Get all keys (snapshot). */
    fun keys(): Set<K> = synchronized(this) { map.keys.toSet() }
}
