package org.nostr.nostrord.ui.components.chat

import androidx.compose.ui.Modifier

/**
 * Android implementation: No-op, context menu is accessed via "More" button.
 * Touch devices don't have a secondary button.
 */
actual fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier {
    // No-op on Android - users access context menu via the "More" button
    return Modifier
}
