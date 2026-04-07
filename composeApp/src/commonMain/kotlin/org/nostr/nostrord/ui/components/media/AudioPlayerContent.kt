package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography

/**
 * Inline audio player with play/pause, progress bar, and duration.
 * Uses platform-specific [AudioPlayer] for actual playback.
 */
@Composable
fun AudioPlayerContent(
    url: String,
    player: AudioPlayer,
    modifier: Modifier = Modifier
) {
    val isPlaying by player.isPlaying.collectAsState()
    val currentMs by player.currentPositionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()

    val progress = if (durationMs > 0) currentMs.toFloat() / durationMs.toFloat() else 0f
    val fileName = url.substringAfterLast("/").substringBefore("?").take(40)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play / Pause button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NostrordColors.Primary.copy(alpha = 0.2f))
                .clickable {
                    if (isPlaying) player.pause() else player.play(url)
                }
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPlaying) "⏸" else "▶",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.Primary
            )
        }

        Spacer(Modifier.width(12.dp))

        // Info + progress
        Column(modifier = Modifier.weight(1f)) {
            // File name
            Text(
                text = fileName,
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(4.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = NostrordColors.Primary,
                trackColor = NostrordColors.Primary.copy(alpha = 0.15f),
            )

            Spacer(Modifier.height(2.dp))

            // Duration text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatDuration(currentMs),
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted
                )
                if (durationMs > 0) {
                    Text(
                        text = formatDuration(durationMs),
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextMuted
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val secStr = if (seconds < 10) "0$seconds" else "$seconds"
    return "$minutes:$secStr"
}
