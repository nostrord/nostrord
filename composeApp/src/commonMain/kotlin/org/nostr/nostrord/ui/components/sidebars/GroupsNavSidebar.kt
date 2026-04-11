package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
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
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.ui.components.navigation.relayShortLabel
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Groups navigation sidebar — second column (240dp).
 *
 * Shows the relay name as header and the list of joined groups
 * for the currently active relay. Follows the nostrord-design spec.
 */
@Composable
fun GroupsNavSidebar(
    relayUrl: String,
    groups: List<GroupMetadata>,
    joinedGroupIds: Set<String>,
    activeGroupId: String?,
    unreadCounts: Map<String, Int> = emptyMap(),
    relayName: String? = null,
    isLoading: Boolean = false,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit = {},
    onAddRelay: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Reset when relay changes
    var searchQuery by remember(relayUrl) { mutableStateOf("") }

    val myGroups = remember(groups, joinedGroupIds) {
        groups.filter { it.id in joinedGroupIds }
    }
    val otherGroups = remember(groups, joinedGroupIds, searchQuery) {
        val base = groups.filter { it.id !in joinedGroupIds }
        if (searchQuery.isBlank()) base
        else base.filter { it.name?.contains(searchQuery, ignoreCase = true) == true || it.id.contains(searchQuery, ignoreCase = true) }
    }

    var myGroupsExpanded by remember(relayUrl) { mutableStateOf(true) }
    var otherGroupsExpanded by remember(relayUrl) { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(NostrordColors.Surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.headerHeight)
                .background(NostrordColors.Surface)
                .padding(horizontal = Spacing.lg),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = if (relayUrl.isBlank()) "No Relay" else (relayName?.takeIf { it.isNotBlank() } ?: relayUrl).uppercase(),
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.02.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NostrordColors.BackgroundDark)
        )

        Column(modifier = Modifier.weight(1f)) {
            val listState = rememberLazyListState()

            if (groups.isEmpty() && onAddRelay != null && relayUrl.isBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "#",
                        color = NostrordColors.TextMuted,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No groups yet",
                        color = NostrordColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Add a relay first, then you can browse and join groups or create your own.",
                        color = NostrordColors.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NostrordColors.Primary)
                            .clickable(onClick = onAddRelay)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Add a Relay",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (isLoading && groups.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                ) {
                    repeat(6) { GroupNavItemSkeleton() }
                }
            } else if (groups.isEmpty()) {
                Text(
                    text = "No groups on this relay",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            } else {
                // MY GROUPS — pinned at top, always visible
                if (myGroups.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = Spacing.sm)) {
                        SectionToggleHeader(
                            text = "MY GROUPS",
                            expanded = myGroupsExpanded,
                            topPadding = Spacing.xs,
                            onToggle = { myGroupsExpanded = !myGroupsExpanded }
                        )
                        if (myGroupsExpanded) {
                            myGroups.forEach { group ->
                                GroupItem(
                                    group = group,
                                    isActive = group.id == activeGroupId,
                                    unreadCount = unreadCounts[group.id] ?: 0,
                                    onClick = { onGroupClick(group.id, group.name) }
                                )
                            }
                        }
                    }
                }

                // OTHER GROUPS — scrollable, fills remaining space
                if (otherGroups.isNotEmpty() || searchQuery.isNotBlank()) {
                    Column(modifier = Modifier.padding(horizontal = Spacing.sm)) {
                        SectionToggleHeader(
                            text = "OTHER GROUPS",
                            expanded = otherGroupsExpanded,
                            topPadding = if (myGroups.isNotEmpty()) Spacing.md else Spacing.xs,
                            onToggle = { otherGroupsExpanded = !otherGroupsExpanded }
                        )
                        if (otherGroupsExpanded) {
                            val focusRequester = remember { FocusRequester() }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NostrordColors.BackgroundDark)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { focusRequester.requestFocus() }
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Search,
                                        contentDescription = null,
                                        tint = NostrordColors.TextMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "Filter…",
                                                color = NostrordColors.TextMuted,
                                                fontSize = 12.sp
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = NostrordColors.TextPrimary,
                                                fontSize = 12.sp
                                            ),
                                            cursorBrush = SolidColor(NostrordColors.Primary),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(focusRequester)
                                                .onPreviewKeyEvent { event ->
                                                    if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && searchQuery.isNotEmpty()) {
                                                        searchQuery = ""
                                                        true
                                                    } else false
                                                }
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clip(RoundedCornerShape(7.dp))
                                                .clickable { searchQuery = "" }
                                                .pointerHoverIcon(PointerIcon.Hand),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Clear,
                                                contentDescription = "Clear filter",
                                                tint = NostrordColors.TextMuted,
                                                modifier = Modifier.size(10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (otherGroupsExpanded) {
                        Box(modifier = Modifier.weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.sm)
                            ) {
                                items(otherGroups, key = { "other_${it.id}" }) { group ->
                                    GroupItem(
                                        group = group,
                                        isActive = group.id == activeGroupId,
                                        unreadCount = unreadCounts[group.id] ?: 0,
                                        onClick = { onGroupClick(group.id, group.name) }
                                    )
                                }

                                if (otherGroups.isEmpty() && searchQuery.isNotBlank()) {
                                    item(key = "no_results") {
                                        Text(
                                            text = "No groups match \"$searchQuery\"",
                                            color = NostrordColors.TextMuted,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
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
            }
        }

        if (onAddRelay == null || relayUrl.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.Surface)
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(NostrordColors.Primary)
                        .clickable(onClick = onCreateGroupClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Create Group",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(NostrordColors.SurfaceVariant)
                        .clickable(onClick = onJoinGroupClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Join Group",
                        color = NostrordColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionToggleHeader(
    text: String,
    expanded: Boolean,
    topPadding: androidx.compose.ui.unit.Dp = Spacing.xs,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(targetValue = if (expanded) 0f else -90f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(top = topPadding, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▼",
            color = NostrordColors.TextMuted,
            fontSize = 8.sp,
            modifier = Modifier.rotate(chevronRotation)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            color = NostrordColors.TextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.02.sp
        )
    }
}

@Composable
private fun GroupNavItemSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmerEffect()
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .height(13.dp)
                .fillMaxWidth(0.65f)
                .clip(RoundedCornerShape(3.dp))
                .shimmerEffect()
        )
    }
}

@Composable
private fun GroupItem(
    group: GroupMetadata,
    isActive: Boolean,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hasUnread = unreadCount > 0

    val backgroundColor = when {
        isActive -> NostrordColors.SurfaceVariant
        isHovered -> NostrordColors.HoverBackground
        else -> Color.Transparent
    }

    val textColor = when {
        isActive -> NostrordColors.TextPrimary
        hasUnread -> NostrordColors.TextPrimary
        isHovered -> NostrordColors.TextPrimary
        else -> NostrordColors.TextSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val groupName = group.name ?: group.id
        GroupNavIcon(group = group, size = 22.dp)

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = groupName,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (hasUnread && !isActive) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (hasUnread && !isActive) {
            Spacer(modifier = Modifier.width(4.dp))
            UnreadBadge(count = unreadCount, size = 16.dp)
        }
    }
}

@Composable
private fun GroupNavIcon(group: GroupMetadata, size: Dp) {
    val context = LocalPlatformContext.current
    val pictureUrl = group.picture
    val iconShape = RoundedCornerShape(4.dp)
    var imageState by remember(pictureUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val showImage = !pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error
    val displayName = group.name ?: group.id

    Box(
        modifier = Modifier
            .size(size)
            .clip(iconShape)
            .background(if (!showImage) generateColorFromString(group.id) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        if (!showImage) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 11.sp,
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
