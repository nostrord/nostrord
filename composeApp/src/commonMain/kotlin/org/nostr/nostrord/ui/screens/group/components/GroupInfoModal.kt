package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.RichAboutText
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Group info modal displaying group details and cover image.
 *
 * Features:
 * - Cover/banner image at the top (if available)
 * - Group avatar overlapping the banner
 * - Group name and description
 * - Public/Private and Open/Closed status badges
 * - Close button
 */
@Composable
fun GroupInfoModal(
    groupId: String,
    groupName: String?,
    groupMetadata: GroupMetadata?,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    onUserClick: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            // Modal card
            Card(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = NostrordColors.Surface
                )
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // Banner/Cover image section
                    BannerSection(
                        coverImageUrl = groupMetadata?.picture,
                        groupName = groupName,
                        groupId = groupId,
                        onCloseClick = onDismiss
                    )

                    // Content section
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(bottom = Spacing.lg)
                    ) {
                        Spacer(modifier = Modifier.height(Spacing.md))

                        // Group name
                        Text(
                            text = groupMetadata?.name ?: groupName ?: "Unknown Group",
                            style = NostrordTypography.ServerHeader,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        // Status badges
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            // Public/Private badge
                            StatusBadge(
                                icon = if (groupMetadata?.isPublic == true) Icons.Default.Public else Icons.Default.Lock,
                                text = if (groupMetadata?.isPublic == true) "Public" else "Private",
                                color = if (groupMetadata?.isPublic == true) NostrordColors.Success else NostrordColors.TextSecondary
                            )

                            // Open/Closed badge
                            StatusBadge(
                                text = if (groupMetadata?.isOpen == true) "Open" else "Closed",
                                color = if (groupMetadata?.isOpen == true) NostrordColors.Primary else NostrordColors.TextMuted
                            )
                        }

                        // Description
                        if (!groupMetadata?.about.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(Spacing.lg))

                            Text(
                                text = "ABOUT",
                                style = NostrordTypography.SectionHeader,
                                color = NostrordColors.TextMuted
                            )

                            Spacer(modifier = Modifier.height(Spacing.sm))

                            RichAboutText(
                                text = groupMetadata?.about ?: "",
                                userMetadata = userMetadata,
                                style = NostrordTypography.MessageBody,
                                color = NostrordColors.TextContent,
                                onMentionClick = onUserClick
                            )
                        }

                        // Group ID
                        Spacer(modifier = Modifier.height(Spacing.lg))

                        Text(
                            text = "GROUP ID",
                            style = NostrordTypography.SectionHeader,
                            color = NostrordColors.TextMuted
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        Text(
                            text = groupId,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
private fun BannerSection(
    coverImageUrl: String?,
    groupName: String?,
    groupId: String,
    onCloseClick: () -> Unit
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val hasCoverImage = !coverImageUrl.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        // Cover image or gradient background
        if (hasCoverImage) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverImageUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "Group cover",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
                onState = { imageState = it }
            )

            // Gradient overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f)
                            )
                        )
                    )
            )
        } else {
            // Gradient background when no cover image
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                NostrordColors.Primary.copy(alpha = 0.8f),
                                NostrordColors.Primary.copy(alpha = 0.4f)
                            )
                        )
                    )
            )
        }

        // Loading indicator for cover image
        if (hasCoverImage && imageState is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }

        // Close button
        IconButton(
            onClick = onCloseClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.sm)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Group avatar at bottom, overlapping
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = Spacing.lg)
                .offset(y = 40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NostrordColors.Surface)
                    .padding(4.dp)
            ) {
                GroupInfoIcon(
                    pictureUrl = coverImageUrl,
                    groupId = groupId,
                    displayName = groupName ?: "Group",
                    size = 80.dp
                )
            }
        }
    }

    // Spacer for avatar overflow
    Spacer(modifier = Modifier.height(48.dp))
}

/**
 * Status badge component for displaying group status.
 */
@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = text,
                style = NostrordTypography.Caption,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun GroupInfoIcon(
    pictureUrl: String?,
    groupId: String,
    displayName: String,
    size: androidx.compose.ui.unit.Dp
) {
    val context = LocalPlatformContext.current
    val iconShape = RoundedCornerShape(12.dp)
    var imageState by remember(pictureUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val showImage = !pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error

    Box(
        modifier = Modifier
            .size(size)
            .clip(iconShape)
            .background(if (!showImage) generateColorFromString(groupId) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        if (!showImage) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(iconShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it }
            )
        }
    }
}
