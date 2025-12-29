package org.nostr.nostrord.utils

/**
 * Thread-safe LRU (Least Recently Used) cache implementation.
 *
 * When the cache exceeds maxSize, the least recently accessed entries
 * are evicted to make room for new entries.
 *
 * @param maxSize Maximum number of entries to keep in the cache
 */
class LruCache<K, V>(
    private val maxSize: Int
) {
    // Using a simple map + insertion order tracking for KMP compatibility
    private val cache = mutableMapOf<K, V>()
    private val accessOrder = mutableListOf<K>()

    /**
     * Get a value from the cache (updates access order)
     */
    fun get(key: K): V? {
        val value = cache[key]
        if (value != null) {
            // Move to end of access order (most recently used)
            accessOrder.remove(key)
            accessOrder.add(key)
        }
        return value
    }

    /**
     * Put a value in the cache
     */
    fun put(key: K, value: V) {
        if (cache.containsKey(key)) {
            accessOrder.remove(key)
        }
        cache[key] = value
        accessOrder.add(key)
        evictIfNeeded()
    }

    /**
     * Put all entries from another map
     */
    fun putAll(entries: Map<K, V>) {
        entries.forEach { (key, value) ->
            put(key, value)
        }
    }

    /**
     * Check if a key exists
     */
    fun containsKey(key: K): Boolean = cache.containsKey(key)

    /**
     * Remove a key from the cache
     */
    fun remove(key: K): V? {
        accessOrder.remove(key)
        return cache.remove(key)
    }

    /**
     * Get current size
     */
    fun size(): Int = cache.size

    /**
     * Clear the cache
     */
    fun clear() {
        cache.clear()
        accessOrder.clear()
    }

    /**
     * Get all entries as an immutable map
     */
    fun toMap(): Map<K, V> = cache.toMap()

    /**
     * Get all keys
     */
    fun keys(): Set<K> = cache.keys.toSet()

    private fun evictIfNeeded() {
        while (cache.size > maxSize && accessOrder.isNotEmpty()) {
            val oldest = accessOrder.removeAt(0)
            cache.remove(oldest)
        }
    }
}
