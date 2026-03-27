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
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.nostr.nostrord.ui.theme.NostrordColors
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Process-level LRU cache for decoded animation frames.
 *
 * WHY this is needed:
 * Compose's `remember` state is scoped to a composition instance. When a LazyColumn item
 * scrolls off screen, Compose destroys that item's composition — including the decoded
 * frames stored in `remember`. When the item scrolls back into view, a new composition
 * instance starts from scratch, triggering a full re-download + re-decode.
 *
 * Storing frames here (outside of any composable) survives scrolling. The LRU eviction
 * (removeEldestEntry) bounds memory use to MAX_ENTRIES animated images at a time. For
 * typical chat GIFs (10–30 frames, ~300 KB/frame as ARGB ImageBitmap), 20 entries is
 * roughly 60–180 MB — reasonable for a desktop client.
 */
private object FrameCache {
    private const val MAX_ENTRIES = 20

    private val cache = object : LinkedHashMap<String, List<Pair<ImageBitmap, Int>>>(
        MAX_ENTRIES + 1, 0.75f, /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<Pair<ImageBitmap, Int>>>): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized fun get(url: String): List<Pair<ImageBitmap, Int>>? = cache[url]

    @Synchronized fun put(url: String, frames: List<Pair<ImageBitmap, Int>>) {
        cache[url] = frames
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
    onClick: () -> Unit
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
                val bytes = java.net.URL(url).openStream().use { it.readBytes() }
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
            // Error — ChatImage's caller will see nothing; the URL link fallback is in
            // ChatImage itself and handles the case where AnimatedImage renders nothing.
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
 * Decodes animated WebP frames using org.jetbrains.skia.Codec.
 *
 * WHY Skia (not ImageIO): Java's standard ImageIO has no WebP reader. Skia (bundled with
 * Compose Desktop via skiko) supports both static and animated WebP natively via Codec.
 *
 * The Skia Bitmap → ImageBitmap conversion uses a PNG encode/decode round-trip through
 * ImageIO because the direct Skia→Compose bridge API (`asComposeImageBitmap`) is not
 * consistently available across skiko versions. PNG round-trip is negligible overhead for
 * the small number of frames in a typical animated WebP.
 */
private fun decodeWebpFrames(bytes: ByteArray): List<Pair<ImageBitmap, Int>> {
    return try {
        val data = SkiaData.makeFromBytes(bytes)
        val codec = SkiaCodec.makeFromData(data)
        val info = codec.imageInfo

        val result = (0 until codec.frameCount).map { i ->
            val bitmap = SkiaBitmap()
            bitmap.allocPixels(info)
            codec.readPixels(bitmap, i)

            // Skia Bitmap → PNG bytes → BufferedImage → ImageBitmap
            // (BufferedImage.toComposeImageBitmap() is the stable Compose Desktop API)
            val skiaImage = SkiaImage.makeFromBitmap(bitmap)
            val pngBytes = skiaImage.encodeToData(EncodedImageFormat.PNG)!!.bytes
            val bufferedImage = ImageIO.read(ByteArrayInputStream(pngBytes))

            val delayMs = codec.getFrameInfo(i).duration.coerceAtLeast(50)

            bitmap.close()
            skiaImage.close()

            bufferedImage.toComposeImageBitmap() to delayMs
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

        // We need to composite frames correctly: GIF frames can be partial (delta frames)
        // and reference the previous frame. Rendering into a single canvas and copying
        // handles disposal methods properly.
        var canvas: BufferedImage? = null

        (0 until frameCount).map { i ->
            val frame: BufferedImage = reader.read(i)
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
