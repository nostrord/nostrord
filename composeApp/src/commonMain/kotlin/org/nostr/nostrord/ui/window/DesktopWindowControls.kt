package org.nostr.nostrord.ui.window

import androidx.compose.runtime.staticCompositionLocalOf

interface DesktopWindowControls {
    fun minimize()
    fun toggleMaximize()
    fun close()
    val isMaximized: Boolean
}

val LocalDesktopWindowControls = staticCompositionLocalOf<DesktopWindowControls?> { null }
