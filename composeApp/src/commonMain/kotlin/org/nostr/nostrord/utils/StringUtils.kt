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
