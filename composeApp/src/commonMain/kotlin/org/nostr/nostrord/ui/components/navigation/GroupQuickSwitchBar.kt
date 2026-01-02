package org.nostr.nostrord.ui.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Quick-switch bar for navigating between joined groups.
 * Displays a horizontal scrollable row of group avatars with:
 * - Home button to go back to group discovery
 * - Joined group avatars with active state indicator
 * - Add button to explore/join new groups
 */
@Composable
fun GroupQuickSwitchBar(
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    activeGroupId: String?,
    onHomeClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onExploreClick: () -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 44.dp,
    unreadCounts: Map<String, Int> = emptyMap()
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(NostrordColors.BackgroundDark)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Home button
        QuickSwitchItem(
            isActive = activeGroupId == null,
            onClick = onHomeClick,
            size = avatarSize,
            tooltip = "Home"
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home",
                tint = if (activeGroupId == null) NostrordColors.Primary else Color.White,
                modifier = Modifier.size(24.dp)
            )
        }

        // Divider
        if (joinedGroups.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(avatarSize * 0.6f)
                    .background(NostrordColors.Divider, RoundedCornerShape(1.dp))
            )
        }

        // Joined groups
        joinedGroups.forEach { groupId ->
            val group = groups.find { it.id == groupId }
            val groupName = group?.name ?: groupId
            val isActive = activeGroupId == groupId
            val unreadCount = unreadCounts[groupId] ?: 0

            QuickSwitchItem(
                isActive = isActive,
                onClick = { onGroupClick(groupId, group?.name) },
                size = avatarSize,
                tooltip = groupName,
                unreadCount = unreadCount
            ) {
                GroupAvatar(
                    groupId = groupId,
                    groupName = groupName,
                    pictureUrl = group?.picture,
                    size = avatarSize - 4.dp // Account for border padding
                )
            }
        }

        // Add/Explore button
        QuickSwitchItem(
            isActive = false,
            onClick = onExploreClick,
            size = avatarSize,
            tooltip = "Explore Groups",
            showActiveRing = false
        ) {
            Box(
                modifier = Modifier
                    .size(avatarSize - 4.dp)
                    .background(NostrordColors.SurfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Explore Groups",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickSwitchItem(
    isActive: Boolean,
    onClick: () -> Unit,
    size: Dp,
    tooltip: String,
    showActiveRing: Boolean = true,
    unreadCount: Int = 0,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (isActive && showActiveRing) {
                    Modifier.border(2.dp, NostrordColors.Primary, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        content()

        // Unread badge
        if (unreadCount > 0 && !isActive) {
            UnreadBadge(
                count = unreadCount,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = Spacing.xxs, y = -Spacing.xxs),
                size = Spacing.badgeSize
            )
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
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val showPlaceholder = pictureUrl.isNullOrBlank() ||
            imageState is AsyncImagePainter.State.Loading ||
            imageState is AsyncImagePainter.State.Error

        // Show placeholder when no URL, loading, or error
        if (showPlaceholder) {
            Box(
                modifier = Modifier
                    .size(size)
                    .background(generateColorFromString(groupId), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = groupName.take(1).uppercase(),
                    color = Color.White,
                    fontSize = (size.value * 0.4f).sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
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
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it }
            )
        }
    }
}
