package org.nostr.nostrord.ui.components.chat

import androidx.compose.ui.Modifier

/**
 * JS implementation: No-op for now.
 * Could potentially hook into browser contextmenu event in the future.
 */
actual fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier {
    // No-op on JS - users access context menu via the "More" button
    // Browser's native right-click menu will appear instead
    return Modifier
}
