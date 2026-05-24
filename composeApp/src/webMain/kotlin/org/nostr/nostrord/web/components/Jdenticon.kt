package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.dom.svg.ReactSVG.path
import react.dom.svg.ReactSVG.svg
import web.cssom.ClassName

/**
 * Identicon fallback, a faithful port of the native `Jdenticon`: a vertically-symmetric 5×5
 * grid on a light background, with a deterministic HSL colour derived from the seed. Users are
 * seeded by pubkey and groups by group id (same as native), so the generated identity matches.
 * Rendered as an inline SVG (`viewBox 0 0 5 5`); the light background comes from `.avatar-iden`.
 */
fun ChildrenBuilder.identicon(seed: String) {
    val hash = hashAvatar(seed)
    val color = identiconColor(hash)
    val cells =
        buildString {
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    val i = row * 3 + col
                    val byteIndex = (i + 3) % hash.size
                    val bitIndex = i % 8
                    val on = ((hash[byteIndex].toInt() shr bitIndex) and 1) == 1
                    if (on) {
                        append("M$col ${row}h1v1h-1z")
                        // Mirror onto the right side for vertical symmetry (skip the centre column).
                        if (col < 2) append("M${4 - col} ${row}h1v1h-1z")
                    }
                }
            }
        }
    svg {
        className = ClassName("avatar-iden")
        viewBox = "0 0 5 5"
        path {
            d = cells
            fill = color
        }
    }
}

/** Matches the native `hashString`: a 16-byte digest from a rolling 31-multiplier hash. */
private fun hashAvatar(input: String): ByteArray {
    val result = ByteArray(16)
    var h = 0L
    for (c in input) h = 31L * h + c.code
    for (i in result.indices) {
        h = h * 31L + i
        result[i] = (h and 0xFF).toByte()
    }
    return result
}

/** Matches the native `generateColor`: a vibrant HSL colour from the first three hash bytes. */
private fun identiconColor(hash: ByteArray): String {
    val hue = (hash[0].toInt() and 0xFF) / 255.0 * 360.0
    val saturation = 0.5 + (hash[1].toInt() and 0x3F) / 127.0 * 0.3
    val lightness = 0.35 + (hash[2].toInt() and 0x3F) / 127.0 * 0.2
    return "hsl(${hue.toInt()}, ${(saturation * 100).toInt()}%, ${(lightness * 100).toInt()}%)"
}
