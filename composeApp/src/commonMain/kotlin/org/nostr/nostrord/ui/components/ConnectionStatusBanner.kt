package org.nostr.nostrord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Banner displayed when connection to relay is lost or reconnecting.
 * Shows status and provides a manual retry button.
 */
@Composable
fun ConnectionStatusBanner(
    connectionState: ConnectionManager.ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showBanner = connectionState is ConnectionManager.ConnectionState.Error ||
            connectionState is ConnectionManager.ConnectionState.Reconnecting ||
            connectionState is ConnectionManager.ConnectionState.Disconnected

    AnimatedVisibility(
        visible = showBanner,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        val (message, showRetryButton, isReconnecting) = when (connectionState) {
            is ConnectionManager.ConnectionState.Reconnecting -> Triple(
                "Reconnecting (${connectionState.attempt}/${connectionState.maxAttempts})...",
                false,
                true
            )
            is ConnectionManager.ConnectionState.Error -> Triple(
                connectionState.message,
                true,
                false
            )
            is ConnectionManager.ConnectionState.Disconnected -> Triple(
                "Disconnected from relay",
                true,
                false
            )
            else -> Triple("", false, false)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NostrordColors.Warning.copy(alpha = 0.15f))
                .clickable(enabled = showRetryButton) { onRetry() }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isReconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = NostrordColors.Warning,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = NostrordColors.Warning,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Text(
                text = message,
                style = NostrordTypography.Caption,
                color = NostrordColors.Warning
            )

            if (showRetryButton) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Retry",
                    tint = NostrordColors.Warning,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = "Tap to retry",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.Warning
                )
            }
        }
    }
}
