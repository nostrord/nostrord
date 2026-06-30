package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
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

/**
 * Size category for avatar optimization.
 * Different rendering strategies are applied based on target size.
 */
private enum class AvatarSizeCategory {
    /** 48dp+ - Full detail, standard rendering */
    LARGE,

    /** 32-47dp - Enhanced contrast, subtle edge definition */
    MEDIUM,

    /** 24-31dp - High contrast, stronger edge definition */
    SMALL,

    /** <24dp - Fallback to initial letter (photos become unrecognizable) */
    TINY,
}

private fun Dp.toSizeCategory(): AvatarSizeCategory = when {
    this >= 48.dp -> AvatarSizeCategory.LARGE
    this >= 32.dp -> AvatarSizeCategory.MEDIUM
    this >= 24.dp -> AvatarSizeCategory.SMALL
    else -> AvatarSizeCategory.TINY
}

/**
 * Optimized avatar component designed for small display sizes (16-64dp).
 *
 * Applies size-aware rendering optimizations:
 * - Requests higher resolution source (2-3x) for quality downscaling
 * - Uses FilterQuality.High for crisp anti-aliasing
 * - Adds subtle edge definition at smaller sizes
 * - Falls back to high-contrast initial letter for tiny sizes
 * - Boosts color saturation for better recognition at small sizes
 *
 * @param imageUrl URL of the avatar image (null shows placeholder)
 * @param identifier Unique identifier for generating consistent placeholder color (e.g., pubkey, groupId)
 * @param displayName Display name for generating initial letter
 * @param size Target display size
 * @param shape Shape of the avatar (CircleShape or RoundedCornerShape)
 * @param isGroup Group avatars fall back to the conic swirl gradient, users to the duotone
 * @param modifier Modifier for the container
 */
@Composable
fun OptimizedSmallAvatar(
    imageUrl: String?,
    identifier: String,
    displayName: String,
    size: Dp,
    shape: Shape = CircleShape,
    isGroup: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val sizeCategory = size.toSizeCategory()

    // Use a single canonical request size (128px) for all avatar sizes so Coil
    // shares one memory/disk cache entry per URL. 128px at 2x density covers up to
    // 64dp avatars with good quality, and Compose downscales at render time.
    val requestSizePx = 128

    // Edge color for subtle border at smaller sizes
    val edgeColor =
        Color.Black.copy(
            alpha =
            when (sizeCategory) {
                AvatarSizeCategory.LARGE -> 0f
                AvatarSizeCategory.MEDIUM -> 0.1f
                AvatarSizeCategory.SMALL -> 0.15f
                AvatarSizeCategory.TINY -> 0.2f
            },
        )

    // No picture → gradient fallback. Tiny sizes still load the real picture (web does
    // too, and the people/member avatar stacks would otherwise be all gradients).
    if (imageUrl.isNullOrBlank()) {
        SmallAvatarPlaceholder(
            identifier = identifier,
            displayName = displayName,
            isGroup = isGroup,
            edgeColor = edgeColor,
            size = size,
            shape = shape,
            modifier = modifier,
        )
        return
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Keyed on imageUrl (list slot reuse resets state) with a self-healing retry, so a transient
        // load failure doesn't latch this avatar to its placeholder for the rest of the session.
        val avatar = rememberAvatarImageState(imageUrl)

        val showPlaceholder =
            avatar.state is AsyncImagePainter.State.Loading ||
                avatar.state is AsyncImagePainter.State.Error
        val loaded = avatar.state is AsyncImagePainter.State.Success

        // Always render placeholder behind (for loading state and as fallback)
        if (showPlaceholder) {
            SmallAvatarPlaceholder(
                identifier = identifier,
                displayName = displayName,
                isGroup = isGroup,
                edgeColor = edgeColor,
                size = size,
                shape = shape,
            )
        }

        // Attempt to load image unless in error state (the retry resets Error -> Empty to re-load)
        if (avatar.state !is AsyncImagePainter.State.Error) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(imageUrl)
                    .crossfade(150) // Quick crossfade
                    .size(Size(requestSizePx, requestSizePx))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "$displayName avatar",
                contentScale = ContentScale.Crop,
                // High filter quality ensures crisp downscaling with proper anti-aliasing
                filterQuality = FilterQuality.High,
                modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    // White backdrop so a transparent avatar (PNG with alpha) shows on
                    // white instead of the surface colour bleeding through. Only once the
                    // picture has loaded; while loading it would cover the gradient
                    // placeholder with a solid white tile (matches web's avatar-photo-white).
                    .then(if (loaded) Modifier.background(Color.White) else Modifier)
                    .then(
                        // Add subtle edge definition for smaller sizes
                        if (edgeColor.alpha > 0f) {
                            Modifier.border(
                                width = 1.dp,
                                color = edgeColor,
                                shape = shape,
                            )
                        } else {
                            Modifier
                        },
                    ).then(
                        // Add micro-contrast enhancement via vignette for medium/small
                        if (sizeCategory == AvatarSizeCategory.MEDIUM ||
                            sizeCategory == AvatarSizeCategory.SMALL
                        ) {
                            Modifier.subtleVignette()
                        } else {
                            Modifier
                        },
                    ),
                onState = avatar.onState,
            )
        }
    }
}

/**
 * Placeholder for small avatars using the seeded gradient fallback.
 */
@Composable
private fun SmallAvatarPlaceholder(
    identifier: String,
    displayName: String,
    isGroup: Boolean,
    edgeColor: Color,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .size(size)
            .clip(shape)
            .then(
                if (edgeColor.alpha > 0f) {
                    Modifier.border(1.dp, edgeColor, shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            GroupGradientAvatar(seed = identifier, name = displayName, size = size)
        } else {
            UserGradientAvatar(seed = identifier, size = size)
        }
    }
}

/**
 * Adds a subtle vignette effect to enhance perceived contrast at edges.
 * This helps define the avatar boundary without a hard border.
 */
private fun Modifier.subtleVignette(): Modifier = this.drawWithContent {
    drawContent()
    // Draw radial gradient overlay - darker at edges
    drawRect(
        brush =
        Brush.radialGradient(
            colors =
            listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.08f),
            ),
            center = center,
            radius = size.minDimension / 1.5f,
        ),
    )
}

/**
 * Convenience composable for user avatars (always circular).
 */
@Composable
fun OptimizedUserAvatar(
    imageUrl: String?,
    pubkey: String,
    displayName: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    OptimizedSmallAvatar(
        imageUrl = imageUrl,
        identifier = pubkey,
        displayName = displayName ?: pubkey.take(8),
        size = size,
        shape = CircleShape,
        modifier = modifier,
    )
}
