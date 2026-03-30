package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.compose.AsyncImagePainter
import coil3.request.crossfade
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.Jdenticon
import org.nostr.nostrord.ui.components.loading.MemberSkeleton
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Enhanced member sidebar with online/offline status, avatars, role badges, and search.
 */
@Composable
fun MemberSidebar(
    members: List<MemberInfo>,
    recentlyActiveMembers: Set<String> = emptySet(),
    isLoading: Boolean = false,
    onMemberClick: (MemberInfo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }

    // Filter members based on search query (name, pubkey hex, or npub)
    val filteredMembers = remember(members, searchQuery) {
        if (searchQuery.isBlank()) {
            members
        } else {
            val query = searchQuery.lowercase()
            members.filter { member ->
                member.displayName.lowercase().contains(query) ||
                member.pubkey.lowercase().contains(query) ||
                Nip19.encodeNpub(member.pubkey).lowercase().contains(query)
            }
        }
    }

    val onlineMembers = filteredMembers.filter { it.pubkey in recentlyActiveMembers }
    val offlineMembers = filteredMembers.filter { it.pubkey !in recentlyActiveMembers }

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

        // Search field
        MemberSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Skeleton placeholder while members are loading
                if (isLoading) {
                    items(8) {
                        MemberSkeleton()
                    }
                }

                // Online section
                if (!isLoading && onlineMembers.isNotEmpty()) {
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
                if (!isLoading && offlineMembers.isNotEmpty()) {
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
                if (!isLoading && onlineMembers.isEmpty() && offlineMembers.isEmpty() && filteredMembers.isNotEmpty()) {
                    item {
                        MemberSectionHeader(
                            title = "MEMBERS",
                            count = filteredMembers.size,
                            expanded = true,
                            onToggle = {}
                        )
                    }
                    items(filteredMembers) { member ->
                        MemberItem(
                            member = member,
                            isOnline = null,
                            onClick = { onMemberClick(member) }
                        )
                    }
                }

                // No results message when search has no matches
                if (!isLoading && filteredMembers.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No members found",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
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

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val showPlaceholder = member.picture.isNullOrBlank() ||
            imageState is AsyncImagePainter.State.Loading ||
            imageState is AsyncImagePainter.State.Error

        // Show Jdenticon when no picture, loading, or error
        if (showPlaceholder) {
            Jdenticon(
                value = member.pubkey,
                size = size,
                modifier = Modifier.graphicsLayer { this.alpha = alpha }
            )
        }

        // Only attempt to load image if URL is provided and not in error state
        if (!member.picture.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(member.picture!!)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = member.displayName,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                alpha = alpha,
                onState = { imageState = it }
            )
        }
    }
}

/**
 * Search field for filtering members by name or pubkey.
 */
@Composable
private fun MemberSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.BackgroundDark)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search members...",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(NostrordColors.Primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            if (event.key == Key.Escape && event.type == KeyEventType.KeyUp && query.isNotEmpty()) {
                                onQueryChange("")
                                true
                            } else false
                        }
                )
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
