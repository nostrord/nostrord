package org.nostr.nostrord.ui.components.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * Returns a Modifier that opens the context menu via the platform's secondary interaction.
 *
 * [onRightClick] receives the local click position (px, relative to the modified
 * element's top-left) so the menu can open at the cursor rather than a fixed corner.
 *
 * Platform behavior:
 * - Desktop (JVM): right-click via PointerEvent secondary button
 * - Android: single tap via detectTapGestures
 * - iOS: no-op (context menu accessed via "More" button)
 * - Web (JS/WasmJS): contextmenu event
 */
expect fun rightClickContextMenuModifier(onRightClick: (Offset) -> Unit): Modifier
