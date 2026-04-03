package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
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
import coil3.size.Size
import org.nostr.nostrord.utils.getImageUrl

/**
 * Android implementation: delegates to Coil AsyncImage.
 * Tries the optimized (proxy) URL first; if that fails, retries with the original URL.
 */
@Composable
actual fun StaticImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit
) {
    val context = LocalPlatformContext.current
    val optimizedUrl = remember(url) { getImageUrl(url) }
    var useOriginal by remember(url) { mutableStateOf(false) }

    val dataUrl = if (useOriginal) url else optimizedUrl

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(dataUrl)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(Size.ORIGINAL)
            .build(),
        contentDescription = "Image",
        contentScale = contentScale,
        modifier = Modifier.fillMaxWidth().then(modifier).clickable(onClick = onClick),
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) {
                // If proxy URL failed and it differs from original, retry with original
                if (!useOriginal && optimizedUrl != url) {
                    useOriginal = true
                } else {
                    onError()
                }
            }
        }
    )
}
