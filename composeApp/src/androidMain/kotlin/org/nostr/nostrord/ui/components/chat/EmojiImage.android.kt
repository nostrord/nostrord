package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import org.nostr.nostrord.utils.getImageUrl

@Composable
actual fun EmojiImage(
    url: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onError: () -> Unit
) {
    val context = LocalPlatformContext.current
    val imageRequest = ImageRequest.Builder(context)
        .data(getImageUrl(url))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .size(Size(44, 44))
        .build()

    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onState = { state ->
            if (state is AsyncImagePainter.State.Error) {
                onError()
            }
        }
    )
}
