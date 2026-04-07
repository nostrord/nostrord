package org.nostr.nostrord.ui.components.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts the first frame of a video URL using ffmpeg and caches the result.
 * Falls back gracefully if ffmpeg is not installed.
 */
object VideoThumbnailExtractor {
    private val cache = ConcurrentHashMap<String, ImageBitmap?>()
    private val ffmpegAvailable: Boolean by lazy {
        try {
            ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    suspend fun extractFirstFrame(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        if (!ffmpegAvailable) return null

        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder(
                    "ffmpeg",
                    "-i", url,
                    "-ss", "00:00:01",
                    "-vframes", "1",
                    "-f", "image2pipe",
                    "-vcodec", "png",
                    "-loglevel", "error",
                    "pipe:1"
                ).redirectErrorStream(false).start()

                val bytes = process.inputStream.readBytes()
                process.waitFor()

                if (bytes.size > 100) {
                    val bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    cache[url] = bitmap
                    bitmap
                } else {
                    cache[url] = null
                    null
                }
            } catch (_: Exception) {
                cache[url] = null
                null
            }
        }
    }
}
