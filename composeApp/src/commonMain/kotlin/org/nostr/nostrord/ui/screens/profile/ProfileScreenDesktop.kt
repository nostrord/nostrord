package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Desktop profile screen with centered content card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreenDesktop(
    displayName: String?,
    username: String?,
    avatarUrl: String?,
    pubkey: String?,
    npub: String?,
    about: String?,
    showCopiedMessage: Boolean,
    onCopyNpub: () -> Unit,
    onEditProfile: () -> Unit,
    onBackupKeys: () -> Unit,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Top bar
            TopAppBar(
                title = {
                    Text(
                        "Profile",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.BackgroundDark
                )
            )

            // Centered content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.xl),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp)
                ) {
                    // Profile card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NostrordShapes.cardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = NostrordColors.Surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Large avatar
                            ProfileAvatar(
                                imageUrl = avatarUrl,
                                displayName = displayName ?: "User",
                                pubkey = pubkey ?: "",
                                size = 120.dp
                            )

                            Spacer(modifier = Modifier.height(Spacing.lg))

                            // Display name
                            Text(
                                text = displayName ?: "Anonymous",
                                style = NostrordTypography.ServerHeader,
                                color = Color.White
                            )

                            // Username
                            if (username != null && username != displayName) {
                                Spacer(modifier = Modifier.height(Spacing.xs))
                                Text(
                                    text = "@$username",
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.TextSecondary
                                )
                            }

                            // About/bio
                            if (!about.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Text(
                                    text = about,
                                    style = NostrordTypography.MessageBody,
                                    color = NostrordColors.TextContent,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Public key
                            if (npub != null) {
                                Spacer(modifier = Modifier.height(Spacing.xl))

                                Text(
                                    text = "PUBLIC KEY",
                                    style = NostrordTypography.SectionHeader,
                                    color = NostrordColors.TextMuted,
                                    modifier = Modifier.align(Alignment.Start)
                                )

                                Spacer(modifier = Modifier.height(Spacing.sm))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(NostrordShapes.shapeSmall)
                                        .background(NostrordColors.InputBackground)
                                        .clickable(onClick = onCopyNpub)
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .padding(Spacing.md),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = npub,
                                        style = NostrordTypography.Caption,
                                        color = NostrordColors.TextContent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(Spacing.sm))
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(Spacing.iconSm + Spacing.xs)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Settings card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NostrordShapes.cardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = NostrordColors.Surface
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "SETTINGS",
                                style = NostrordTypography.SectionHeader,
                                color = NostrordColors.TextMuted,
                                modifier = Modifier.padding(
                                    start = Spacing.lg,
                                    top = Spacing.lg,
                                    bottom = Spacing.sm
                                )
                            )

                            SettingsMenuItem(
                                icon = Icons.Default.Edit,
                                title = "Edit Profile",
                                subtitle = "Update your profile information",
                                onClick = onEditProfile
                            )

                            HorizontalDivider(
                                color = NostrordColors.Divider,
                                modifier = Modifier.padding(horizontal = Spacing.lg)
                            )

                            SettingsMenuItem(
                                icon = Icons.Default.Key,
                                title = "Backup Keys",
                                subtitle = "Save your private key securely",
                                onClick = onBackupKeys
                            )

                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Logout card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NostrordShapes.cardShape,
                        colors = CardDefaults.cardColors(
                            containerColor = NostrordColors.Surface
                        )
                    ) {
                        SettingsMenuItem(
                            icon = Icons.AutoMirrored.Filled.Logout,
                            title = "Log Out",
                            subtitle = "Sign out of your account",
                            onClick = onLogout,
                            iconTint = NostrordColors.Error,
                            titleColor = NostrordColors.Error
                        )
                    }
                }
            }
        }

        // Snackbar for copy confirmation
        if (showCopiedMessage) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.lg),
                containerColor = NostrordColors.Success
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Success",
                        tint = Color.White,
                        modifier = Modifier.size(Spacing.iconSm + Spacing.xs)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        "Copied to clipboard",
                        color = Color.White,
                        style = NostrordTypography.Button
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconTint: Color = NostrordColors.TextSecondary,
    titleColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(NostrordColors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(Spacing.iconMd)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = NostrordTypography.ChannelName,
                color = titleColor
            )
            Text(
                text = subtitle,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted
            )
        }

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(Spacing.iconMd)
        )
    }
}
