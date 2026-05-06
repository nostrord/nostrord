package org.nostr.nostrord.ui.components.chat

import androidx.compose.ui.Modifier

/**
 * Returns a Modifier that opens the context menu via the platform's secondary interaction.
 *
 * Platform behavior:
 * - Desktop (JVM): right-click via PointerEvent secondary button
 * - Android: long-press via detectTapGestures
 * - iOS: no-op (context menu accessed via "More" button)
 * - Web (JS/WasmJS): contextmenu event
 */
expect fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier
