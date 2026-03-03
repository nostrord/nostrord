package org.nostr.nostrord.ui.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls
import org.nostr.nostrord.ui.window.WindowDraggableArea

@Composable
fun NavigationToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    val windowControls = LocalDesktopWindowControls.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(NostrordColors.BackgroundDark),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LEFT: Back/Forward buttons
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) NostrordColors.TextPrimary else NostrordColors.TextMuted
            )
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) NostrordColors.TextPrimary else NostrordColors.TextMuted
            )
        }

        // CENTER: Draggable area
        WindowDraggableArea(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onDoubleClick = { windowControls?.toggleMaximize() }
        ) {
            Box(Modifier.fillMaxSize())
        }

        // RIGHT: Window control buttons (desktop only)
        if (windowControls != null) {
            WindowControlButtons(windowControls)
        }
    }
}

@Composable
fun MinimalTitleBar(modifier: Modifier = Modifier) {
    val windowControls = LocalDesktopWindowControls.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(NostrordColors.BackgroundDark),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Draggable area fills all space
        WindowDraggableArea(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            onDoubleClick = { windowControls?.toggleMaximize() }
        ) {
            Box(Modifier.fillMaxSize())
        }

        // Window control buttons
        if (windowControls != null) {
            WindowControlButtons(windowControls)
        }
    }
}

@Composable
private fun WindowControlButtons(
    windowControls: org.nostr.nostrord.ui.window.DesktopWindowControls
) {
    // Minimize
    IconButton(onClick = { windowControls.minimize() }, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = MinimizeIcon,
            contentDescription = "Minimize",
            tint = NostrordColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }

    // Maximize / Restore
    IconButton(onClick = { windowControls.toggleMaximize() }, modifier = Modifier.size(36.dp)) {
        Icon(
            imageVector = if (windowControls.isMaximized) RestoreIcon else MaximizeIcon,
            contentDescription = if (windowControls.isMaximized) "Restore" else "Maximize",
            tint = NostrordColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }

    // Close (red on hover)
    val closeHoverInteraction = remember { MutableInteractionSource() }
    val isCloseHovered by closeHoverInteraction.collectIsHoveredAsState()
    IconButton(
        onClick = { windowControls.close() },
        modifier = Modifier.size(36.dp).hoverable(closeHoverInteraction)
    ) {
        Icon(
            imageVector = CloseIcon,
            contentDescription = "Close",
            tint = if (isCloseHovered) NostrordColors.Error else NostrordColors.TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

// --- Window control icons ---

private val MinimizeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Minimize",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(3f, 8f)
            lineTo(13f, 8f)
            lineTo(13f, 9f)
            lineTo(3f, 9f)
            close()
        }
    }.build()
}

private val MaximizeIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Maximize",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // Outer square
            moveTo(3f, 3f)
            lineTo(13f, 3f)
            lineTo(13f, 13f)
            lineTo(3f, 13f)
            close()
            // Inner cutout
            moveTo(4f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 12f)
            lineTo(12f, 4f)
            close()
        }
    }.build()
}

private val RestoreIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Restore",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        // Back window (top-right, partially hidden)
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(5f, 2f)
            lineTo(14f, 2f)
            lineTo(14f, 11f)
            lineTo(13f, 11f)
            lineTo(13f, 3f)
            lineTo(5f, 3f)
            close()
        }
        // Front window (bottom-left)
        path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
            moveTo(2f, 5f)
            lineTo(11f, 5f)
            lineTo(11f, 14f)
            lineTo(2f, 14f)
            close()
            moveTo(3f, 6f)
            lineTo(3f, 13f)
            lineTo(10f, 13f)
            lineTo(10f, 6f)
            close()
        }
    }.build()
}

private val CloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Close",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            // X shape using two rotated rectangles
            moveTo(3.3f, 2.6f)
            lineTo(8f, 7.3f)
            lineTo(12.7f, 2.6f)
            lineTo(13.4f, 3.3f)
            lineTo(8.7f, 8f)
            lineTo(13.4f, 12.7f)
            lineTo(12.7f, 13.4f)
            lineTo(8f, 8.7f)
            lineTo(3.3f, 13.4f)
            lineTo(2.6f, 12.7f)
            lineTo(7.3f, 8f)
            lineTo(2.6f, 3.3f)
            close()
        }
    }.build()
}
