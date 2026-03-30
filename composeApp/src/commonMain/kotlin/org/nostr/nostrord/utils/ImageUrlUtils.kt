package org.nostr.nostrord.utils

/**
 * Returns the URL to use for loading an image.
 * Delegates to platform actuals, which should call [optimizeImageUrl] for CDN resizing.
 */
expect fun getImageUrl(url: String): String

/** Max width we request from CDNs — 2x of the 400dp chat image max. */
private const val CDN_MAX_WIDTH = 800

/** Hosts with unreliable image serving — never attempt to fetch, show as text instead. */
private val BLOCKED_IMAGE_HOSTS = listOf(
    "pomf2.lain.la",
)

/**
 * Returns true if the URL belongs to a host that should never be fetched
 * (broken CDN, persistent 404s, CORS issues, etc.).
 */
fun isBlockedImageHost(url: String): Boolean {
    val lower = url.lowercase()
    return BLOCKED_IMAGE_HOSTS.any { lower.contains(it) }
}

/**
 * Hosts that have their own resize/CDN — pass the URL through unchanged.
 * width/height query params on these hosts are either native resize directives
 * or harmless metadata that the server ignores.
 */
private val NATIVE_HOSTS = listOf(
    "nostr.build",
    "lain.la",
    "void.cat",
    "imgur.com",
    "i.imgur.com",
    "imgproxy.",
    "wsrv.nl",
    "weserv.nl",
    "primal.b-cdn.net",
    "giphy.com",
    "tenor.com",
    "i.redd.it",
    "pbs.twimg.com",
)

/**
 * Optimizes image URLs by requesting appropriately-sized images:
 * - Known hosts (nostr.build, lain.la, imgur, etc.): kept as-is (native resize or small enough)
 * - Unknown hosts: proxied through wsrv.nl for server-side resize + webp conversion
 *
 * Called from platform [getImageUrl] actuals so all platforms benefit from
 * reduced network downloads.
 */
fun optimizeImageUrl(url: String): String {
    val lower = url.lowercase()

    // Never proxy animated images — weserv converts GIFs to static webp
    if (isAnimatedImageUrl(url)) return url

    // Known hosts — no proxy needed
    if (NATIVE_HOSTS.any { lower.contains(it) }) return url

    // Proxy unknown hosts through wsrv.nl for server-side resize
    return proxyViaWeserv(url)
}

/** Proxies through wsrv.nl for resize. Encodes source URL to avoid param conflicts. */
private fun proxyViaWeserv(url: String): String {
    val stripped = url.removePrefix("https://").removePrefix("http://")
    val encoded = stripped.replace("&", "%26").replace("?", "%3F")
    return "https://images.weserv.nl/?url=${encoded}&w=${CDN_MAX_WIDTH}&fit=inside&output=webp"
}

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
