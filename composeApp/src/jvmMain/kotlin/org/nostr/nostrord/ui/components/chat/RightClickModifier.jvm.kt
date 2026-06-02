package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Desktop implementation: Detects right-click via secondary button press
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun rightClickContextMenuModifier(onRightClick: (Offset) -> Unit): Modifier = Modifier.pointerInput(Unit) {
    awaitEachGesture {
        val event = awaitPointerEvent()
        // Check if secondary (right) mouse button is pressed
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
            val position = event.changes.firstOrNull()?.position ?: Offset.Zero
            onRightClick(position)
        }
    }
}
