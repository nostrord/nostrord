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
import org.nostr.nostrord.ui.util.generateColorFromString

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

    // Generate a saturated, high-contrast placeholder color
    val baseColor = generateColorFromString(identifier)
    val enhancedColor = enhanceColorForSmallSize(baseColor, sizeCategory)

    // Edge color for subtle border (darker version of base color)
    val edgeColor = baseColor.copy(
        red = (baseColor.red * 0.7f).coerceIn(0f, 1f),
        green = (baseColor.green * 0.7f).coerceIn(0f, 1f),
        blue = (baseColor.blue * 0.7f).coerceIn(0f, 1f),
        alpha = when (sizeCategory) {
            AvatarSizeCategory.LARGE -> 0f // No border for large
            AvatarSizeCategory.MEDIUM -> 0.3f
            AvatarSizeCategory.SMALL -> 0.5f
            AvatarSizeCategory.TINY -> 0.6f
        }
    )

    // For tiny sizes, always use initial letter (photos are unrecognizable)
    if (sizeCategory == AvatarSizeCategory.TINY || imageUrl.isNullOrBlank()) {
        SmallAvatarPlaceholder(
            displayName = displayName,
            backgroundColor = enhancedColor,
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
                displayName = displayName,
                backgroundColor = enhancedColor,
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
 * Placeholder for small avatars with enhanced visibility.
 */
@Composable
private fun SmallAvatarPlaceholder(
    displayName: String,
    backgroundColor: Color,
    edgeColor: Color,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier
) {
    val initial = displayName.firstOrNull()?.uppercase() ?: "?"

    // Font size scales with avatar size, but with a minimum for readability
    val fontSize = (size.value * 0.45f).coerceIn(10f, 24f).sp

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (edgeColor.alpha > 0f) {
                    Modifier.border(1.dp, edgeColor, shape)
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Enhances color saturation and brightness for better recognition at small sizes.
 * Colors appear washed out when viewed at small sizes, so we boost them.
 */
private fun enhanceColorForSmallSize(color: Color, sizeCategory: AvatarSizeCategory): Color {
    val boostFactor = when (sizeCategory) {
        AvatarSizeCategory.LARGE -> 1.0f
        AvatarSizeCategory.MEDIUM -> 1.1f
        AvatarSizeCategory.SMALL -> 1.15f
        AvatarSizeCategory.TINY -> 1.2f
    }

    // Simple saturation/brightness boost by pushing colors away from gray
    val avg = (color.red + color.green + color.blue) / 3f
    return Color(
        red = (avg + (color.red - avg) * boostFactor).coerceIn(0f, 1f),
        green = (avg + (color.green - avg) * boostFactor).coerceIn(0f, 1f),
        blue = (avg + (color.blue - avg) * boostFactor).coerceIn(0f, 1f),
        alpha = color.alpha
    )
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
