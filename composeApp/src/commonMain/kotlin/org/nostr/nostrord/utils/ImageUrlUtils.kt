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
