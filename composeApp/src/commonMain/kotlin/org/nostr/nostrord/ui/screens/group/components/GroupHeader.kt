package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Enhanced group header with description, member count, and status indicators.
 */
@Composable
fun GroupHeader(
    selectedChannel: String,
    groupMetadata: GroupMetadata?,
    connectionState: ConnectionManager.ConnectionState,
    memberCount: Int,
    isJoined: Boolean,
    onBackClick: () -> Unit,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NostrordColors.BackgroundDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Navigation icons
            if (navigationIcon != null) {
                navigationIcon()
            } else {
                IconButton(onClick = onBackClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            // Channel name and description
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#$selectedChannel",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Divider
                    if (!groupMetadata?.about.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(NostrordColors.Divider)
                        )
                        Spacer(modifier = Modifier.width(12.dp))

                        // Description snippet
                        Text(
                            text = groupMetadata?.about ?: "",
                            color = NostrordColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }

                // Status row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Connection status indicator
                    ConnectionStatusIndicator(connectionState)

                    // Member count
                    if (memberCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = "Members",
                                modifier = Modifier.size(12.dp),
                                tint = NostrordColors.TextMuted
                            )
                            Text(
                                text = "$memberCount",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Notifications button
                IconButton(
                    onClick = onNotificationsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Notifications,
                        contentDescription = "Notifications",
                        tint = NostrordColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Settings button
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = NostrordColors.TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Join/Leave button
                if (!isJoined) {
                    Button(
                        onClick = onJoinClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Join", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    OutlinedButton(
                        onClick = onLeaveClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NostrordColors.Error),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Leave", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(
    connectionState: ConnectionManager.ConnectionState
) {
    val (color, text) = when (connectionState) {
        is ConnectionManager.ConnectionState.Connected -> NostrordColors.Success to "Connected"
        is ConnectionManager.ConnectionState.Connecting -> NostrordColors.Warning to "Connecting..."
        is ConnectionManager.ConnectionState.Disconnected -> NostrordColors.TextMuted to "Disconnected"
        is ConnectionManager.ConnectionState.Error -> NostrordColors.Error to "Error"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = text,
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Compact header for mobile with essential info only.
 */
@Composable
fun GroupHeaderCompact(
    selectedChannel: String,
    groupMetadata: GroupMetadata?,
    connectionState: ConnectionManager.ConnectionState,
    memberCount: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "#$selectedChannel",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection status
            ConnectionStatusIndicator(connectionState)

            // Member count
            if (memberCount > 0) {
                Text(
                    text = "• $memberCount members",
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
