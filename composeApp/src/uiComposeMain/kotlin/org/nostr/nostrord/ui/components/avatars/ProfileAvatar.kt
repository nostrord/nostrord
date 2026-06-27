package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    // Request at the real display density with retina headroom (2x). The old `size.value * 2`
    // ignored density, so on HiDPI screens (mobile is ~2.6-3x) a 40dp avatar's real ~110px was
    // served from an 80px decode and looked soft. roundToPx() uses the actual density, so it is
    // crisp everywhere and identical to the old value at 1x desktop (no needless over-fetch).
    val sizeInPx = remember(size, density) { with(density) { size.roundToPx() } * 2 }

    if (imageUrl.isNullOrBlank()) {
        AvatarPlaceholder(displayName, pubkey, modifier.size(size), size)
    } else {
        Box(modifier = modifier) {
            // Self-healing load (keyed on the URL): a transient failure no longer latches the avatar
            // to its placeholder for the rest of the session.
            val avatar = rememberAvatarImageState(imageUrl)

            // Show placeholder background while loading or on error
            if (avatar.state is AsyncImagePainter.State.Loading || avatar.state is AsyncImagePainter.State.Error) {
                AvatarPlaceholder(displayName, pubkey, Modifier.size(size), size)
            }

            if (avatar.state !is AsyncImagePainter.State.Error) {
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
                        .clip(CircleShape)
                        // White backdrop so a transparent avatar (PNG with alpha) shows on
                        // white instead of whatever is behind it.
                        .background(Color.White),
                    onState = avatar.onState,
                )
            }
        }
    }
}
