package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ScreenSize {
    Compact, // Mobile: < 912dp
    Medium, // Tablet: 912-1200dp
    Large, // Desktop: > 1200dp
}

/**
 * Use the smallest available dimension (analogous to Android's `sw600dp` resource qualifier)
 * to decide mobile-vs-desktop layouts. Width alone would mis-classify a phone in landscape
 * as desktop, because the phone is suddenly wider than 912dp but the user still wants a
 * mobile layout.
 *
 * If the container has unbounded height (e.g. inside a vertical scroll), falls back to
 * maxWidth so we don't divide by infinity.
 */
val BoxWithConstraintsScope.responsiveDimension: Dp
    get() = if (maxHeight == Dp.Infinity) maxWidth else minOf(maxWidth, maxHeight)

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
