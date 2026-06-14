package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size

@Composable
fun ProfileAvatar(
    imageUrl: String?,
    displayName: String,
    pubkey: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    // Request at the real display density with retina headroom and a high floor, so the avatar
    // is as sharp as the web <img> (which downscales from the full-resolution source). The old
    // `size.value * 2` ignored density and under-resolved on HiDPI, softening the picture; Coil
    // then downsampled to that small size, which read as a worse/"zoomed" render than web.
    val sizeInPx = remember(size, density) { maxOf(with(density) { size.roundToPx() } * 2, 256) }

    if (imageUrl.isNullOrBlank()) {
        AvatarPlaceholder(displayName, pubkey, modifier.size(size), size)
    } else {
        Box(modifier = modifier) {
            var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

            // Show placeholder background while loading or on error
            if (imageState is AsyncImagePainter.State.Loading || imageState is AsyncImagePainter.State.Error) {
                AvatarPlaceholder(displayName, pubkey, Modifier.size(size), size)
            }

            if (imageState !is AsyncImagePainter.State.Error) {
                // Memoize image request to prevent rebuilding on every recomposition
                val imageRequest =
                    remember(imageUrl, sizeInPx, context) {
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .size(Size(sizeInPx, sizeInPx))
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build()
                    }

                AsyncImage(
                    model = imageRequest,
                    contentDescription = "$displayName's avatar",
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier =
                    Modifier
                        .size(size)
                        .clip(CircleShape),
                    onState = { imageState = it },
                )
            }
        }
    }
}
