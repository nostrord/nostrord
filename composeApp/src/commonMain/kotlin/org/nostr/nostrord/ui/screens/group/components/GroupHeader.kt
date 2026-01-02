package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Enhanced group header with description, member count, and status indicators.
 */
@Composable
fun GroupHeader(
    groupName: String?,
    groupMetadata: GroupMetadata?,
    isJoined: Boolean,
    onBackClick: () -> Unit,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
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

            // Group avatar
            ProfileAvatar(
                imageUrl = groupMetadata?.picture,
                displayName = groupName ?: "Group",
                pubkey = groupMetadata?.id ?: "",
                size = 36.dp
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Group name and description
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = groupName ?: "Unknown Group",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Description
                if (!groupMetadata?.about.isNullOrBlank()) {
                    Text(
                        text = groupMetadata?.about ?: "",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isJoined) {
                    // Join button for non-members
                    Button(
                        onClick = onJoinClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Join", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    // Dropdown menu for members
                    var menuExpanded by remember { mutableStateOf(false) }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = NostrordColors.Surface,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Settings", color = NostrordColors.TextPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    onSettingsClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Leave Group",
                                        color = NostrordColors.Error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onLeaveClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ExitToApp,
                                        contentDescription = null,
                                        tint = NostrordColors.Error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
