package org.nostr.nostrord.utils

/**
 * Returns the URL to use for loading an image.
 * Currently returns the URL unchanged on all platforms.
 */
expect fun getImageUrl(url: String): String

/**
 * Returns true when a URL likely points to an animated image (GIF or animated WebP).
 *
 * Detection uses file extension and well-known animated-media CDN host names.
 * - .gif  → always animated
 * - .webp → may be static or animated; we route through the animated renderer and let
 *           the decoder decide (single-frame result is rendered as a static image).
 * - CDN hosts (Giphy, Tenor) → animated regardless of extension
 */
fun isAnimatedImageUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.contains(".gif") || lower.contains(".webp")) return true
    val animatedHosts = listOf(
        "giphy.com", "media.giphy.com",
        "tenor.com", "media.tenor.com", "c.tenor.com"
    )
    return animatedHosts.any { lower.contains(it) }
}
