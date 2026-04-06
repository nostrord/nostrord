package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
 * Mobile edit profile screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileScreenMobile(
    name: String,
    about: String,
    pictureUrl: String,
    bannerUrl: String,
    nip05: String,
    lightningAddress: String,
    website: String,
    pubkey: String?,
    isSaving: Boolean,
    showSuccessMessage: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onPictureUrlChange: (String) -> Unit,
    onBannerUrlChange: (String) -> Unit,
    onNip05Change: (String) -> Unit,
    onLightningAddressChange: (String) -> Unit,
    onWebsiteChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Edit Profile",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White
                    )
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
        },
        containerColor = NostrordColors.Background,
        snackbarHost = {
            when {
                showSuccessMessage -> {
                    Snackbar(
                        modifier = Modifier.padding(Spacing.lg),
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
                        modifier = Modifier.padding(Spacing.lg),
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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Avatar preview section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.Surface)
                    .padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileAvatar(
                    imageUrl = pictureUrl.ifBlank { null },
                    displayName = name.ifBlank { "User" },
                    pubkey = pubkey ?: "",
                    size = 96.dp
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "Avatar Preview",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            // Form fields
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.Surface)
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                ProfileTextField(
                    label = "Name",
                    value = name,
                    onValueChange = onNameChange,
                    placeholder = "Your name"
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
                    label = "Banner URL",
                    value = bannerUrl,
                    onValueChange = onBannerUrlChange,
                    placeholder = "https://example.com/banner.jpg"
                )

                ProfileTextField(
                    label = "Nostr Address (NIP-05)",
                    value = nip05,
                    onValueChange = onNip05Change,
                    placeholder = "you@example.com"
                )

                ProfileTextField(
                    label = "Lightning Address",
                    value = lightningAddress,
                    onValueChange = onLightningAddressChange,
                    placeholder = "you@walletofsatoshi.com"
                )

                ProfileTextField(
                    label = "Website",
                    value = website,
                    onValueChange = onWebsiteChange,
                    placeholder = "https://example.com"
                )
}

            Spacer(modifier = Modifier.height(Spacing.xxl))
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
