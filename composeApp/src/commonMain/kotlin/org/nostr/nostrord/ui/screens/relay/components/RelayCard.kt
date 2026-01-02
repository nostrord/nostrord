package org.nostr.nostrord.ui.screens.relay.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    isActive: Boolean,
    isCompact: Boolean,
    onSelectRelay: () -> Unit,
    onDeleteRelay: (() -> Unit)? = null
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
            .then(
                if (isActive) Modifier.border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(NostrordColors.Primary, NostrordColors.Success)
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                else Modifier.border(1.dp, NostrordColors.Divider, RoundedCornerShape(12.dp))
            )
            .clickable(onClick = onSelectRelay),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) NostrordColors.SurfaceVariant else NostrordColors.Surface
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 4.dp else 0.dp
        )
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
                        .background(
                            if (isActive) NostrordColors.Primary.copy(alpha = 0.2f)
                            else NostrordColors.SurfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = if (isActive) NostrordColors.Primary else NostrordColors.TextSecondary,
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
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Action buttons
            if (isActive) {
                // Active badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(NostrordColors.Success.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = NostrordColors.Success,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Active",
                            color = NostrordColors.Success,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                // Select and delete buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Delete button (if callback provided)
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

                    // Select button
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
}
