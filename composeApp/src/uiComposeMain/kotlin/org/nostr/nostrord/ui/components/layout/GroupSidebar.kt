package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.channelTree
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.ManageGroupModal
import org.nostr.nostrord.ui.screens.group.components.ManageTab
import org.nostr.nostrord.ui.screens.group.components.MembersModal
import org.nostr.nostrord.ui.screens.group.isLockedChannel
import org.nostr.nostrord.ui.screens.group.rootGroupId
import org.nostr.nostrord.ui.screens.home.RelayHeaderIcon
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.Hsl
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Second column when a group is open (prototype ChannelsSidebar group mode): the
 * gradient banner (ROOT group identity, Discord's "server") and below it the
 * per-channel rows (Chat/Threads/Members/Manage act on the OPEN channel) plus the
 * channel list (the root and its subgroup subtree). Opening any channel keeps this
 * sidebar anchored on the root. Mirrors the web web/GroupSidebar.
 */
@Composable
fun GroupSidebar(
    route: GroupRoute,
    onNavigateGroup: (GroupRoute) -> Unit,
    onNavigateRelay: (String) -> Unit = {},
    onNavigateHome: () -> Unit = {},
) {
    val vm = viewModel(key = route.groupId) { GroupViewModel(AppModule.nostrRepository, route.groupId) }
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val childrenByParent by vm.childrenByParent.collectAsState()
    val groupMembers by vm.groupMembers.collectAsState()
    val groupAdmins by vm.groupAdmins.collectAsState()
    val joinedGroupsByRelay by vm.joinedGroupsByRelay.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()

    val relayGroups = groupsByRelay[route.relayUrl].orEmpty()
    val metaById = relayGroups.associateBy { it.id }
    val meta = metaById[route.groupId]
    val currentUserPubkey = remember { vm.getPublicKey() }
    val isAdmin = currentUserPubkey != null && currentUserPubkey in groupAdmins[route.groupId].orEmpty()
    val memberCount = groupMembers[route.groupId].orEmpty().size
    // Discord-style channel model: the sidebar anchors on the ROOT of the open channel's
    // subgroup tree (the "server"); the open channel only drives the chat pane + the
    // per-channel rows (Chat/Threads/Members/Manage — membership is per subgroup).
    val rootId = rootGroupId(route.groupId) { metaById[it]?.parent }
    val rootMeta = metaById[rootId]
    val rootName = rootMeta?.name ?: rootId
    val isRootAdmin = currentUserPubkey != null && currentUserPubkey in groupAdmins[rootId].orEmpty()
    // Subgroup channels only: the root's own chat is the "General" row above the list,
    // so listing the root again would duplicate the banner identity.
    val channels = channelTree(rootId, childrenByParent, metaById).drop(1)
    val joinedHere = joinedGroupsByRelay[route.relayUrl.normalizeRelayUrl()].orEmpty()
    // Only relays that advertise nip29:{subgroups:true} in their NIP-11 host subgroups.
    val supportsSubgroups =
        (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])
            ?.supportsSubgroups == true

    var showMembers by remember { mutableStateOf(false) }
    var showCreateSubgroup by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    // Tab the Manage modal opens on: the Members row jumps admins straight to "Members".
    var manageTab by remember { mutableStateOf(ManageTab.Info) }

    val relayHost = route.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    val relayIconUrl =
        (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])?.icon

    Column(modifier = Modifier.fillMaxSize()) {
        GroupBanner(
            seed = rootId,
            name = rootName,
            picture = rootMeta?.picture,
            relayUrl = route.relayUrl,
            relayHost = relayHost,
            relayIconUrl = relayIconUrl,
            onRelayClick = { onNavigateRelay(route.relayUrl) },
        )
        Column(
            modifier =
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        ) {
            // The root group's own chat (Discord's #general); subgroup channels are listed below.
            SidebarRow(
                icon = Icons.AutoMirrored.Filled.Chat,
                label = "General",
                active = route.view == GroupView.Chat && route.groupId == rootId,
            ) { onNavigateGroup(GroupRoute(route.relayUrl, rootId)) }
            SidebarRow(
                icon = Icons.Default.Forum,
                label = "Threads",
                active = route.view == GroupView.Threads,
            ) { onNavigateGroup(route.copy(view = GroupView.Threads, threadRootId = null)) }
            SidebarRow(
                icon = Icons.Default.People,
                label = if (memberCount > 0) "Members · $memberCount" else "Members",
            ) {
                // Admins manage members in the Manage modal; everyone else sees the roster.
                if (isAdmin) {
                    manageTab = ManageTab.Members
                    showManage = true
                } else {
                    showMembers = true
                }
            }
            if (isAdmin) {
                SidebarRow(icon = Icons.Default.Settings, label = "Manage group") {
                    manageTab = ManageTab.Info
                    showManage = true
                }
            }

            // Channels (Discord model): the root's subgroup subtree, depth-first with
            // indentation. Hidden on relays that can't host subgroups, and for non-admins
            // when there are no channels yet (admins keep the header for its add button).
            if (supportsSubgroups && (channels.isNotEmpty() || isRootAdmin)) {
                Spacer(modifier = Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "CHANNELS · ${channels.size}",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (isRootAdmin) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add channel",
                            tint = NostrordColors.TextMuted,
                            modifier =
                            Modifier
                                .size(16.dp)
                                .clip(NostrordShapes.shapeSmall)
                                .clickable { showCreateSubgroup = true },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                channels.forEach { entry ->
                    val channel = metaById[entry.id]
                    ChannelRow(
                        groupId = entry.id,
                        name = channel?.name ?: entry.id,
                        picture = channel?.picture,
                        // First-level channels sit flush; only nesting below them indents.
                        depth = entry.depth - 1,
                        locked = isLockedChannel(channel, isJoined = entry.id in joinedHere),
                        unread = unreadCounts[entry.id] ?: 0,
                        active = route.groupId == entry.id,
                    ) { onNavigateGroup(GroupRoute(route.relayUrl, entry.id)) }
                }
            }
        }
    }

    if (showMembers) {
        MembersModal(
            groupId = route.groupId,
            onDismiss = { showMembers = false },
        )
    }
    if (showManage) {
        ManageGroupModal(
            groupId = route.groupId,
            currentMetadata = meta,
            relayUrl = route.relayUrl,
            onDismiss = { showManage = false },
            onDeleted = {
                showManage = false
                onNavigateHome()
            },
            initialTab = manageTab,
            supportsSubgroups = supportsSubgroups,
        )
    }
    if (showCreateSubgroup) {
        CreateGroupModal(
            // The channel must land on the parent's relay, not whatever relay is focused.
            currentRelayUrl = route.relayUrl,
            userRelays = kind10009Relays,
            parentGroupId = rootId,
            onDismiss = { showCreateSubgroup = false },
            onGroupCreated = { relayUrl, newId, _ ->
                showCreateSubgroup = false
                onNavigateGroup(GroupRoute(relayUrl, newId))
            },
        )
    }
}

