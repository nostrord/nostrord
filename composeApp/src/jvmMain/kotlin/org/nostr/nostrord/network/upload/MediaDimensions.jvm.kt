package org.nostr.nostrord.network.upload

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

// ImageIO reader exposes width/height from the header without decoding the full raster.
actual suspend fun decodeImageDimensions(bytes: ByteArray, mimeType: String?): Pair<Int, Int>? {
    if (bytes.isEmpty()) return null
    return withContext(Dispatchers.Default) {
        runCatching {
            ByteArrayInputStream(bytes).use { input ->
                val iis = ImageIO.createImageInputStream(input) ?: return@runCatching null
                iis.use {
                    val readers = ImageIO.getImageReaders(it)
                    if (!readers.hasNext()) return@runCatching null
                    val reader = readers.next()
                    try {
                        reader.input = it
                        val w = reader.getWidth(0)
                        val h = reader.getHeight(0)
                        if (w > 0 && h > 0) w to h else null
                    } finally {
                        reader.dispose()
                    }
                }
            }
        }.getOrNull()
    }
}
