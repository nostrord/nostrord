package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

/**
 * iOS implementation: single tap opens the context menu at the tap position
 * (same as Android). Touch devices have no secondary button, and there is no
 * longer a hover "More" button, so tap is the entry point.
 */
actual fun rightClickContextMenuModifier(onRightClick: (Offset) -> Unit): Modifier = Modifier.pointerInput(onRightClick) {
    detectTapGestures(onTap = { offset -> onRightClick(offset) })
}
