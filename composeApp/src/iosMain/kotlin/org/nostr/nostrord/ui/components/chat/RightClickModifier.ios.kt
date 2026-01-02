package org.nostr.nostrord.ui.components.chat

import androidx.compose.ui.Modifier

/**
 * iOS implementation: No-op, context menu is accessed via "More" button.
 * Touch devices don't have a secondary button.
 */
actual fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier {
    // No-op on iOS - users access context menu via the "More" button
    return Modifier
}
