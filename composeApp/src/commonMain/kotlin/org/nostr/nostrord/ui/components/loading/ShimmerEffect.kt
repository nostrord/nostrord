package org.nostr.nostrord.ui.components.loading

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Creates a shimmer effect modifier for skeleton loading states.
 * The shimmer animates from left to right creating a loading effect.
 */
@Composable
fun Modifier.shimmerEffect(
    baseColor: Color = NostrordColors.Surface,
    highlightColor: Color = NostrordColors.SurfaceVariant
): Modifier {
    val shimmerColors = listOf(
        baseColor,
        highlightColor,
        baseColor
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnimation - 200f, 0f),
        end = Offset(translateAnimation, 0f)
    )

    return this.background(brush)
}

/**
 * Creates a shimmer brush for custom shapes.
 */
@Composable
fun shimmerBrush(
    baseColor: Color = NostrordColors.Surface,
    highlightColor: Color = NostrordColors.SurfaceVariant
): Brush {
    val shimmerColors = listOf(
        baseColor,
        highlightColor,
        baseColor
    )

    val transition = rememberInfiniteTransition(label = "shimmer_brush")
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_brush_translate"
    )

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnimation - 200f, 0f),
        end = Offset(translateAnimation, 0f)
    )
}
