package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.Codec as SkiaCodec
import org.jetbrains.skia.Data as SkiaData
import org.jetbrains.skia.Image as SkiaImage
import org.nostr.nostrord.ui.theme.NostrordColors
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream

/** Max bytes allowed for a single downloaded image before we refuse to decode. */
private const val MAX_IMAGE_DOWNLOAD_BYTES = 15L * 1024 * 1024 // 15 MB

/** HTTP timeouts for animated image fetching. */
private const val CONNECT_TIMEOUT_MS = 5_000
private const val READ_TIMEOUT_MS = 10_000

/**
 * Process-level LRU cache for decoded animation frames, bounded by total byte usage.
 *
 * WHY this is needed:
 * Compose's `remember` state is scoped to a composition instance. When a LazyColumn item
 * scrolls off screen, Compose destroys that item's composition — including the decoded
 * frames stored in `remember`. Storing frames here survives scrolling.
 *
 * Unlike the old count-based cache, this tracks estimated ARGB byte usage per entry
 * (width × height × 4 × frameCount) and evicts LRU entries when total exceeds the cap.
 * This prevents OOM from a few large GIFs blowing past a fixed entry count.
 */
private object FrameCache {
    private const val MAX_BYTES = 150L * 1024 * 1024 // 150 MB cap

    private data class Entry(
        val frames: List<Pair<ImageBitmap, Int>>,
        val estimatedBytes: Long
    )

    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, /* accessOrder = */ true)
    private var currentBytes = 0L

    @Synchronized
    fun get(url: String): List<Pair<ImageBitmap, Int>>? = cache[url]?.frames

    @Synchronized
    fun put(url: String, frames: List<Pair<ImageBitmap, Int>>) {
        if (frames.isEmpty()) return
        val first = frames[0].first
        val estimatedBytes = first.width.toLong() * first.height * 4 * frames.size
        // Don't cache entries larger than half the budget
        if (estimatedBytes > MAX_BYTES / 2) return

        // Remove existing entry if replacing
        cache.remove(url)?.let { currentBytes -= it.estimatedBytes }

        // Evict LRU until we fit
        val iter = cache.entries.iterator()
        while (currentBytes + estimatedBytes > MAX_BYTES && iter.hasNext()) {
            val eldest = iter.next()
            currentBytes -= eldest.value.estimatedBytes
            iter.remove()
        }

        cache[url] = Entry(frames, estimatedBytes)
        currentBytes += estimatedBytes
    }
}

/**
 * JVM Desktop implementation: frame-by-frame animation using Java's built-in GIF decoder.
 *
 * WHY Coil cannot be used here:
 * Coil 3's `coil-gif` module only ships Android decoders (AnimatedImageDecoder / GifDecoder).
 * On JVM, Coil uses Java's ImageIO for decoding but only reads the first frame, producing
 * a static image. There is no first-party Coil animated GIF decoder for JVM Desktop.
 *
 * HOW this works:
 * 1. Decoded frames are looked up in FrameCache (process-level LRU) first. Cache hits
 *    restore frames instantly, with no loading state and no network request.
 * 2. On cache miss: GIF bytes are fetched on Dispatchers.IO and decoded per format:
 *    GIF → ImageIO (preserves disposal methods natively)
 *    WebP → Skia Codec (ImageIO has no WebP support in the standard JDK)
 * 3. Frame delay from GIF/WebP metadata is applied (floored at 50 ms per browser spec).
 * 4. A LaunchedEffect cycles currentFrame at each declared delay, driving recomposition.
 */
@Composable
actual fun AnimatedImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit
) {
    // Initialise directly from cache — composables that re-enter after scrolling away
    // will already have frames and skip the loading state entirely.
    var frames by remember(url) { mutableStateOf(FrameCache.get(url)) }
    var currentFrame by remember { mutableIntStateOf(0) }

    LaunchedEffect(url) {
        // Cache hit: frames were set in the remember initialiser — nothing to do.
        if (frames != null) return@LaunchedEffect

        val decoded: List<Pair<ImageBitmap, Int>> = withContext(Dispatchers.IO) {
            try {
                val bytes = fetchWithTimeout(url)
                    ?: return@withContext emptyList<Pair<ImageBitmap, Int>>()
                val lower = url.lowercase()
                when {
                    lower.contains(".webp") -> decodeWebpFrames(bytes)
                    else -> decodeGifFrames(bytes)
                }
            } catch (e: Exception) {
                println("[AnimatedImage/JVM] Failed to load $url: ${e.message}")
                emptyList()
            }
        }
        frames = decoded
        if (decoded.isNotEmpty()) FrameCache.put(url, decoded)
    }

    val baseModifier = modifier.clickable(onClick = onClick)

    when {
        frames == null -> {
            // Loading
            Box(modifier = baseModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = NostrordColors.Primary,
                    strokeWidth = 3.dp
                )
            }
        }
        frames!!.isEmpty() -> {
            // Error — notify parent so it can show a text link fallback
            LaunchedEffect(Unit) { onError() }
        }
        frames!!.size == 1 -> {
            // Single-frame GIF — render as a static image, no animation loop needed
            Image(
                bitmap = frames!![0].first,
                contentDescription = "Image",
                contentScale = contentScale,
                modifier = baseModifier
            )
        }
        else -> {
            // Multi-frame animated GIF
            val frameList = frames!!
            LaunchedEffect(frameList) {
                while (isActive) {
                    val delayMs = frameList[currentFrame].second.toLong()
                    delay(delayMs)
                    currentFrame = (currentFrame + 1) % frameList.size
                }
            }
            Image(
                bitmap = frameList[currentFrame].first,
                contentDescription = "Animated GIF",
                contentScale = contentScale,
                modifier = baseModifier
            )
        }
    }
}

