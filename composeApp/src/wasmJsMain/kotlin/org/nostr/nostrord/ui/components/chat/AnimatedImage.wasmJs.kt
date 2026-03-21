package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.utils.getImageUrl

/** WasmJS: same limitations and rationale as the JS target. See AnimatedImage.js.kt. */
@Composable
actual fun AnimatedImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit
) {
    val context = LocalPlatformContext.current
    var loadError by remember(url) { mutableStateOf(false) }

    if (loadError) return

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(getImageUrl(url))
            .crossfade(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build(),
        contentDescription = "Animated GIF",
        contentScale = contentScale,
        modifier = modifier.clickable(onClick = onClick),
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) {
                println("[AnimatedImage] WasmJS load error for $url: ${state.result.throwable?.message}")
                loadError = true
            }
        }
    )
}
