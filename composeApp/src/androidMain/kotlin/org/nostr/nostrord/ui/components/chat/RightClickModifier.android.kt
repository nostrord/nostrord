package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

actual fun rightClickContextMenuModifier(onRightClick: (Offset) -> Unit): Modifier = Modifier.pointerInput(onRightClick) {
    detectTapGestures(onTap = { offset -> onRightClick(offset) })
}
