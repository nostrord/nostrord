package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Web (WasmJS) implementation:
 * - Mobile (coarse pointer): single tap opens the context menu, matching Android.
 * - Desktop (mouse): secondary (right) click opens the context menu, matching JVM.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier = if (isCoarsePointer()) {
    Modifier.pointerInput(onRightClick) {
        detectTapGestures(onTap = { onRightClick() })
    }
} else {
    Modifier.pointerInput(onRightClick) {
        awaitEachGesture {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                onRightClick()
            }
        }
    }
}
