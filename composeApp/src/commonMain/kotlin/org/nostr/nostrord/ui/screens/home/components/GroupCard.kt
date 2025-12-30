package org.nostr.nostrord.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Enhanced group card with cover image, badges, and member count.
 * Inspired by Discord Groups design.
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
    val hasCoverImage = !group.picture.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        // Cover image area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            if (hasCoverImage) {
                // Cover image from group.picture
                AsyncImage(
                    model = group.picture,
                    contentDescription = groupName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Gradient overlay for text readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    NostrordColors.Surface.copy(alpha = 0.7f)
                                )
                            )
                        )
                )
            } else {
                // Fallback: gradient background with pattern
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    generateColorFromString(group.id),
                                    generateColorFromString(group.id + "alt").copy(alpha = 0.7f)
                                )
                            )
                        )
                )
            }

            // Badges in top-right corner
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (group.isPublic) {
                    Badge(
                        icon = { Icon(Icons.Default.Public, contentDescription = "Public", modifier = Modifier.size(12.dp), tint = Color.White) },
                        text = "Public"
                    )
                }
                if (group.isOpen) {
                    Badge(
                        icon = { Icon(Icons.Default.Lock, contentDescription = "Open", modifier = Modifier.size(12.dp), tint = NostrordColors.Success) },
                        text = "Open",
                        backgroundColor = NostrordColors.Success.copy(alpha = 0.2f),
                        textColor = NostrordColors.Success
                    )
                }
            }

            // Avatar overlapping the cover with unread badge
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 12.dp, y = 24.dp)
            ) {
                GroupAvatar(
                    groupId = group.id,
                    groupName = groupName,
                    pictureUrl = group.picture,
                    size = 48.dp
                )

                // Unread badge positioned at top-right of avatar
                if (unreadCount > 0) {
                    UnreadBadge(
                        count = unreadCount,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-4).dp),
                        size = 18.dp
                    )
                }
            }
        }

        // Content area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 72.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
        ) {
            // Group name
            Text(
                text = groupName,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Description
            if (!group.about.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = group.about,
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Member count and joined status
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Member count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = "Members",
                        modifier = Modifier.size(14.dp),
                        tint = NostrordColors.TextMuted
                    )
                    Text(
                        text = if (memberCount > 0) "$memberCount members" else "No activity yet",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Joined indicator
                if (isJoined) {
                    Text(
                        text = "Joined",
                        color = NostrordColors.Success,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun Badge(
    icon: @Composable () -> Unit,
    text: String,
    backgroundColor: Color = Color.Black.copy(alpha = 0.5f),
    textColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .background(backgroundColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon()
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun GroupAvatar(
    groupId: String,
    groupName: String,
    pictureUrl: String?,
    size: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(NostrordColors.Surface)
            .padding(2.dp) // Border effect
    ) {
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model = pictureUrl,
                contentDescription = groupName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
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
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
