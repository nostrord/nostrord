package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    TINY
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
 * @param modifier Modifier for the container
 */
@Composable
fun OptimizedSmallAvatar(
    imageUrl: String?,
    identifier: String,
    displayName: String,
    size: Dp,
    shape: Shape = CircleShape,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val sizeCategory = size.toSizeCategory()

    // Calculate request size: higher multiplier for smaller targets
    // This gives Coil more pixels to work with during downscaling
    val requestMultiplier = when (sizeCategory) {
        AvatarSizeCategory.LARGE -> 2
        AvatarSizeCategory.MEDIUM -> 3
        AvatarSizeCategory.SMALL -> 3
        AvatarSizeCategory.TINY -> 2 // Will use fallback anyway
    }
    val requestSizePx = with(density) { (size * requestMultiplier).roundToPx() }

    // Edge color for subtle border at smaller sizes
    val edgeColor = Color.Black.copy(
        alpha = when (sizeCategory) {
            AvatarSizeCategory.LARGE -> 0f
            AvatarSizeCategory.MEDIUM -> 0.1f
            AvatarSizeCategory.SMALL -> 0.15f
            AvatarSizeCategory.TINY -> 0.2f
        }
    )

    // For tiny sizes or no image, use Jdenticon
    if (sizeCategory == AvatarSizeCategory.TINY || imageUrl.isNullOrBlank()) {
        SmallAvatarPlaceholder(
            identifier = identifier,
            edgeColor = edgeColor,
            size = size,
            shape = shape,
            modifier = modifier
        )
        return
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

        val showPlaceholder = imageState is AsyncImagePainter.State.Loading ||
            imageState is AsyncImagePainter.State.Error

        // Always render placeholder behind (for loading state and as fallback)
        if (showPlaceholder) {
            SmallAvatarPlaceholder(
                identifier = identifier,
                edgeColor = edgeColor,
                size = size,
                shape = shape
            )
        }

        // Attempt to load image unless in error state
        if (imageState !is AsyncImagePainter.State.Error) {
            AsyncImage(
                model = ImageRequest.Builder(context)
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
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .then(
                        // Add subtle edge definition for smaller sizes
                        if (edgeColor.alpha > 0f) {
                            Modifier.border(
                                width = 1.dp,
                                color = edgeColor,
                                shape = shape
                            )
                        } else Modifier
                    )
                    .then(
                        // Add micro-contrast enhancement via vignette for medium/small
                        if (sizeCategory == AvatarSizeCategory.MEDIUM ||
                            sizeCategory == AvatarSizeCategory.SMALL) {
                            Modifier.subtleVignette()
                        } else Modifier
                    ),
                onState = { imageState = it }
            )
        }
    }
}

/**
 * Placeholder for small avatars using Jdenticon.
 */
@Composable
private fun SmallAvatarPlaceholder(
    identifier: String,
    edgeColor: Color,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (edgeColor.alpha > 0f) {
                    Modifier.border(1.dp, edgeColor, shape)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Jdenticon(
            value = identifier,
            size = size
        )
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
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.08f)
            ),
            center = center,
            radius = size.minDimension / 1.5f
        )
    )
}

/**
 * Convenience composable for server/group icons in the ServerRail.
 * Uses RoundedCornerShape with animated corners.
 */
@Composable
fun OptimizedServerIcon(
    imageUrl: String?,
    groupId: String,
    groupName: String,
    size: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier
) {
    OptimizedSmallAvatar(
        imageUrl = imageUrl,
        identifier = groupId,
        displayName = groupName,
        size = size,
        shape = RoundedCornerShape(cornerRadius),
        modifier = modifier
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
    modifier: Modifier = Modifier
) {
    OptimizedSmallAvatar(
        imageUrl = imageUrl,
        identifier = pubkey,
        displayName = displayName ?: pubkey.take(8),
        size = size,
        shape = CircleShape,
        modifier = modifier
    )
}
