package org.nostr.nostrord.utils

/**
 * URL decode a percent-encoded string.
 *
 * IMPORTANT: Handles multi-byte UTF-8 sequences correctly.
 * Example: %E6%97%A5 → 日 (not æ—¥)
 *
 * The previous implementation incorrectly converted each percent-encoded
 * byte to a Char individually, which corrupted multi-byte UTF-8 characters
 * like CJK text, emojis, and other non-ASCII Unicode.
 */
fun String.urlDecode(): String {
    val bytes = mutableListOf<Byte>()
    var i = 0

    while (i < length) {
        when {
            this[i] == '%' && i + 2 < length -> {
                try {
                    val hex = substring(i + 1, i + 3)
                    bytes.add(hex.toInt(16).toByte())
                    i += 3
                } catch (e: NumberFormatException) {
                    // Invalid hex sequence, keep original character as UTF-8 bytes
                    bytes.addAll(this[i].toString().encodeToByteArray().toList())
                    i++
                }
            }
            this[i] == '+' -> {
                bytes.add(' '.code.toByte())
                i++
            }
            else -> {
                // Regular character - encode to UTF-8 bytes
                bytes.addAll(this[i].toString().encodeToByteArray().toList())
                i++
            }
        }
    }

    // Decode accumulated bytes as UTF-8
    return bytes.toByteArray().decodeToString()
}

/**
 * Normalize a relay URL for consistent comparison and deduplication.
 *
 * - Removes trailing slashes
 * - Lowercases the scheme and host (wss://RELAY.COM → wss://relay.com)
 * - Trims whitespace
 *
 * This prevents the same relay appearing under different map keys
 * (e.g. "wss://relay.example.com" vs "wss://relay.example.com/").
 */
fun String.normalizeRelayUrl(): String {
    val trimmed = trim().trimEnd('/')
    // Lowercase scheme + host but preserve path case.
    // WebSocket URLs rarely have meaningful paths beyond "/",
    // but we handle it just in case.
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed
    val afterScheme = schemeEnd + 3
    // Find end of host (first '/' after scheme, or ':' for port, or end of string)
    val pathStart = trimmed.indexOf('/', afterScheme)
    return if (pathStart < 0) {
        // No path — lowercase everything
        trimmed.lowercase()
    } else {
        // Lowercase scheme+host, keep path as-is
        trimmed.substring(0, pathStart).lowercase() + trimmed.substring(pathStart)
    }
}

/**
 * Validate a relay URL. Accepts `wss://` always, and `ws://` only for
 * localhost / loopback hosts (useful for local development relays).
 */
/**
 * Add a scheme to a bare relay host. If the input already has `ws://` or `wss://`,
 * it is returned unchanged. Otherwise, localhost/loopback hosts get `ws://` and
 * everything else gets `wss://`.
 */
fun String.toRelayUrl(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) return trimmed
    val host = trimmed.substringBefore('/').substringBefore(':').lowercase()
    val scheme = if (host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1") "ws" else "wss"
    return "$scheme://$trimmed"
}

fun isValidRelayUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.startsWith("wss://") && trimmed.length > 6) return true
    if (trimmed.startsWith("ws://") && trimmed.length > 5) {
        val host = trimmed.removePrefix("ws://")
            .substringBefore('/')
            .substringBefore(':')
            .lowercase()
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }
    return false
}
