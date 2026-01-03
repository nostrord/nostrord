package org.nostr.nostrord.ui.components.avatars

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AvatarPlaceholder(
    displayName: String,
    pubkey: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Jdenticon(
            value = pubkey,
            size = size,
            modifier = Modifier.fillMaxSize()
        )
    }
}
