package org.nostr.nostrord.ui.window

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Wires the side mouse buttons (X1 = back, X2 = forward) to navigation. On Linux the
 * button mapping can be unreliable (a known Compose-desktop quirk), so Alt+Left/Right is
 * the dependable shortcut there.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.onBackForwardMouseButtons(
    onBack: () -> Unit,
    onForward: () -> Unit,
): Modifier = onPointerEvent(PointerEventType.Press) { event ->
    when (event.button) {
        PointerButton.Back -> onBack()
        PointerButton.Forward -> onForward()
        else -> {}
    }
}
