package org.nostr.nostrord.ui.window

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Frame
import java.awt.MouseInfo

val LocalAwtWindow = staticCompositionLocalOf<Frame?> { null }

@Composable
actual fun WindowDraggableArea(
    modifier: Modifier,
    onDoubleClick: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    val window = LocalAwtWindow.current
    if (window == null) {
        Box(modifier) { content() }
        return
    }

    var dragStartWindowX = 0
    var dragStartWindowY = 0
    var dragStartMouseX = 0
    var dragStartMouseY = 0

    val dragModifier = Modifier.pointerInput(window) {
        detectDragGestures(
            onDragStart = {
                val mouseLocation = MouseInfo.getPointerInfo().location
                dragStartMouseX = mouseLocation.x
                dragStartMouseY = mouseLocation.y
                dragStartWindowX = window.x
                dragStartWindowY = window.y
            },
            onDrag = { change, _ ->
                change.consume()
                val mouseLocation = MouseInfo.getPointerInfo().location
                val deltaX = mouseLocation.x - dragStartMouseX
                val deltaY = mouseLocation.y - dragStartMouseY
                window.setLocation(
                    dragStartWindowX + deltaX,
                    dragStartWindowY + deltaY
                )
            }
        )
    }

    val doubleTapModifier = if (onDoubleClick != null) {
        Modifier.pointerInput(onDoubleClick) {
            detectTapGestures(onDoubleTap = { onDoubleClick() })
        }
    } else {
        Modifier
    }

    Box(modifier.then(doubleTapModifier).then(dragModifier)) {
        content()
    }
}
