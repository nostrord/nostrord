package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
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
import org.nostr.nostrord.storage.SecureStorage
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
    /** childrenByParent from NostrRepository — used to render the "has subgroups" hint. */
    childrenByParent: Map<String, Set<String>> = emptyMap(),
    /**
     * Subgroup ids whose parent-claim is unverified per NIP-29 §"Parent consent"
     * (parent doesn't list them back and no admin attestation). Rendered nested
     * under their declared parent but visually flagged.
     */
    unverifiedChildren: Set<String> = emptySet(),
    /** Joined group ids with no `kind:39000` on this relay — stale `kind:10009` pins. */
    orphanedJoinedIds: Set<String> = emptySet(),
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit = {},
    onAddRelay: (() -> Unit)? = null,
    onForgetOrphan: (groupId: String) -> Unit = {},
    showRelayTitle: Boolean = true,
    /** True when this relay uses lazy fetch mode — full group list is fetched on-demand. */
    isGroupFetchLazy: Boolean = false,
    /** True when the full group list has already been fetched this session (LAZY mode only). */
    hasFullGroupListBeenFetched: Boolean = true,
    /** Called when OTHER GROUPS first expands on a lazy relay that hasn't fetched the full list yet. */
    onRequestFullGroupList: () -> Unit = {},
    /** True when the relay WebSocket is connected — used to re-trigger the fetch once connection is ready. */
    isRelayConnected: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Reset when relay changes
    var searchQuery by remember(relayUrl) { mutableStateOf("") }

    // Per-parent expansion state for the "Unconfirmed claims" subheader.
    // Missing key = collapsed (default) — admin sees only the confirmed tree
    // until they explicitly reveal the disputed claims.
    val expandedUnverified = remember(relayUrl) { mutableStateMapOf<String, Boolean>() }

    // Groups that appear under "My Groups": joined groups plus any descendants
    // of joined groups (even if the user hasn't joined them yet), so the hierarchy
    // stays intact — otherwise children of a joined parent would be orphaned in
    // "Other Groups". Descendants without effective access are rendered muted
    // via `notJoined`. Unverified descendants are included but visually demoted.
    val myGroupsIds = remember(groups, joinedGroupIds, childrenByParent) {
        val result = mutableSetOf<String>()
        val stack = ArrayDeque<String>().apply { joinedGroupIds.forEach { addLast(it) } }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!result.add(id)) continue
            childrenByParent[id].orEmpty().forEach { stack.addLast(it) }
        }
        result
    }
    val myGroups = remember(groups, myGroupsIds, childrenByParent, unverifiedChildren, expandedUnverified.toMap()) {
        flattenHierarchy(
            groups.filter { it.id in myGroupsIds },
            childrenByParent,
            unverifiedChildren,
            expandedUnverified
        )
    }
    val otherGroups = remember(groups, myGroupsIds, searchQuery, childrenByParent, unverifiedChildren, expandedUnverified.toMap()) {
        val base = groups.filter { it.id !in myGroupsIds }
        val filtered = if (searchQuery.isBlank()) base
        else base.filter { it.name?.contains(searchQuery, ignoreCase = true) == true || it.id.contains(searchQuery, ignoreCase = true) }
        flattenHierarchy(filtered, childrenByParent, unverifiedChildren, expandedUnverified)
    }

    var myGroupsExpanded by remember(relayUrl) { mutableStateOf(true) }
    var otherGroupsExpanded by remember(relayUrl) {
        mutableStateOf(SecureStorage.getBooleanPref("sidebar_other_expanded_$relayUrl", default = true))
    }

    // A stale persisted full-list timestamp can make hasFullGroupListBeenFetched=true even when
    // the current session has no data. Guard against that by also checking otherGroups.isEmpty()
    // so an empty panel always triggers a fetch regardless of the cached timestamp.
    val needsFullFetch = !hasFullGroupListBeenFetched || otherGroups.isEmpty()

    // On a LAZY relay, trigger the full group list fetch when OTHER GROUPS is expanded and
    // the connection is ready. isRelayConnected is included as a key so that if the sidebar
    // renders before the WebSocket handshake completes, the effect re-fires once connected.
    LaunchedEffect(otherGroupsExpanded, isGroupFetchLazy, needsFullFetch, isRelayConnected) {
        if (otherGroupsExpanded && isGroupFetchLazy && needsFullFetch && isRelayConnected) {
            onRequestFullGroupList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(NostrordColors.Surface)
    ) {
        if (showRelayTitle) {
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
        }

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
            } else if (groups.isEmpty() && orphanedJoinedIds.isEmpty()) {
                Text(
                    text = "No groups on this relay",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            } else {
                val hasOtherGroups = otherGroups.isNotEmpty() || searchQuery.isNotBlank() || isGroupFetchLazy
                Column(modifier = Modifier.fillMaxSize()) {
                // MY GROUPS — weight gives verticalScroll a bounded viewport; OTHER GROUPS header always visible
                if (myGroups.isNotEmpty() || orphanedJoinedIds.isNotEmpty()) {
                    val myGroupsScrollState = remember(relayUrl) { ScrollState(0) }
                    val expandedWithOtherGroups = hasOtherGroups && otherGroupsExpanded
                    Column(modifier = if (expandedWithOtherGroups)
                        Modifier.padding(horizontal = Spacing.sm)
                    else
                        Modifier.padding(horizontal = Spacing.sm).weight(1f)
                    ) {
                        SectionToggleHeader(
                            text = "MY GROUPS",
                            expanded = myGroupsExpanded,
                            topPadding = Spacing.xs,
                            onToggle = { myGroupsExpanded = !myGroupsExpanded }
                        )
                        if (myGroupsExpanded) {
                            Box(modifier = if (expandedWithOtherGroups)
                                Modifier.heightIn(max = 240.dp)
                            else
                                Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.verticalScroll(myGroupsScrollState)) {
                                    myGroups.forEach { item ->
                                        when (item) {
                                            is SidebarItem.Group -> GroupItem(
                                                group = item.group,
                                                isActive = item.group.id == activeGroupId,
                                                unreadCount = unreadCounts[item.group.id] ?: 0,
                                                childCount = childrenByParent[item.group.id]?.size ?: 0,
                                                notJoined = item.group.id !in joinedGroupIds,
                                                unverified = item.unverified,
                                                depth = item.depth,
                                                onClick = { onGroupClick(item.group.id, item.group.name) }
                                            )
                                            is SidebarItem.UnverifiedHeader -> UnverifiedClaimsHeader(
                                                count = item.count,
                                                expanded = expandedUnverified[item.parentId] == true,
                                                depth = item.depth,
                                                onToggle = {
                                                    expandedUnverified[item.parentId] =
                                                        !(expandedUnverified[item.parentId] ?: false)
                                                }
                                            )
                                        }
                                    }
                                    orphanedJoinedIds.forEach { orphanId ->
                                        OrphanedGroupItem(
                                            groupId = orphanId,
                                            onForget = { onForgetOrphan(orphanId) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // OTHER GROUPS — scrollable, fills remaining space
                if (otherGroups.isNotEmpty() || searchQuery.isNotBlank() || isGroupFetchLazy) {
                    Column(modifier = Modifier.padding(horizontal = Spacing.sm)) {
                        SectionToggleHeader(
                            text = "OTHER GROUPS",
                            expanded = otherGroupsExpanded,
                            topPadding = if (myGroups.isNotEmpty()) Spacing.md else Spacing.xs,
                            onToggle = {
                                val next = !otherGroupsExpanded
                                otherGroupsExpanded = next
                                SecureStorage.saveBooleanPref("sidebar_other_expanded_$relayUrl", next)
                                // Trigger immediately rather than waiting for the LaunchedEffect
                                // below to recompose with the new otherGroupsExpanded value.
                                if (next && isGroupFetchLazy && needsFullFetch) {
                                    onRequestFullGroupList()
                                }
                            }
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
                                items(
                                    otherGroups,
                                    key = { item ->
                                        when (item) {
                                            is SidebarItem.Group -> "other_${item.group.id}"
                                            is SidebarItem.UnverifiedHeader -> "other_unverified_${item.parentId}"
                                        }
                                    }
                                ) { item ->
                                    when (item) {
                                        is SidebarItem.Group -> GroupItem(
                                            group = item.group,
                                            isActive = item.group.id == activeGroupId,
                                            unreadCount = unreadCounts[item.group.id] ?: 0,
                                            childCount = childrenByParent[item.group.id]?.size ?: 0,
                                            unverified = item.unverified,
                                            depth = item.depth,
                                            onClick = { onGroupClick(item.group.id, item.group.name) }
                                        )
                                        is SidebarItem.UnverifiedHeader -> UnverifiedClaimsHeader(
                                            count = item.count,
                                            expanded = expandedUnverified[item.parentId] == true,
                                            depth = item.depth,
                                            onToggle = {
                                                expandedUnverified[item.parentId] =
                                                    !(expandedUnverified[item.parentId] ?: false)
                                            }
                                        )
                                    }
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

                                if (otherGroups.isEmpty() && isGroupFetchLazy) {
                                    item(key = "lazy_placeholder") {
                                        Text(
                                            text = if (isLoading) "Loading groups…" else "No other groups",
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

/**
 * Collapsible subheader nested under a parent group that groups its unverified
 * child-claims. Collapsed by default — admins see only confirmed children until
 * they open this row to review disputed claims.
 */
@Composable
private fun UnverifiedClaimsHeader(
    count: Int,
    expanded: Boolean,
    depth: Int,
    onToggle: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val chevronRotation by animateFloatAsState(targetValue = if (expanded) 0f else -90f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(
                start = (10 + depth.coerceAtMost(3) * 14).dp,
                end = 10.dp,
                top = 6.dp,
                bottom = 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▼",
            color = NostrordColors.TextMuted,
            fontSize = 8.sp,
            modifier = Modifier.rotate(chevronRotation)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Unconfirmed claims ($count)",
            color = NostrordColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.02.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupItem(
    group: GroupMetadata,
    isActive: Boolean,
    unreadCount: Int,
    childCount: Int = 0,
    notJoined: Boolean = false,
    unverified: Boolean = false,
    depth: Int = 0,
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
        // Unverified subgroups are greyed even when active/hovered so the
        // "claim is not confirmed" hint stays visible while the user reads it.
        unverified -> NostrordColors.TextMuted
        isActive -> NostrordColors.TextPrimary
        hasUnread -> NostrordColors.TextPrimary
        isHovered -> NostrordColors.TextPrimary
        notJoined -> NostrordColors.TextMuted
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
            // Indent caps at depth 3 so sidebar doesn't overflow horizontally.
            // Product decision, not a protocol rule — the tree itself keeps rendering
            // as deep as the data goes; only the visual indent stops growing.
            .padding(start = (10 + depth.coerceAtMost(3) * 14).dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val groupName = group.name ?: group.id
        if (depth > 0) {
            Text(
                text = "›",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        GroupNavIcon(group = group, size = 22.dp)

        Spacer(modifier = Modifier.width(6.dp))

        Text(
            text = groupName,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (hasUnread && !isActive) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (unverified) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (unverified) {
            Spacer(modifier = Modifier.width(4.dp))
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above
                ),
                tooltip = {
                    PlainTooltip(
                        containerColor = NostrordColors.Surface,
                        contentColor = NostrordColors.TextPrimary
                    ) {
                        Text("Unverified subgroup — this group claims a parent that hasn't listed it back.")
                    }
                },
                state = rememberTooltipState()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Unverified subgroup",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        if (childCount > 0) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "› $childCount",
                color = NostrordColors.TextMuted,
                fontSize = 11.sp
            )
        }

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

@Composable
private fun OrphanedGroupItem(
    groupId: String,
    onForget: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(NostrordColors.BackgroundDark),
            contentAlignment = Alignment.Center
        ) {
            Text("?", color = NostrordColors.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Unavailable group",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = groupId.take(12) + if (groupId.length > 12) "…" else "",
                color = NostrordColors.TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { showConfirm = true }
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove from list",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Remove unavailable group?") },
            text = {
                Text("This group no longer exists on the relay. Removing it updates your pinned list (kind:10009) so it stops appearing across your devices.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onForget()
                }) { Text("Remove", color = NostrordColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }
}

/**
 * Item emitted by [flattenHierarchy]: either a group row or a collapsible
 * "Unconfirmed claims" subheader that groups unverified children under their
 * claimed parent.
 */
private sealed class SidebarItem {
    abstract val depth: Int
    data class Group(
        val group: GroupMetadata,
        override val depth: Int,
        val unverified: Boolean
    ) : SidebarItem()
    data class UnverifiedHeader(
        val parentId: String,
        override val depth: Int,
        val count: Int
    ) : SidebarItem()
}

/**
 * Produce a flat list ordered as a hierarchy: each parent is followed by its
 * confirmed children (DFS), then — if the parent has any unverified claims —
 * an [SidebarItem.UnverifiedHeader] row. The unverified children themselves are
 * only emitted when the header is expanded in [expandedUnverified].
 *
 * Unverified groups are rendered as leaves — their own descendants are not
 * visited since the parent link is disputed.
 */
private fun flattenHierarchy(
    list: List<GroupMetadata>,
    childrenByParent: Map<String, Set<String>> = emptyMap(),
    unverifiedChildren: Set<String> = emptySet(),
    expandedUnverified: Map<String, Boolean> = emptyMap()
): List<SidebarItem> {
    if (list.isEmpty()) return emptyList()
    val byId = list.associateBy { it.id }
    // A group is only nested if its parent is also in the list; otherwise it must
    // appear as a root (e.g. user left the parent group).
    val nestedChildIds = childrenByParent
        .filter { (parentId, _) -> parentId in byId }
        .values.flatten().toSet()
    val roots = list.filter { it.id !in nestedChildIds }
    val out = mutableListOf<SidebarItem>()
    fun visit(g: GroupMetadata, depth: Int) {
        out += SidebarItem.Group(g, depth, g.id in unverifiedChildren)
        val kids = childrenByParent[g.id].orEmpty().mapNotNull { byId[it] }
        val (unverified, confirmed) = kids.partition { it.id in unverifiedChildren }
        confirmed.forEach { visit(it, depth + 1) }
        if (unverified.isNotEmpty()) {
            out += SidebarItem.UnverifiedHeader(g.id, depth + 1, unverified.size)
            if (expandedUnverified[g.id] == true) {
                unverified.forEach {
                    out += SidebarItem.Group(it, depth + 2, unverified = true)
                }
            }
        }
    }
    roots.forEach { visit(it, 0) }
    return out
}
