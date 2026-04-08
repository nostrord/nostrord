package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ScreenSize {
    Compact,  // Mobile: < 912dp
    Medium,   // Tablet: 912-1200dp
    Large     // Desktop: > 1200dp
}

@Composable
fun ResponsiveScaffold(
    compactBreakpoint: Dp = 912.dp,
    mediumBreakpoint: Dp = 840.dp,
    mobile: @Composable () -> Unit,
    desktop: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth < compactBreakpoint) {
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
    content: @Composable (screenSize: ScreenSize, gridColumns: Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenSize = when {
            maxWidth < compactBreakpoint -> ScreenSize.Compact
            maxWidth < mediumBreakpoint -> ScreenSize.Medium
            else -> ScreenSize.Large
        }
        val gridColumns = when (screenSize) {
            ScreenSize.Compact -> 1
            ScreenSize.Medium -> 2
            ScreenSize.Large -> 3
        }
        content(screenSize, gridColumns)
    }
}
