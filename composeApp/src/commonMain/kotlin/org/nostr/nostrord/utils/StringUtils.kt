package org.nostr.nostrord.utils

// Maps the low surrogate of U+1D400-U+1D7FF (Mathematical Alphanumeric Symbols, all sharing
// high surrogate \uD835) to plain ASCII. Ranges: (startOffset, endOffset, baseAsciiCode)
// where offset is relative to \uDC00. Gaps in Script/Fraktur/Double-Struck blocks exist
// because those letters pre-date the math block and live at separate code points.
private val mathLowSurrogateToAscii: Map<Char, Char> by lazy {
    val map = HashMap<Char, Char>(512)
    val ranges = arrayOf(
        intArrayOf(0x000, 0x019, 'A'.code), intArrayOf(0x01A, 0x033, 'a'.code), // Bold
        intArrayOf(0x034, 0x04D, 'A'.code), intArrayOf(0x04E, 0x054, 'a'.code), // Italic
        intArrayOf(0x056, 0x067, 'i'.code),                                       // Italic small i-z (h gap)
        intArrayOf(0x068, 0x081, 'A'.code), intArrayOf(0x082, 0x09B, 'a'.code), // Bold Italic
        intArrayOf(0x09C, 0x09C, 'A'.code), intArrayOf(0x09E, 0x09F, 'C'.code), // Script Capital
        intArrayOf(0x0A2, 0x0A2, 'G'.code), intArrayOf(0x0A5, 0x0A6, 'J'.code),
        intArrayOf(0x0A9, 0x0AC, 'N'.code), intArrayOf(0x0AE, 0x0B5, 'S'.code),
        intArrayOf(0x0B6, 0x0B9, 'a'.code), intArrayOf(0x0BB, 0x0BB, 'f'.code), // Script Small
        intArrayOf(0x0BD, 0x0C3, 'h'.code), intArrayOf(0x0C5, 0x0CF, 'p'.code),
        intArrayOf(0x0D0, 0x0E9, 'A'.code), intArrayOf(0x0EA, 0x103, 'a'.code), // Bold Script
        intArrayOf(0x104, 0x105, 'A'.code), intArrayOf(0x107, 0x10A, 'D'.code), // Fraktur Capital
        intArrayOf(0x10D, 0x114, 'J'.code), intArrayOf(0x116, 0x11C, 'S'.code),
        intArrayOf(0x11E, 0x137, 'a'.code),                                       // Fraktur Small
        intArrayOf(0x138, 0x139, 'A'.code), intArrayOf(0x13B, 0x13E, 'D'.code), // Double-Struck Capital
        intArrayOf(0x140, 0x144, 'I'.code), intArrayOf(0x146, 0x146, 'O'.code),
        intArrayOf(0x14A, 0x150, 'S'.code),
        intArrayOf(0x152, 0x16B, 'a'.code),                                       // Double-Struck Small
        intArrayOf(0x16C, 0x185, 'A'.code), intArrayOf(0x186, 0x19F, 'a'.code), // Bold Fraktur
        intArrayOf(0x1A0, 0x1B9, 'A'.code), intArrayOf(0x1BA, 0x1D3, 'a'.code), // Sans-Serif
        intArrayOf(0x1D4, 0x1ED, 'A'.code), intArrayOf(0x1EE, 0x207, 'a'.code), // Sans-Serif Bold
        intArrayOf(0x208, 0x221, 'A'.code), intArrayOf(0x222, 0x23B, 'a'.code), // Sans-Serif Italic
        intArrayOf(0x23C, 0x255, 'A'.code), intArrayOf(0x256, 0x26F, 'a'.code), // Sans-Serif Bold Italic
        intArrayOf(0x270, 0x289, 'A'.code), intArrayOf(0x28A, 0x2A3, 'a'.code), // Monospace
    )
    for (r in ranges) {
        val startLow = 0xDC00 + r[0]
        val baseCode = r[2]
        for (low in startLow..0xDC00 + r[1]) {
            map[low.toChar()] = (baseCode + (low - startLow)).toChar()
        }
    }
    map
}

// Small-capital letters from IPA / Latin Extended blocks used as decorative text.
// These are BMP code points (no surrogate pairs), so a plain map suffices.
private val smallCapsToAscii: Map<Char, Char> = mapOf(
    'ᴀ' to 'a', 'ʙ' to 'b', 'ᴄ' to 'c', 'ᴅ' to 'd',
    'ᴇ' to 'e', 'ꜰ' to 'f', 'ɢ' to 'g', 'ʜ' to 'h',
    'ɪ' to 'i', 'ᴊ' to 'j', 'ᴋ' to 'k', 'ʟ' to 'l',
    'ᴍ' to 'm', 'ɴ' to 'n', 'ᴏ' to 'o', 'ᴘ' to 'p',
    'ʀ' to 'r', 'ꜱ' to 's', 'ᴛ' to 't', 'ᴜ' to 'u',
    'ᴠ' to 'v', 'ᴡ' to 'w', 'ʏ' to 'y', 'ᴢ' to 'z',
)

// Maps decorative Unicode letter variants (mathematical styles, full-width, small caps) to
// plain ASCII lowercase. Callers do not need ignoreCase = true after this.
fun String.normalizeForSearch(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c == '\uD835' && i + 1 < length -> {
                val low = this[i + 1]
                val ascii = mathLowSurrogateToAscii[low]
                if (ascii != null) sb.append(ascii) else { sb.append(c); sb.append(low) }
                i += 2
            }
            c.code in 0xFF21..0xFF3A -> { sb.append(('A'.code + c.code - 0xFF21).toChar()); i++ }
            c.code in 0xFF41..0xFF5A -> { sb.append(('a'.code + c.code - 0xFF41).toChar()); i++ }
            else -> { sb.append(smallCapsToAscii[c] ?: c); i++ }
        }
    }
    return sb.toString().lowercase()
}

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
    val hadScheme = trimmed.startsWith("ws://") || trimmed.startsWith("wss://")
    val afterScheme = trimmed.removePrefix("wss://").removePrefix("ws://")
    val authority = afterScheme.substringBefore('/')
    // Reject userinfo: `ws://localhost:7777@evil.com` would bypass loopback checks.
    if ('@' in authority) return ""
    if (hadScheme) return trimmed
    val host = authority.substringBefore(':').lowercase()
    val scheme = if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "[::1]" || host == "::1") "ws" else "wss"
    return "$scheme://$trimmed"
}

fun isValidRelayUrl(url: String): Boolean {
    val trimmed = url.trim()
    val afterScheme = when {
        trimmed.startsWith("wss://") && trimmed.length > 6 -> trimmed.removePrefix("wss://")
        trimmed.startsWith("ws://") && trimmed.length > 5 -> trimmed.removePrefix("ws://")
        else -> return false
    }
    val authority = afterScheme.substringBefore('/')
    if ('@' in authority) return false
    val host = authority.substringBefore(':').lowercase()
    if (host.isBlank()) return false
    val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" || host == "[::1]" || host == "::1"
    if (trimmed.startsWith("ws://")) return isLoopback
    if (isLoopback) return true
    val labels = host.split('.')
    return labels.size >= 2 &&
        labels.all { it.isNotEmpty() } &&
        labels.last().length >= 2
}
