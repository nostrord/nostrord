package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
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
 * JVM Desktop implementation: delegates to Coil AsyncImage.
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
        modifier = modifier.clickable(onClick = onClick),
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) {
                onError()
            }
        }
    )
}
