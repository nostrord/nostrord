package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import org.nostr.nostrord.ui.util.generateColorFromString

@Composable
fun ProfileAvatar(
    imageUrl: String?,
    displayName: String,
    pubkey: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    // Memoize computed values to prevent recalculation on every recomposition
    val sizeInPx = remember(size) { (size.value * 2).toInt() }
    val placeholderColor = remember(pubkey) { generateColorFromString(pubkey) }

    if (imageUrl.isNullOrBlank()) {
        AvatarPlaceholder(displayName, pubkey, modifier.size(size))
    } else {
        Box(modifier = modifier) {
            var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

            // Show placeholder background while loading or on error
            if (imageState is AsyncImagePainter.State.Loading || imageState is AsyncImagePainter.State.Error) {
                AvatarPlaceholder(displayName, pubkey, Modifier.size(size))
            }

            if (imageState !is AsyncImagePainter.State.Error) {
                // Memoize image request to prevent rebuilding on every recomposition
                val imageRequest = remember(imageUrl, sizeInPx, context) {
                    ImageRequest.Builder(context)
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
                    modifier = Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(placeholderColor, CircleShape),
                    onState = { imageState = it }
                )
            }
        }
    }
}
