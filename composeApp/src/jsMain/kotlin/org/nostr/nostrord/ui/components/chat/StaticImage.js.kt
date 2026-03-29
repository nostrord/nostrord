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
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
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
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.getImageUrl

/**
 * Process-level LRU cache for decoded static image bitmaps.
 * JS is single-threaded so no synchronization is needed.
 */
private val staticImageCache = LruCache<String, ImageBitmap>(30)

/**
 * Semaphore limiting concurrent createImageBitmap decode pipelines to 3.
 * Prevents the JS event loop from being starved when many images appear at once.
 */
private val fetchPermits = Semaphore(3)

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

private fun jsFetchUrl(url: String): dynamic = js("fetch(url)")

private fun responseToBlob(response: dynamic): dynamic = js("response.blob()")

private fun createImageBitmapFromBlob(blob: dynamic): dynamic = js("createImageBitmap(blob)")

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

/**
 * Fetches [url] via the browser Fetch API, decodes the bytes off-thread using
 * createImageBitmap(), reads RGBA pixels from an OffscreenCanvas, and installs them
 * into a Skia Bitmap that is wrapped as a Compose ImageBitmap.
 *
 * This replaces the Coil/Skia on-main-thread decode path that caused UI freezes.
 */
private suspend fun loadStaticImageBitmap(url: String): ImageBitmap? {
    staticImageCache.get(url)?.let { return it }

    return fetchPermits.withPermit {
        // Check cache again after acquiring permit — another coroutine may have filled it.
        staticImageCache.get(url)?.let { return@withPermit it }

        try {
            // 1. Fetch the image as a Blob (browser handles network, non-blocking).
            val response = awaitDynamic(jsFetchUrl(url))
            if (response.ok != true) {
                    return@withPermit null
            }
            val blob = awaitDynamic(responseToBlob(response))
            yield()

            // 2. Decode off the JS thread via createImageBitmap (browser-native, non-blocking).
            val nativeBitmap = awaitDynamic(createImageBitmapFromBlob(blob))
            val width = (nativeBitmap.width as? Int) ?: 0
            val height = (nativeBitmap.height as? Int) ?: 0
            if (width <= 0 || height <= 0) {
                return@withPermit null
            }
            yield()

            // 3. Extract RGBA pixels via OffscreenCanvas — pure in-memory, very fast.
            val canvas = makOffscreenCanvas(width, height)
            val ctx = getContext2d(canvas)
            drawImage(ctx, nativeBitmap)
            val imageData = getImageData(ctx, width, height)
            // imageData.data is RGBA, 8 bpp per channel, unpremultiplied.
            val byteArray = imageDataToByteArray(imageData)
            yield()

            // 4. Install pixels into a Skia Bitmap and wrap as Compose ImageBitmap.
            // Canvas getImageData returns RGBA_8888 unpremultiplied — use matching ImageInfo.
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            val bitmap = SkiaBitmap()
            bitmap.allocPixels(info)
            bitmap.installPixels(info, byteArray, width * 4)
            val imageBitmap = bitmap.asComposeImageBitmap()

            staticImageCache.put(url, imageBitmap)
            imageBitmap
        } catch (e: Exception) {
            null
        }
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
    // null  = still loading
    // non-null empty sentinel (handled via separate flag) = load failed
    var bitmap by remember(url) { mutableStateOf(staticImageCache.get(url)) }
    var loadFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val result = loadStaticImageBitmap(url)
        if (result != null) {
            bitmap = result
        } else {
            loadFailed = true
            onError()
        }
    }

    val baseModifier = modifier.clickable(onClick = onClick)

    when {
        loadFailed -> {
            // Coil fallback so the user still sees something on decode failure.
            CoilFallbackImage(
                url = url,
                modifier = baseModifier,
                contentScale = contentScale,
                onError = onError
            )
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

@Composable
private fun CoilFallbackImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onError: () -> Unit
) {
    val context = LocalPlatformContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(getImageUrl(url))
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(Size(800, 600))
            .build(),
        contentDescription = "Image",
        contentScale = contentScale,
        modifier = modifier,
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) {
                onError()
            }
        }
    )
}
