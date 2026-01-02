package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Enhanced member sidebar with online/offline status, avatars, and role badges.
 * Inspired by Discord's member list design.
 */
@Composable
fun MemberSidebar(
    members: List<MemberInfo>,
    recentlyActiveMembers: Set<String> = emptySet(),
    onMemberClick: (MemberInfo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val onlineMembers = members.filter { it.pubkey in recentlyActiveMembers }
    val offlineMembers = members.filter { it.pubkey !in recentlyActiveMembers }

    var onlineExpanded by remember { mutableStateOf(true) }
    var offlineExpanded by remember { mutableStateOf(true) }

    // Use passed modifier first (allows fillMaxWidth on mobile), then apply defaults
    val finalModifier = if (modifier == Modifier) {
        // Default case (desktop): fixed width
        Modifier.width(240.dp)
    } else {
        // Custom modifier passed (mobile): use it
        modifier
    }

    Column(
        modifier = finalModifier
            .fillMaxHeight()
            .background(NostrordColors.Surface)
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(NostrordColors.BackgroundDark)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Members — ${members.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Online section
                if (onlineMembers.isNotEmpty()) {
                    item {
                        MemberSectionHeader(
                            title = "ONLINE",
                            count = onlineMembers.size,
                            expanded = onlineExpanded,
                            onToggle = { onlineExpanded = !onlineExpanded }
                        )
                    }

                    if (onlineExpanded) {
                        items(onlineMembers) { member ->
                            MemberItem(
                                member = member,
                                isOnline = true,
                                onClick = { onMemberClick(member) }
                            )
                        }
                    }
                }

                // Offline section
                if (offlineMembers.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        MemberSectionHeader(
                            title = "OFFLINE",
                            count = offlineMembers.size,
                            expanded = offlineExpanded,
                            onToggle = { offlineExpanded = !offlineExpanded }
                        )
                    }

                    if (offlineExpanded) {
                        items(offlineMembers) { member ->
                            MemberItem(
                                member = member,
                                isOnline = false,
                                onClick = { onMemberClick(member) }
                            )
                        }
                    }
                }

                // If no categorization needed (all shown as active)
                if (onlineMembers.isEmpty() && offlineMembers.isEmpty() && members.isNotEmpty()) {
                    item {
                        MemberSectionHeader(
                            title = "MEMBERS",
                            count = members.size,
                            expanded = true,
                            onToggle = {}
                        )
                    }
                    items(members) { member ->
                        MemberItem(
                            member = member,
                            isOnline = null,
                            onClick = { onMemberClick(member) }
                        )
                    }
                }
            }

            VerticalScrollbarWrapper(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun MemberSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$title — $count",
            color = NostrordColors.TextMuted,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun MemberItem(
    member: MemberInfo,
    isOnline: Boolean?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with status indicator
        Box {
            MemberAvatar(
                member = member,
                size = 32.dp,
                dimmed = isOnline == false
            )

            // Status indicator dot
            if (isOnline != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(12.dp)
                        .background(NostrordColors.Surface, CircleShape)
                        .padding(2.dp)
                        .background(
                            if (isOnline) NostrordColors.Success else NostrordColors.TextMuted,
                            CircleShape
                        )
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name
        Text(
            text = member.displayName,
            color = if (isOnline == false) NostrordColors.TextMuted else Color.White,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MemberAvatar(
    member: MemberInfo,
    size: androidx.compose.ui.unit.Dp,
    dimmed: Boolean = false
) {
    val context = LocalPlatformContext.current
    val alpha = if (dimmed) 0.5f else 1f

    if (!member.picture.isNullOrBlank()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(member.picture)
                .crossfade(true)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = member.displayName,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            alpha = alpha
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(generateColorFromString(member.pubkey).copy(alpha = alpha)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = member.displayName.take(1).uppercase(),
                color = Color.White.copy(alpha = alpha),
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
