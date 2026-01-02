package org.nostr.nostrord.ui.components.chat

import androidx.compose.ui.Modifier

/**
 * Returns a Modifier that detects right-click (secondary button) and triggers the callback.
 *
 * Platform behavior:
 * - Desktop (JVM): Detects right-click via PointerEvent.button
 * - Mobile (Android/iOS): No-op, context menu accessed via "More" button
 * - Web (JS/WasmJS): Detects right-click via contextmenu event
 *
 * This modifier does NOT interfere with text selection as it only responds to
 * secondary button presses, not primary button or touch gestures.
 */
expect fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier
