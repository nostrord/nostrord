@file:OptIn(ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.getImageUrl

/**
 * Process-level LRU cache for decoded static image bitmaps.
 * WasmJS is single-threaded so no synchronization is needed.
 */
private val staticImageCache = LruCache<String, ImageBitmap>(30)

/**
 * Semaphore limiting concurrent createImageBitmap decode pipelines to 3.
 */
private val fetchPermits = Semaphore(3)

// ---- WasmJS interop via @JsFun ----

@JsFun("(url) => fetch(url)")
private external fun jsFetch(url: String): kotlin.js.Promise<JsAny>

@JsFun("(response) => response.ok")
private external fun responseOk(response: JsAny): Boolean

@JsFun("(response) => response.blob()")
private external fun responseToBlob(response: JsAny): kotlin.js.Promise<JsAny>

@JsFun("(blob) => createImageBitmap(blob)")
private external fun jsCreateImageBitmap(blob: JsAny): kotlin.js.Promise<JsAny>

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

/**
 * Fetches [url] via the browser Fetch API, decodes using createImageBitmap(), reads
 * RGBA pixels from an OffscreenCanvas, and installs them into a Skia Bitmap.
 *
 * This replaces the Coil/Skia on-main-thread decode path that caused UI freezes.
 */
private suspend fun loadStaticImageBitmap(url: String): ImageBitmap? {
    staticImageCache.get(url)?.let { return it }

    return fetchPermits.withPermit {
        staticImageCache.get(url)?.let { return@withPermit it }

        println("[StaticImage/WASM] loading $url")
        try {
            // 1. Fetch as Blob (browser handles network, non-blocking).
            val response: JsAny = jsFetch(url).await()
            if (!responseOk(response)) {
                println("[StaticImage/WASM] fetch HTTP error for $url")
                return@withPermit null
            }
            val blob: JsAny = responseToBlob(response).await()
            yield()

            // 2. Decode via createImageBitmap (browser-native, non-blocking).
            val nativeBitmap: JsAny = jsCreateImageBitmap(blob).await()
            val width = bitmapWidth(nativeBitmap)
            val height = bitmapHeight(nativeBitmap)
            if (width <= 0 || height <= 0) {
                println("[StaticImage/WASM] zero dimensions for $url")
                return@withPermit null
            }
            yield()

            // 3. Read RGBA pixels via OffscreenCanvas.
            val canvas = createOffscreenCanvas(width, height)
            val ctx = getContext2d(canvas)
            drawImageOnContext(ctx, nativeBitmap)
            val imageData = getImageData(ctx, width, height)
            val byteArray = imageDataToByteArray(imageData)
            yield()

            // 4. Install into Skia Bitmap (RGBA_8888 unpremultiplied matches getImageData output).
            val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            val bitmap = SkiaBitmap()
            bitmap.allocPixels(info)
            bitmap.installPixels(info, byteArray, width * 4)
            val imageBitmap = bitmap.asComposeImageBitmap()

            staticImageCache.put(url, imageBitmap)
            println("[StaticImage/WASM] decoded ${width}x${height} for $url")
            imageBitmap
        } catch (e: Exception) {
            println("[StaticImage/WASM] load error for $url: ${e::class.simpleName}: ${e.message}")
            null
        }
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
            CoilFallbackImage(
                url = url,
                modifier = baseModifier,
                contentScale = contentScale,
                onError = onError
            )
        }
        bitmap == null -> {
            Box(modifier = baseModifier, contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = NostrordColors.Primary,
                    strokeWidth = 3.dp
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
                println("[StaticImage/WASM] Coil fallback error for $url: ${state.result.throwable?.message}")
                onError()
            }
        }
    )
}
