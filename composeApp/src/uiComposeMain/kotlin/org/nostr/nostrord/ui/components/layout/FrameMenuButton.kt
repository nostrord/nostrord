package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * The frame drawer's hamburger button (the web .frame-menu-btn), used in every mobile
 * screen header. Uses the default [IconButton] touch target and ripple so its size and
 * click feedback are identical on every page; pass [modifier] only for positioning.
 */
@Composable
fun FrameMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = Icons.Default.Menu,
            contentDescription = "Open menu",
            tint = NostrordColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}
