package org.nostr.nostrord.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun WindowDraggableArea(
    modifier: Modifier = Modifier,
    onDoubleClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
)
