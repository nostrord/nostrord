package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Desktop edit profile screen with centered content card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreenDesktop(
    displayName: String,
    username: String,
    about: String,
    pictureUrl: String,
    nip05: String,
    pubkey: String?,
    isSaving: Boolean,
    showSuccessMessage: Boolean,
    errorMessage: String?,
    onDisplayNameChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onPictureUrlChange: (String) -> Unit,
    onNip05Change: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
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
                        "Edit Profile",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = NostrordColors.Primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Save",
                                color = NostrordColors.Primary,
                                style = NostrordTypography.Button
                            )
                        }
                    }
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
                    // Avatar preview card
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
                            ProfileAvatar(
                                imageUrl = pictureUrl.ifBlank { null },
                                displayName = displayName.ifBlank { "User" },
                                pubkey = pubkey ?: "",
                                size = 120.dp
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))
                            Text(
                                text = "Avatar Preview",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Form card
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
                            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                        ) {
                            Text(
                                text = "PROFILE INFORMATION",
                                style = NostrordTypography.SectionHeader,
                                color = NostrordColors.TextMuted
                            )

                            Spacer(modifier = Modifier.height(Spacing.sm))

                            ProfileTextField(
                                label = "Display Name",
                                value = displayName,
                                onValueChange = onDisplayNameChange,
                                placeholder = "Your display name"
                            )

                            ProfileTextField(
                                label = "Username",
                                value = username,
                                onValueChange = onUsernameChange,
                                placeholder = "your_username"
                            )

                            ProfileTextField(
                                label = "About",
                                value = about,
                                onValueChange = onAboutChange,
                                placeholder = "Tell us about yourself",
                                singleLine = false,
                                maxLines = 4
                            )

                            ProfileTextField(
                                label = "Avatar URL",
                                value = pictureUrl,
                                onValueChange = onPictureUrlChange,
                                placeholder = "https://example.com/avatar.jpg"
                            )

                            ProfileTextField(
                                label = "NIP-05 Identifier",
                                value = nip05,
                                onValueChange = onNip05Change,
                                placeholder = "you@example.com"
                            )
                        }
                    }
                }
            }
        }

        // Snackbar for messages
        when {
            showSuccessMessage -> {
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
                            "Profile updated successfully",
                            color = Color.White,
                            style = NostrordTypography.Button
                        )
                    }
                }
            }
            errorMessage != null -> {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.lg),
                    containerColor = NostrordColors.Error
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = "Error",
                            tint = Color.White,
                            modifier = Modifier.size(Spacing.iconSm + Spacing.xs)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            errorMessage,
                            color = Color.White,
                            style = NostrordTypography.Button
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    Column {
        Text(
            text = label,
            style = NostrordTypography.SectionHeader,
            color = NostrordColors.TextMuted
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = placeholder,
                    color = NostrordColors.TextMuted
                )
            },
            singleLine = singleLine,
            maxLines = maxLines,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NostrordColors.Primary,
                unfocusedBorderColor = NostrordColors.Divider,
                focusedContainerColor = NostrordColors.InputBackground,
                unfocusedContainerColor = NostrordColors.InputBackground,
                cursorColor = NostrordColors.Primary,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = NostrordShapes.shapeSmall
        )
    }
}
