package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography

/**
 * iOS stub: shows a play button overlay. Clicking opens externally.
 * TODO: AVPlayerViewController integration.
 */
@Composable
actual fun PlatformVideoPlayer(
    url: String,
    thumbnailUrl: String?,
    aspectRatio: Float,
    onFallbackClick: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable(onClick = onFallbackClick),
        contentAlignment = Alignment.Center
    ) {
        // Play button overlay
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(NostrordColors.Background.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "▶",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextPrimary
            )
        }
    }
}
