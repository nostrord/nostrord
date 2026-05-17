package org.nostr.nostrord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

@Composable
fun ConnectionStatusBanner(
    connectionState: ConnectionManager.ConnectionState,
    onRetry: () -> Unit,
    onManageRelay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible =
        connectionState is ConnectionManager.ConnectionState.Error ||
            connectionState is ConnectionManager.ConnectionState.Reconnecting

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.sm)
                .clip(RoundedCornerShape(8.dp))
                .background(NostrordColors.Warning.copy(alpha = 0.12f))
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (connectionState) {
                is ConnectionManager.ConnectionState.Reconnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = NostrordColors.Warning,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Reconnecting (${connectionState.attempt}/${connectionState.maxAttempts})...",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.Warning,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = NostrordColors.Warning,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Retry now",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.Warning,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                is ConnectionManager.ConnectionState.Error -> {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = NostrordColors.Warning,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Unable to connect",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.Warning,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            tint = NostrordColors.Warning,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Retry",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.Warning,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onManageRelay,
                        colors =
                        ButtonDefaults.buttonColors(
                            containerColor = NostrordColors.Warning.copy(alpha = 0.2f),
                            contentColor = NostrordColors.Warning,
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        elevation = ButtonDefaults.buttonElevation(0.dp),
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Manage relay",
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                else -> {}
            }
        }
    }
}
