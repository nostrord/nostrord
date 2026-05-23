package org.nostr.nostrord.ui.components.media

import org.nostr.nostrord.utils.LruCache

/** Survives player release so resumes work across re-mount, on any platform with a real player. */
object VideoViewedPositionCache {
    /** Below this many ms, restoring the saved position is more friction than help. */
    const val RESUME_THRESHOLD_MS = 5_000L

    private val cache = LruCache<String, Long>(100)

    fun put(
        url: String,
        positionMs: Long,
    ) {
        if (cache.get(url) == positionMs) return
        cache.put(url, positionMs)
    }

    fun get(url: String): Long? = cache.get(url)
}