/**
 * Fetches image bytes from a URL with connect/read timeouts and a size cap.
 * Returns null if the fetch fails or the response is too large.
 */
private fun fetchWithTimeout(url: String): ByteArray? {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Nostrord/1.0")

        if (conn.responseCode !in 200..299) return null

        // Reject responses larger than our budget before reading them fully
        val contentLength = conn.contentLengthLong
        if (contentLength > MAX_IMAGE_DOWNLOAD_BYTES) return null

        conn.inputStream.use { stream ->
            val buffer = java.io.ByteArrayOutputStream(
                if (contentLength > 0) contentLength.toInt() else 65536
            )
            val chunk = ByteArray(8192)
            var totalRead = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n == -1) break
                totalRead += n
                if (totalRead > MAX_IMAGE_DOWNLOAD_BYTES) return null
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        }
    } catch (_: Exception) {
        null
    } finally {
        conn.disconnect()
    }
}

/**
 * Decodes animated WebP frames using org.jetbrains.skia.Codec.
 *
 * WHY Skia (not ImageIO): Java's standard ImageIO has no WebP reader. Skia (bundled with
 * Compose Desktop via skiko) supports both static and animated WebP natively via Codec.
 *
 * Uses Skia Bitmap → toComposeImageBitmap() directly, avoiding the previous PNG
 * encode/decode round-trip which wasted ~8x memory per frame.
 */
private fun decodeWebpFrames(bytes: ByteArray): List<Pair<ImageBitmap, Int>> {
    return try {
        val data = SkiaData.makeFromBytes(bytes)
        val codec = SkiaCodec.makeFromData(data)
        val info = codec.imageInfo

        // Reject absurdly large images (e.g. 16000×16000) before allocating frames
        if (info.width.toLong() * info.height > 8_000_000) { // ~8 megapixels max
            codec.close(); data.close()
            return emptyList()
        }
        // Reject video-length WebPs to prevent hundreds of MB of frame allocations
        if (codec.frameCount > 300) {
            codec.close(); data.close()
            return emptyList()
        }

        val result = (0 until codec.frameCount).map { i ->
            val bitmap = SkiaBitmap()
            bitmap.allocPixels(info)
            codec.readPixels(bitmap, i)

            val delayMs = codec.getFrameInfo(i).duration.coerceAtLeast(50)
            // SkiaBitmap → SkiaImage → Compose ImageBitmap (no PNG round-trip)
            val skiaImage = SkiaImage.makeFromBitmap(bitmap)
            val composeBitmap = skiaImage.toComposeImageBitmap()
            skiaImage.close()
            bitmap.close()

            composeBitmap to delayMs
        }

        codec.close()
        data.close()
        result
    } catch (e: Exception) {
        println("[WebP/JVM] Decode error: ${e.message}")
        emptyList()
    }
}

/**
 * Decodes all frames of a GIF from its raw bytes using javax.imageio.
 *
 * Returns a list of (ImageBitmap, delayMs) pairs.
 * Frame delays are read from the GIF GraphicControlExtension metadata node.
 * An empty list signals decode failure.
 */
private fun decodeGifFrames(bytes: ByteArray): List<Pair<ImageBitmap, Int>> {
    val readers = ImageIO.getImageReadersByFormatName("gif")
    if (!readers.hasNext()) {
        println("[GIF/JVM] No GIF ImageReader available in this JVM")
        return emptyList()
    }
    val reader = readers.next()
    return try {
        val iis = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        reader.input = iis

        val frameCount = reader.getNumImages(true)
        if (frameCount <= 0) return emptyList()
        // Reject video-length GIFs to prevent hundreds of MB of frame allocations
        if (frameCount > 300) return emptyList()

        // Read first frame to check dimensions — reject absurdly large GIFs
        val firstFrame = reader.read(0)
        if (firstFrame.width.toLong() * firstFrame.height > 8_000_000) {
            return emptyList()
        }

        // We need to composite frames correctly: GIF frames can be partial (delta frames)
        // and reference the previous frame. Rendering into a single canvas and copying
        // handles disposal methods properly.
        var canvas: BufferedImage? = null

        (0 until frameCount).map { i ->
            val frame: BufferedImage = if (i == 0) firstFrame else reader.read(i)
            val meta = reader.getImageMetadata(i)

            // Parse frame delay from GIF GraphicControlExtension (in centiseconds → ms)
            val delayMs = runCatching {
                val tree = meta.getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
                val gce = tree.getElementsByTagName("GraphicControlExtension").item(0)
                    as? IIOMetadataNode
                val cs = gce?.getAttribute("delayTime")?.toIntOrNull() ?: 10
                // GIF delay is in 1/100 s; multiply by 10 to get ms; floor at 50 ms
                (cs * 10).coerceAtLeast(50)
            }.getOrDefault(100)

            // Composite onto canvas so partial (delta) frames render correctly
            if (canvas == null) {
                canvas = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            }
            val g = canvas!!.createGraphics()
            g.drawImage(frame, 0, 0, null)
            g.dispose()

            canvas!!.toComposeImageBitmap() to delayMs
        }
    } catch (e: Exception) {
        println("[GIF/JVM] Decode error: ${e.message}")
        emptyList()
    } finally {
        reader.dispose()
    }
}
