package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/**
 * Identicon generator for Compose.
 *
 * Generates a unique, vertically symmetric visual identity based on a hash of the input string.
 * Uses a 5x5 grid with filled/empty squares.
 */
@Composable
fun Jdenticon(
    value: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val hash = remember(value) { hashString(value) }
    val foregroundColor = remember(hash) { generateColor(hash) }
    val pattern = remember(hash) { generatePattern(hash) }

    Canvas(modifier = modifier.size(size)) {
        val cellSize = this.size.width / 5f

        // Draw light background
        drawRect(
            color = Color(0xFFF0F0F0),
            topLeft = Offset.Zero,
            size = this.size
        )

        // Draw the 5x5 pattern with vertical symmetry
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                if (pattern[row * 3 + col]) {
                    // Draw on left side
                    drawRect(
                        color = foregroundColor,
                        topLeft = Offset(col * cellSize, row * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                    // Mirror on right side (except center column)
                    if (col < 2) {
                        drawRect(
                            color = foregroundColor,
                            topLeft = Offset((4 - col) * cellSize, row * cellSize),
                            size = Size(cellSize, cellSize)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Simple hash function for generating consistent values from a string.
 */
private fun hashString(input: String): ByteArray {
    val result = ByteArray(16)
    var h = 0L

    for (i in input.indices) {
        h = 31L * h + input[i].code
    }

    for (i in result.indices) {
        h = h * 31L + i
        result[i] = (h and 0xFF).toByte()
    }

    return result
}

/**
 * Generate a single color from hash bytes (vibrant colors).
 */
private fun generateColor(hash: ByteArray): Color {
    val hue = (hash[0].toInt() and 0xFF) / 255f * 360f
    val saturation = 0.5f + (hash[1].toInt() and 0x3F) / 127f * 0.3f
    val lightness = 0.35f + (hash[2].toInt() and 0x3F) / 127f * 0.2f

    return Color.hsl(hue, saturation, lightness)
}

/**
 * Generate a 5x3 pattern (15 bits) for the left half + center column.
 * The right side mirrors the left side for vertical symmetry.
 */
private fun generatePattern(hash: ByteArray): BooleanArray {
    val pattern = BooleanArray(15)

    for (i in 0 until 15) {
        // Use different bits from the hash to determine each cell
        val byteIndex = (i + 3) % hash.size
        val bitIndex = i % 8
        pattern[i] = ((hash[byteIndex].toInt() shr bitIndex) and 1) == 1
    }

    return pattern
}
