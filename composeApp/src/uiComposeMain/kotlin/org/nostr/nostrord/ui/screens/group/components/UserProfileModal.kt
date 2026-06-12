package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip57
import org.nostr.nostrord.ui.components.IdentifierField
import org.nostr.nostrord.ui.components.RichAboutText
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.zap.ZapController
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.rememberClipboardWriter

/** One row in the profile modal's action list (prototype ActionRow). */
@Composable
private fun ProfileActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint =
            when {
                danger -> NostrordColors.Error
                isHovered -> NostrordColors.TextPrimary
                else -> NostrordColors.TextSecondary
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            color =
            when {
                danger -> NostrordColors.Error
                isHovered -> NostrordColors.TextPrimary
                else -> NostrordColors.TextSecondary
            },
            fontSize = 14.sp,
        )
    }
}

/**
 * User profile modal displaying user details and banner image.
 *
 * Features:
 * - Banner image at the top (if available)
 * - User avatar overlapping the banner
 * - Display name and username
 * - About/bio section
 * - Copy npub functionality
 * - Close button
 */
@Composable
fun UserProfileModal(
    pubkey: String,
    metadata: UserMetadata?,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    onUserClick: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val copyToClipboard = rememberClipboardWriter()
    val npub = remember(pubkey) { Nip19.encodeNpub(pubkey) }
    val displayName = metadata?.displayName ?: metadata?.name ?: pubkey.take(8) + "..."

    // Zaps require signing a kind:9734 request, so only offer them when an account
    // with a usable signer is active.
    val activeSession by ActiveAccountManager.session.collectAsState()
    val canZap = activeSession != null &&
        Nip57.resolvePayEndpoint(metadata?.lud16, metadata?.lud06) != null
    val username = metadata?.name

    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            // Modal card
            Card(
                modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors =
                CardDefaults.cardColors(
                    containerColor = NostrordColors.Surface,
                ),
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    // Banner section
                    UserBannerSection(
                        bannerUrl = metadata?.banner,
                        avatarUrl = metadata?.picture,
                        displayName = displayName,
                        pubkey = pubkey,
                        onCloseClick = onDismiss,
                    )

                    // Content section
                    Column(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(bottom = Spacing.lg),
                    ) {
                        Spacer(modifier = Modifier.height(Spacing.md))

                        // Name on the left, Zap pill aligned right just below the banner.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                // Display name
                                Text(
                                    text = displayName,
                                    style = NostrordTypography.ServerHeader,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                )

                                // Username (if different from display name)
                                if (username != null && username != displayName) {
                                    Text(
                                        text = "@$username",
                                        style = NostrordTypography.Caption,
                                        color = NostrordColors.TextSecondary,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        // About/bio
                        if (!metadata?.about.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(Spacing.md))

                            Text(
                                text = "ABOUT",
                                style = NostrordTypography.SectionHeader,
                                color = NostrordColors.TextMuted,
                            )

                            Spacer(modifier = Modifier.height(Spacing.sm))

                            RichAboutText(
                                text = metadata?.about ?: "",
                                userMetadata = userMetadata,
                                style = NostrordTypography.MessageBody,
                                color = NostrordColors.TextContent,
                                onMentionClick = onUserClick,
                            )
                        }

                        // Cycling identifier (prototype IdentifierField): npub / nprofile /
                        // link / hex / nip-05 with swap + copy.
                        Spacer(modifier = Modifier.height(Spacing.lg))
                        IdentifierField(pubkey = pubkey, nip05 = metadata?.nip05)

                        // Prototype UserProfileCard: jump to the full profile page. Only
                        // available inside the new-design frame (the navigator local is
                        // absent under the legacy navigation).
                        val frameNavigator = LocalFrameNavigator.current
                        if (frameNavigator != null) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            AppButton(
                                text = "View profile",
                                onClick = {
                                    onDismiss()
                                    frameNavigator(UserRoute(pubkey))
                                },
                                variant = AppButtonVariant.Secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Prototype UserProfileCard action list. Mention / mute / report and
                        // the admin actions join the list when their backends land (composer
                        // bridge, mute list, NIP-56 reports, group-scoped moderation).
                        if (canZap) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            ProfileActionRow(
                                icon = Icons.Outlined.Bolt,
                                label = "Send zap",
                            ) {
                                ZapController.request(pubkey, null)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Banner section with cover image and avatar overlay.
 */
@Composable
private fun UserBannerSection(
    bannerUrl: String?,
    avatarUrl: String?,
    displayName: String,
    pubkey: String,
    onCloseClick: () -> Unit,
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val hasBannerImage = !bannerUrl.isNullOrBlank()

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        // Banner image or gradient background
        if (hasBannerImage) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(bannerUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "User banner",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
                onState = { imageState = it },
            )

            // Gradient overlay for text readability
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                            ),
                        ),
                    ),
            )
        } else {
            // Gradient background when no banner image
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                            listOf(
                                NostrordColors.Primary.copy(alpha = 0.8f),
                                NostrordColors.Primary.copy(alpha = 0.4f),
                            ),
                        ),
                    ),
            )
        }

        // Loading indicator for banner image
        if (hasBannerImage && imageState is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(
                modifier =
                Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        // Close button
        IconButton(
            onClick = onCloseClick,
            modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.sm)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }

        // User avatar at bottom, overlapping
        Box(
            modifier =
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = Spacing.lg)
                .offset(y = 40.dp),
        ) {
            Box(
                modifier =
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Surface)
                    .padding(4.dp),
            ) {
                ProfileAvatar(
                    imageUrl = avatarUrl,
                    displayName = displayName,
                    pubkey = pubkey,
                    size = 80.dp,
                )
            }
        }
    }

    // Spacer for avatar overflow
    Spacer(modifier = Modifier.height(48.dp))
}
