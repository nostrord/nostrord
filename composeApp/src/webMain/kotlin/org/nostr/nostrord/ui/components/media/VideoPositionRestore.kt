package org.nostr.nostrord.ui.components.media

import org.w3c.dom.HTMLVideoElement
import kotlin.time.TimeSource

private const val SAVE_THROTTLE_MS = 1_000L

/**
 * Wires [VideoViewedPositionCache] save/restore to a `<video>` element via DOM events.
 * Reading `src` from the element (rather than capturing a Kotlin url param) avoids the
 * stale-position-saved-under-new-url race when the same element gets a new src.
 */
fun HTMLVideoElement.attachPositionRestore() {
    var lastSave: TimeSource.Monotonic.ValueTimeMark? = null
    addEventListener("loadedmetadata", {
        val saved = VideoViewedPositionCache.get(src)
        val durationMs = (duration * 1000).toLong()
        if (saved != null &&
            saved > VideoViewedPositionCache.RESUME_THRESHOLD_MS &&
            durationMs > 0 &&
            saved < durationMs - VideoViewedPositionCache.RESUME_THRESHOLD_MS
        ) {
            currentTime = saved / 1000.0
        }
    })
    addEventListener("timeupdate", {
        val now = TimeSource.Monotonic.markNow()
        val last = lastSave
        if (last == null || (now - last).inWholeMilliseconds >= SAVE_THROTTLE_MS) {
            lastSave = now
            VideoViewedPositionCache.put(src, (currentTime * 1000).toLong())
        }
    })
}
