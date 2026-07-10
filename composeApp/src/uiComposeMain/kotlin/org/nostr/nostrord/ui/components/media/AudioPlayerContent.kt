package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 *
 * The playback engine differs per platform (Android: MediaPlayer; Desktop: the same
 * kdroidfilter player the video uses, so it shares the system codec set; iOS: stub), so
 * this is the expect/actual boundary. All actuals render the shared [AudioPlayerChrome].
 */
@Composable
expect fun AudioPlayerContent(
    url: String,
    modifier: Modifier = Modifier,
)

/**
 * Shared visual: play/pause button, filename, progress bar and the position/duration row.
 * Takes a normalized [progress] (0..1) and pre-formatted [positionText]/[durationText] so each
 * platform can source them from its own engine (the desktop player's own text avoids the raw,
 * sometimes-bogus, duration a streamed file reports). [durationText] null hides the duration.
 */
@Composable
internal fun AudioPlayerChrome(
    isPlaying: Boolean,
    progress: Float,
    positionText: String,
    durationText: String?,
    fileName: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NostrordColors.Primary.copy(alpha = 0.2f))
                .clickable { onToggle() }
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isPlaying) "⏸" else "▶",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.Primary,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { clampedProgress },
                modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = NostrordColors.Primary,
                trackColor = NostrordColors.Primary.copy(alpha = 0.15f),
            )

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = positionText,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted,
                )
                if (durationText != null) {
                    Text(
                        text = durationText,
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextMuted,
                    )
                }
            }
        }
    }
}

/** A short display name for an audio URL: last path segment, query stripped, capped. */
internal fun audioFileName(url: String): String = url.substringAfterLast("/").substringBefore("?").take(40)

internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val secStr = if (seconds < 10) "0$seconds" else "$seconds"
    return "$minutes:$secStr"
}
