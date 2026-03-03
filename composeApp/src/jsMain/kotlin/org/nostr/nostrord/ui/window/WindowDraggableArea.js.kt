package org.nostr.nostrord.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun WindowDraggableArea(
    modifier: Modifier,
    onDoubleClick: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    Box(modifier) { content() }
}