/**
 * Gradient identity banner (prototype GroupBanner): same hue pair as the avatar, darkened.
 * Mirrors the web `.group-side-banner`: group avatar + name, with a tappable relay line
 * (icon + host) below the name that opens the relay page. Only the relay line is clickable;
 * the banner itself is not (parity with web).
 */
@Composable
private fun GroupBanner(
    seed: String,
    name: String,
    picture: String?,
    relayUrl: String,
    relayHost: String,
    relayIconUrl: String?,
    onRelayClick: () -> Unit,
) {
    val banner = remember(seed) { AvatarGradients.banner(seed) }
    // White text over a colored gradient needs the same drop shadow the web banner uses.
    val textShadow = Shadow(color = Color.Black.copy(alpha = 0.4f), offset = Offset(0f, 1f), blurRadius = 2f)
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .drawBehind {
                // 135deg diagonal, top-left to bottom-right (CSS linear-gradient(135deg, …)).
                drawRect(
                    brush =
                    Brush.linearGradient(
                        colors = listOf(banner.start.toColor(), banner.end.toColor()),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                // Bottom scrim for legible white text.
                drawRect(
                    brush =
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        startY = size.height - 56.dp.toPx(),
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - 56.dp.toPx()),
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OptimizedSmallAvatar(
                imageUrl = picture,
                identifier = seed,
                displayName = name,
                size = 24.dp,
                // Web group avatars are rounded squares at 25% radius (.avatar-stack.group).
                shape = RoundedCornerShape(percent = 25),
                isGroup = true,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = LocalTextStyle.current.copy(shadow = textShadow),
                )
                if (relayHost.isNotBlank()) {
                    Row(
                        modifier =
                        Modifier
                            .clip(NostrordShapes.shapeSmall)
                            .clickable(onClick = onRelayClick)
                            .padding(end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        RelayHeaderIcon(
                            relayUrl = relayUrl,
                            iconUrl = relayIconUrl,
                            label = relayHost,
                            size = 13.dp,
                            // Web .group-side-banner-relay-icon: small rounded square, not a circle.
                            cornerRadius = 3.dp,
                        )
                        Text(
                            relayHost,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(shadow = textShadow),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(
    icon: ImageVector,
    label: String,
    muted: Boolean = false,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isHovered || active
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (highlighted) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) {
                NostrordColors.TextPrimary
            } else if (muted) {
                NostrordColors.TextMuted
            } else {
                NostrordColors.TextSecondary
            },
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            color =
            when {
                highlighted -> NostrordColors.TextPrimary
                muted -> NostrordColors.TextMuted
                else -> NostrordColors.TextSecondary
            },
            fontSize = if (muted) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChannelRow(
    groupId: String,
    name: String,
    picture: String?,
    depth: Int,
    locked: Boolean,
    unread: Int,
    active: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = isHovered || active
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            // Nesting indent, capped so deep foreign trees never crush the label.
            .padding(start = Spacing.md * minOf(depth, 3))
            .clip(NostrordShapes.shapeMedium)
            .background(if (highlighted) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs + Spacing.xxs),
    ) {
        OptimizedSmallAvatar(
            imageUrl = picture,
            identifier = groupId,
            displayName = name,
            size = 20.dp,
            shape = NostrordShapes.shapeSmall,
            isGroup = true,
        )
        Text(
            name,
            color = if (highlighted) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (locked) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Members only",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(12.dp),
            )
        }
        if (unread > 0) {
            Box(
                modifier =
                Modifier
                    .clip(CircleShape)
                    .background(NostrordColors.BadgeBackground)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    if (unread > 99) "99+" else "$unread",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun Hsl.toColor(): Color = Color.hsl(hue.toFloat(), saturation / 100f, lightness / 100f)
