package org.nostr.nostrord.utils

/**
 * Returns the URL to use for loading an image.
 * On web platforms, this wraps external URLs with a CORS proxy (wsrv.nl).
 * On native platforms, returns the URL unchanged.
 *
 * @param url The original image URL
 * @return The URL to use for loading (may be proxied on web)
 */
expect fun getImageUrl(url: String): String

/**
 * Checks if a URL is an external URL that might need proxying.
 */
fun isExternalUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

/**
 * Returns true when a URL likely points to an animated image (GIF or animated WebP).
 *
 * Detection uses file extension and well-known animated-media CDN host names.
 * - .gif  → always animated
 * - .webp → may be static or animated; we route through the animated renderer and let
 *           the decoder decide (single-frame result is rendered as a static image).
 * - CDN hosts (Giphy, Tenor) → animated regardless of extension
 *
 * Detection here drives two behaviours:
 *   (a) route the URL to the animated renderer on JVM Desktop
 *   (b) add `&output=gif` to the wsrv.nl proxy on web targets for GIFs
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

/** Narrower check: true only for GIF URLs (used for wsrv.nl &output=gif parameter). */
fun isGifUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.contains(".gif")) return true
    val gifHosts = listOf(
        "giphy.com", "media.giphy.com",
        "tenor.com", "media.tenor.com", "c.tenor.com"
    )
    return gifHosts.any { lower.contains(it) }
}
