package org.nostr.nostrord

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.nostr.nostrord.ui.window.DesktopWindowControls
import org.nostr.nostrord.ui.window.LocalAwtWindow
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls

fun main() = application {
    val windowState = rememberWindowState(
        width = 1280.dp,
        height = 800.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Nostrord",
        state = windowState,
        undecorated = true,
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
        val controls = remember(windowState) {
            object : DesktopWindowControls {
                override fun minimize() { windowState.isMinimized = true }
                override fun toggleMaximize() {
                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                        WindowPlacement.Floating else WindowPlacement.Maximized
                }
                override fun close() { exitApplication() }
                override val isMaximized: Boolean
                    get() = windowState.placement == WindowPlacement.Maximized
            }
        }

        CompositionLocalProvider(
            LocalDesktopWindowControls provides controls,
            LocalAwtWindow provides window
        ) {
            App()
        }
    }
}
