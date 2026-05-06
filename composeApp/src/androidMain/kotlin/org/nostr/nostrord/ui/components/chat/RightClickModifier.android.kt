package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

actual fun rightClickContextMenuModifier(onRightClick: () -> Unit): Modifier =
    Modifier.pointerInput(onRightClick) {
        detectTapGestures(onLongPress = { onRightClick() })
    }
