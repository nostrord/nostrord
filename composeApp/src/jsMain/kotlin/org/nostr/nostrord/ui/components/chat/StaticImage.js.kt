package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.ByteBoundedImageCache
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.getImageUrl

/**
 * Process-level LRU cache for decoded static image bitmaps, bounded at 80 MB.
 * Prevents OOM from accumulating large decoded bitmaps in JS heap.
 */
private val staticImageCache = ByteBoundedImageCache(80L * 1024 * 1024)

/** URLs that have already failed — avoids repeated network requests for broken images. */
private val failedUrls = LruCache<String, Boolean>(100)

/**
 * Semaphore limiting concurrent createImageBitmap decode pipelines to 5.
 * Prevents the JS event loop from being starved when many images appear at once,
 * while allowing enough concurrency for smooth chat scrolling.
 */
private val fetchPermits = Semaphore(5)

// ---- JS interop via js() calls ----

/**
 * Wraps a JS Promise<dynamic> in a Kotlin suspend function via suspendCoroutine.
 */
private suspend fun awaitDynamic(promise: dynamic): dynamic = suspendCoroutine { cont ->
    promise.then(
        { value: dynamic -> cont.resume(value); null },
        { err: dynamic -> cont.resumeWithException(Exception(err.toString())); null }
    )
}

private fun jsFetchWithTimeout(url: String, timeoutMs: Int): dynamic =
    js("fetch(url, { signal: AbortSignal.timeout(timeoutMs) })")

private fun responseToBlob(response: dynamic): dynamic = js("response.blob()")

private fun createImageBitmapCapped(blob: dynamic, maxW: Int): dynamic =
    js("createImageBitmap(blob, { resizeWidth: maxW, resizeQuality: 'medium' })")

private fun makOffscreenCanvas(w: Int, h: Int): dynamic = js("new OffscreenCanvas(w, h)")

private fun getContext2d(canvas: dynamic): dynamic = js("canvas.getContext('2d')")

private fun drawImage(ctx: dynamic, bitmap: dynamic): Unit =
    js("ctx.drawImage(bitmap, 0, 0)")

private fun getImageData(ctx: dynamic, w: Int, h: Int): dynamic =
    js("ctx.getImageData(0, 0, w, h)")

/**
 * Copies a JS Uint8ClampedArray (imageData.data) into a Kotlin ByteArray.
 */
private fun imageDataToByteArray(imageData: dynamic): ByteArray {
    val data = imageData.data
    val len = (data.length as Int)
    val result = ByteArray(len)
    for (i in 0 until len) {
        result[i] = (data[i] as Int).toByte()
    }
    return result
}

/** Max pixel width for decoded images (2x of 400dp chat image max). */
private const val MAX_DECODE_WIDTH = 800

/** Timeout for image fetch requests in milliseconds. */
private const val FETCH_TIMEOUT_MS = 5_000

/**
 * Fetches and decodes an image into a Compose ImageBitmap.
 *
 * 1. Tries the optimized URL (proxy/CDN-resized via [getImageUrl]) first.
 * 2. If that fails (proxy blocked, 404), falls back to the original URL.
 * 3. Uses a single-pass capped decode: createImageBitmap(blob, {resizeWidth})
 *    lets the browser resize during decode without ever allocating the
 *    full-resolution pixel buffer.
 */
private suspend fun loadStaticImageBitmap(url: String): ImageBitmap? {
    staticImageCache.get(url)?.let { return it }
    if (failedUrls.get(url) == true) return null

    return fetchPermits.withPermit {
        staticImageCache.get(url)?.let { return@withPermit it }
        if (failedUrls.get(url) == true) return@withPermit null

        // Try optimized URL first, fall back to original on failure
        val optimizedUrl = getImageUrl(url)
        val result = fetchAndDecode(optimizedUrl)
            ?: if (optimizedUrl != url) fetchAndDecode(url) else null

        if (result != null) {
            staticImageCache.put(url, result)
        } else {
            failedUrls.put(url, true)
        }
        result
    }
}

/**
 * Fetches [fetchUrl], decodes with capped width via createImageBitmap,
 * and converts to a Compose ImageBitmap. Returns null on any failure.
 */
private suspend fun fetchAndDecode(fetchUrl: String): ImageBitmap? {
    return try {
        val response = awaitDynamic(jsFetchWithTimeout(fetchUrl, FETCH_TIMEOUT_MS))
        if (response.ok != true) return null
        val blob = awaitDynamic(responseToBlob(response))
        yield()

        // Single-pass decode: resizeWidth caps the output, browser calculates
        // proportional height and never allocates the full-resolution buffer.
        val nativeBitmap = awaitDynamic(createImageBitmapCapped(blob, MAX_DECODE_WIDTH))
        val width = (nativeBitmap.width as? Int) ?: 0
        val height = (nativeBitmap.height as? Int) ?: 0
        if (width <= 0 || height <= 0) return null
        yield()

        // Extract RGBA pixels via OffscreenCanvas.
        val canvas = makOffscreenCanvas(width, height)
        val ctx = getContext2d(canvas)
        drawImage(ctx, nativeBitmap)
        val imageData = getImageData(ctx, width, height)
        val byteArray = imageDataToByteArray(imageData)
        yield()

        // Install into Skia Bitmap.
        val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val bitmap = SkiaBitmap()
        bitmap.allocPixels(info)
        bitmap.installPixels(info, byteArray, width * 4)
        bitmap.asComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/**
 * JS implementation of StaticImage: uses createImageBitmap() for off-thread decode to
 * avoid freezing Skia's main-thread decode pipeline.
 */
@Composable
actual fun StaticImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit
) {
    val alreadyFailed = failedUrls.get(url) == true
    var bitmap by remember(url) { mutableStateOf(staticImageCache.get(url)) }
    var loadFailed by remember(url) { mutableStateOf(alreadyFailed) }

    // Fire onError as a side-effect, not during composition
    LaunchedEffect(loadFailed) {
        if (loadFailed) onError()
    }

    LaunchedEffect(url) {
        if (bitmap != null || loadFailed) return@LaunchedEffect
        val result = loadStaticImageBitmap(url)
        if (result != null) {
            bitmap = result
        } else {
            loadFailed = true
        }
    }

    val baseModifier = modifier.clickable(onClick = onClick)

    when {
        loadFailed -> {
            // Nothing to render — onError triggers ChatImage to show text link
        }
        bitmap == null -> {
            Box(
                modifier = baseModifier
                    .widthIn(min = 200.dp)
                    .heightIn(min = 100.dp)
                    .background(NostrordColors.Surface),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = NostrordColors.TextMuted,
                    strokeWidth = 2.5.dp
                )
            }
        }
        else -> {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Image",
                contentScale = contentScale,
                modifier = baseModifier
            )
        }
    }
}
