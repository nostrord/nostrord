package org.nostr.nostrord.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Simplified row-style group card for joined groups list.
 *
 * Layout:
 * - 44dp height
 * - 40dp avatar
 * - 12dp gap
 * - Group name (bold white)
 * - Subtext with channel and member count
 * - Unread badge at right edge
 */
@Composable
fun GroupCard(
    group: GroupMetadata,
    onClick: () -> Unit,
    memberCount: Int = 0,
    isJoined: Boolean = false,
    unreadCount: Int = 0
) {
    val groupName = group.name ?: group.id
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.memberItemHeight + Spacing.xxs) // ~44dp
            .border(
                width = 1.dp,
                color = NostrordColors.Divider,
                shape = NostrordShapes.channelItemShape
            )
            .clip(NostrordShapes.channelItemShape)
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with unread badge
        Box {
            GroupAvatar(
                groupId = group.id,
                groupName = groupName,
                pictureUrl = group.picture,
                size = Spacing.avatarSize
            )

            // Unread badge positioned at bottom-right of avatar
            if (unreadCount > 0) {
                UnreadBadge(
                    count = unreadCount,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = Spacing.xxs, y = Spacing.xxs),
                    size = Spacing.badgeSize
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        // Name and subtext
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            // Group name - bold white
            Text(
                text = groupName,
                color = if (unreadCount > 0) NostrordColors.ChannelUnread else Color.White,
                style = if (unreadCount > 0) NostrordTypography.ChannelNameUnread else NostrordTypography.ChannelName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Subtext with description
            val description = group.about?.takeIf { it.isNotBlank() }
            if (description != null) {
                Text(
                    text = description,
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Timestamp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GroupAvatar(
    groupId: String,
    groupName: String,
    pictureUrl: String?,
    size: Dp
) {
    val context = LocalPlatformContext.current

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
    ) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val showPlaceholder = pictureUrl.isNullOrBlank() ||
            imageState is AsyncImagePainter.State.Loading ||
            imageState is AsyncImagePainter.State.Error

        // Show placeholder when no URL, loading, or error
        if (showPlaceholder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(generateColorFromString(groupId)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = groupName.take(1).uppercase(),
                    color = Color.White,
                    fontSize = (size.value * 0.4f).sp,
                    style = NostrordTypography.AvatarInitial
                )
            }
        }

        // Only attempt to load image if URL is provided and not in error state
        if (!pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = groupName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it }
            )
        }
    }
}
