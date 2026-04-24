package org.nostr.nostrord.ui.screens.relay.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.screens.relay.model.RelayStatus
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Extract the domain name from a WebSocket URL.
 * e.g., "wss://groups.fiatjaf.com" -> "groups.fiatjaf.com"
 */
private fun extractDomain(url: String): String {
    return url
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")
}

@Composable
fun RelayCard(
    relay: RelayInfo,
    isCompact: Boolean,
    onSelectRelay: () -> Unit,
    onDeleteRelay: (() -> Unit)? = null,
    isLazyFetch: Boolean = false,
    onToggleLazyFetch: ((Boolean) -> Unit)? = null
) {
    val statusColor = when (relay.status) {
        RelayStatus.CONNECTED -> NostrordColors.StatusOnline
        RelayStatus.CONNECTING -> NostrordColors.StatusIdle
        RelayStatus.ERROR -> NostrordColors.Error
        RelayStatus.DISCONNECTED -> NostrordColors.StatusOffline
    }

    val statusText = when (relay.status) {
        RelayStatus.CONNECTED -> "Connected"
        RelayStatus.CONNECTING -> "Connecting..."
        RelayStatus.ERROR -> "Error"
        RelayStatus.DISCONNECTED -> "Disconnected"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NostrordColors.Divider, RoundedCornerShape(12.dp))
            .clickable(onClick = onSelectRelay),
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Relay icon with status indicator
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(if (isCompact) 44.dp else 52.dp)
                        .clip(CircleShape)
                        .background(NostrordColors.SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = NostrordColors.TextSecondary,
                        modifier = Modifier.size(if (isCompact) 24.dp else 28.dp)
                    )
                }

                // Status dot
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(NostrordColors.Surface)
                        .padding(2.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
            }

            Spacer(modifier = Modifier.width(if (isCompact) 12.dp else 16.dp))

            // Relay info
            Column(modifier = Modifier.weight(1f)) {
                // Domain name (prominent)
                Text(
                    text = extractDomain(relay.url),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    if (!isCompact && relay.groupCount != null) {
                        Text(
                            text = " • ",
                            color = NostrordColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "${relay.groupCount} groups",
                            color = NostrordColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Description (desktop only)
                if (!isCompact && relay.details.isNotBlank() && relay.details != "No additional details available.") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = relay.details,
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp
                    )
                }

                // Fetch mode toggle
                if (onToggleLazyFetch != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Always fetch all groups",
                                color = NostrordColors.TextPrimary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Load the full group list at startup instead of waiting until Other Groups is opened",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = !isLazyFetch,
                            onCheckedChange = { onToggleLazyFetch(!it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = NostrordColors.Primary,
                                uncheckedThumbColor = NostrordColors.TextMuted,
                                uncheckedTrackColor = NostrordColors.SurfaceVariant
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDeleteRelay != null) {
                    IconButton(
                        onClick = onDeleteRelay,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove relay",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Button(
                    onClick = onSelectRelay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NostrordColors.Primary
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Connect",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
