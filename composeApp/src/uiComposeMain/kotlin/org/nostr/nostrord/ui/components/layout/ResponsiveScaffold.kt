package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.isHandheldPlatform

enum class ScreenSize {
    Compact, // Mobile: < 912dp
    Medium, // Tablet: 912-1200dp
    Large, // Desktop: > 1200dp
}

/**
 * On handheld platforms (Android, iOS) the window IS the device, so use the smallest
 * dimension (sw600dp-style) — rotating shouldn't flip mobile↔desktop layout. On desktop
 * platforms the user resizes the window deliberately, so width is the right signal.
 *
 * If maxHeight is unbounded (e.g. inside a vertical scroll), fall back to maxWidth.
 */
val BoxWithConstraintsScope.responsiveDimension: Dp
    get() = when {
        maxHeight == Dp.Infinity -> maxWidth
        isHandheldPlatform -> minOf(maxWidth, maxHeight)
        else -> maxWidth
    }

@Composable
fun ResponsiveScaffold(
    compactBreakpoint: Dp = 912.dp,
    mediumBreakpoint: Dp = 840.dp,
    mobile: @Composable () -> Unit,
    desktop: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (responsiveDimension < compactBreakpoint) {
            mobile()
        } else {
            desktop()
        }
    }
}

@Composable
fun ResponsiveScaffold(
    compactBreakpoint: Dp = 912.dp,
    mediumBreakpoint: Dp = 840.dp,
    content: @Composable (screenSize: ScreenSize, gridColumns: Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dim = responsiveDimension
        val screenSize =
            when {
                dim < compactBreakpoint -> ScreenSize.Compact
                dim < mediumBreakpoint -> ScreenSize.Medium
                else -> ScreenSize.Large
            }
        val gridColumns =
            when (screenSize) {
                ScreenSize.Compact -> 1
                ScreenSize.Medium -> 2
                ScreenSize.Large -> 3
            }
        content(screenSize, gridColumns)
    }
}
