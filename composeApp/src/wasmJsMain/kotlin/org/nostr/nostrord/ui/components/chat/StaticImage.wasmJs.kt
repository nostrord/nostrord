@file:OptIn(ExperimentalWasmJsInterop::class)

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
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.await
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
 * Prevents OOM from accumulating large decoded bitmaps in Wasm heap.
 */
private val staticImageCache = ByteBoundedImageCache(80L * 1024 * 1024)

/** URLs that have already failed — avoids repeated network requests for broken images. */
private val failedUrls = LruCache<String, Boolean>(100)

/**
 * Semaphore limiting concurrent createImageBitmap decode pipelines to 5.
 */
private val fetchPermits = Semaphore(5)

// ---- WasmJS interop via @JsFun ----

@JsFun("(url, timeoutMs) => fetch(url, { signal: AbortSignal.timeout(timeoutMs) })")
private external fun jsFetchWithTimeout(url: String, timeoutMs: Int): kotlin.js.Promise<JsAny>

@JsFun("(response) => response.ok")
private external fun responseOk(response: JsAny): Boolean

@JsFun("(response) => response.blob()")
private external fun responseToBlob(response: JsAny): kotlin.js.Promise<JsAny>

@JsFun("(blob, maxW) => createImageBitmap(blob, { resizeWidth: maxW, resizeQuality: 'medium' })")
private external fun jsCreateImageBitmapCapped(blob: JsAny, maxW: Int): kotlin.js.Promise<JsAny>

@JsFun("(bitmap) => bitmap.width | 0")
private external fun bitmapWidth(bitmap: JsAny): Int

@JsFun("(bitmap) => bitmap.height | 0")
private external fun bitmapHeight(bitmap: JsAny): Int

@JsFun("(width, height) => new OffscreenCanvas(width, height)")
private external fun createOffscreenCanvas(width: Int, height: Int): JsAny

@JsFun("(canvas) => canvas.getContext('2d')")
private external fun getContext2d(canvas: JsAny): JsAny

@JsFun("(ctx, bitmap) => { ctx.drawImage(bitmap, 0, 0); }")
private external fun drawImageOnContext(ctx: JsAny, bitmap: JsAny)

@JsFun("(ctx, width, height) => ctx.getImageData(0, 0, width, height)")
private external fun getImageData(ctx: JsAny, width: Int, height: Int): JsAny

@JsFun("(imageData) => imageData.data.length | 0")
private external fun imageDataLength(imageData: JsAny): Int

@JsFun("(imageData, i) => imageData.data[i] | 0")
private external fun imageDataByte(imageData: JsAny, i: Int): Int

/**
 * Copies pixel bytes from a JS ImageData object into a Kotlin ByteArray.
 */
private fun imageDataToByteArray(imageData: JsAny): ByteArray {
    val len = imageDataLength(imageData)
    return ByteArray(len) { i -> imageDataByte(imageData, i).toByte() }
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
        val response: JsAny = jsFetchWithTimeout(fetchUrl, FETCH_TIMEOUT_MS).await()
        if (!responseOk(response)) return null
        val blob: JsAny = responseToBlob(response).await()
        yield()

        // Single-pass decode: resizeWidth caps the output, browser calculates
        // proportional height and never allocates the full-resolution buffer.
        val nativeBitmap: JsAny = jsCreateImageBitmapCapped(blob, MAX_DECODE_WIDTH).await()
        val width = bitmapWidth(nativeBitmap)
        val height = bitmapHeight(nativeBitmap)
        if (width <= 0 || height <= 0) return null
        yield()

        // Extract RGBA pixels via OffscreenCanvas.
        val canvas = createOffscreenCanvas(width, height)
        val ctx = getContext2d(canvas)
        drawImageOnContext(ctx, nativeBitmap)
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
 * WasmJS implementation of StaticImage: uses createImageBitmap() for off-thread decode.
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
