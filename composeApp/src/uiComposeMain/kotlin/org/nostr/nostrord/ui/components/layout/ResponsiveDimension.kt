package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.ui.unit.Dp
import org.nostr.nostrord.isHandheldPlatform

/**
 * On handheld platforms (Android, iOS) the window IS the device, so use the smallest
 * dimension (sw600dp-style) — rotating shouldn't flip mobile to desktop layout. On desktop
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
