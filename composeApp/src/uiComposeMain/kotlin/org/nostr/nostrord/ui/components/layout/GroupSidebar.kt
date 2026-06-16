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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.EditGroupModal
import org.nostr.nostrord.ui.screens.group.components.GroupInfoModal
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.Hsl
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * Second column when a group is open (prototype ChannelsSidebar group mode): the
 * gradient banner (group identity, opens the info modal) and the group tree below
 * it (Members row, the subgroups section, parent backlink). Rows the prototype
 * backs with mock-only features (Threads / Pinned / Media panels) arrive with
 * those features. Mirrors the web web/GroupSidebar.
 */
@Composable
fun GroupSidebar(
    route: GroupRoute,
    onNavigateGroup: (GroupRoute) -> Unit,
    onNavigateHome: () -> Unit = {},
) {
    val vm = viewModel(key = route.groupId) { GroupViewModel(AppModule.nostrRepository, route.groupId) }
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val childrenByParent by vm.childrenByParent.collectAsState()
    val groupMembers by vm.groupMembers.collectAsState()
    val groupAdmins by vm.groupAdmins.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()

    val relayGroups = groupsByRelay[route.relayUrl].orEmpty()
    val meta = relayGroups.firstOrNull { it.id == route.groupId }
    val name = meta?.name ?: route.groupId
    val currentUserPubkey = remember { vm.getPublicKey() }
    val isAdmin = currentUserPubkey != null && currentUserPubkey in groupAdmins[route.groupId].orEmpty()
    val memberCount = groupMembers[route.groupId].orEmpty().size
    val subgroupIds = childrenByParent[route.groupId].orEmpty()
    // Only relays that advertise nip29:{subgroups:true} in their NIP-11 host subgroups.
    val supportsSubgroups =
        (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])
            ?.supportsSubgroups == true
    val parent = meta?.parent?.let { pid -> relayGroups.firstOrNull { it.id == pid } }

    var showInfo by remember { mutableStateOf(false) }
    var showCreateSubgroup by remember { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        GroupBanner(
            seed = route.groupId,
            name = name,
            picture = meta?.picture,
            onClick = { showInfo = true },
        )
        Column(
            modifier =
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.md),
        ) {
            if (parent != null) {
                SidebarRow(
                    icon = Icons.Default.KeyboardArrowLeft,
                    label = parent.name ?: parent.id,
                    muted = true,
                ) { onNavigateGroup(GroupRoute(route.relayUrl, parent.id)) }
            }
            SidebarRow(
                icon = Icons.Default.People,
                label = if (memberCount > 0) "Members · $memberCount" else "Members",
            ) { showInfo = true }
            if (isAdmin) {
                SidebarRow(icon = Icons.Default.Settings, label = "Manage group") {
                    showManage = true
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))
            if (!supportsSubgroups) {
                // No "SUBGROUPS · 0" header on relays that can't host subgroups.
                Text(
                    "This group doesn't support subgroups.",
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SUBGROUPS · ${subgroupIds.size}",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (isAdmin) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add subgroup",
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
                subgroupIds.forEach { subId ->
                    val sub = relayGroups.firstOrNull { it.id == subId }
                    SubgroupRow(
                        groupId = subId,
                        name = sub?.name ?: subId,
                        picture = sub?.picture,
                        unread = unreadCounts[subId] ?: 0,
                    ) { onNavigateGroup(GroupRoute(route.relayUrl, subId)) }
                }
                if (subgroupIds.isEmpty()) {
                    Text(
                        "No subgroups.",
                        color = NostrordColors.TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    )
                }
            }
        }
    }

    if (showInfo) {
        GroupInfoModal(
            groupId = route.groupId,
            groupName = meta?.name,
            groupMetadata = meta,
            userMetadata = userMetadata,
            onDismiss = { showInfo = false },
        )
    }
    if (showManage) {
        EditGroupModal(
            groupId = route.groupId,
            currentMetadata = meta,
            onDismiss = { showManage = false },
            onGroupUpdated = { showManage = false },
            onDeleted = {
                showManage = false
                onNavigateHome()
            },
            showSubgroupControls = supportsSubgroups,
        )
    }
    if (showCreateSubgroup) {
        CreateGroupModal(
            currentRelayUrl = currentRelayUrl,
            userRelays = kind10009Relays,
            parentGroupId = route.groupId,
            onDismiss = { showCreateSubgroup = false },
            onGroupCreated = { relayUrl, newId, _ ->
                showCreateSubgroup = false
                onNavigateGroup(GroupRoute(relayUrl, newId))
            },
        )
    }
}

/** Gradient identity banner (prototype GroupBanner): same hue pair as the avatar, darkened. */
@Composable
private fun GroupBanner(
    seed: String,
    name: String,
    picture: String?,
    onClick: () -> Unit,
) {
    val banner = remember(seed) { AvatarGradients.banner(seed) }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable(onClick = onClick)
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm + Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OptimizedSmallAvatar(
                imageUrl = picture,
                identifier = seed,
                displayName = name,
                size = 24.dp,
                shape = NostrordShapes.shapeSmall,
                isGroup = true,
            )
            Text(
                name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SidebarRow(
    icon: ImageVector,
    label: String,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (muted) NostrordColors.TextMuted else NostrordColors.TextSecondary,
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            color =
            when {
                isHovered -> NostrordColors.TextPrimary
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
private fun SubgroupRow(
    groupId: String,
    name: String,
    picture: String?,
    unread: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
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
            color = if (isHovered) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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
