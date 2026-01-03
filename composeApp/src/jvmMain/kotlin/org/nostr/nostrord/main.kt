package org.nostr.nostrord

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nostrord",
        state = rememberWindowState(
            width = 1280.dp,
            height = 800.dp,
            position = WindowPosition.Aligned(Alignment.Center)
        ),
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                // Ctrl+Q (Windows/Linux) or Cmd+Q (macOS) - quit application
                if (event.key == Key.Q && (event.isCtrlPressed || event.isMetaPressed)) {
                    exitApplication()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    ) {
        App()
    }
}
