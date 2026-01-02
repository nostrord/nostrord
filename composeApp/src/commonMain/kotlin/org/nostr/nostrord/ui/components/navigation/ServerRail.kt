package org.nostr.nostrord.ui.components.navigation

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.avatars.OptimizedServerIcon
import org.nostr.nostrord.ui.components.avatars.OptimizedUserAvatar
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Server Rail - vertical column of group/server icons.
 *
 * This component is ALWAYS visible on desktop, providing persistent
 * navigation between groups. It sits at the left edge of the screen.
 *
 * Layout:
 * - 72dp wide
 * - Home/Explore button at top
 * - Joined groups as icons
 * - Add button
 * - User avatar at bottom
 */
@Composable
fun ServerRail(
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    activeGroupId: String?,
    unreadCounts: Map<String, Int> = emptyMap(),
    onHomeClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    userAvatarUrl: String? = null,
    userDisplayName: String? = null,
    userPubkey: String? = null,
    onUserClick: () -> Unit = {},
    isProfileActive: Boolean = false
) {
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(Spacing.serverRailWidth)
            .background(NostrordColors.BackgroundDark)
            .padding(vertical = Spacing.sm)
            .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // Home/Explore button
            item(key = "home") {
                val isHomeActive = activeGroupId == null && !isProfileActive
                val exploreInteractionSource = remember { MutableInteractionSource() }
                val isExploreHovered by exploreInteractionSource.collectIsHoveredAsState()

                ServerRailItem(
                    isActive = isHomeActive,
                    hasUnread = false,
                    onClick = onHomeClick,
                    tooltip = "Explore"
                ) {
                    Box(
                        modifier = Modifier
                            .size(Spacing.serverIconSize)
                            .hoverable(exploreInteractionSource)
                            .background(
                                color = when {
                                    isHomeActive -> NostrordColors.Primary
                                    isExploreHovered -> NostrordColors.Primary
                                    else -> NostrordColors.SurfaceVariant
                                },
                                shape = RoundedCornerShape(
                                    if (isHomeActive || isExploreHovered)
                                        NostrordShapes.serverIconActive
                                    else
                                        NostrordShapes.serverIconDefault
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "Home",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Joined groups
            items(
                items = joinedGroups.toList(),
                key = { it }
            ) { groupId ->
                val group = groups.find { it.id == groupId }
                val groupName = group?.name ?: groupId
                val isActive = activeGroupId == groupId
                val unreadCount = unreadCounts[groupId] ?: 0

                ServerRailItem(
                    isActive = isActive,
                    hasUnread = unreadCount > 0,
                    unreadCount = unreadCount,
                    onClick = { onGroupClick(groupId, group?.name) },
                    tooltip = groupName
                ) {
                    ServerIcon(
                        groupId = groupId,
                        groupName = groupName,
                        pictureUrl = group?.picture,
                        isActive = isActive
                    )
                }
            }

            // Add button
            item(key = "add") {
                Spacer(modifier = Modifier.height(Spacing.xs))
                ServerRailItem(
                    isActive = false,
                    hasUnread = false,
                    onClick = onAddClick,
                    showIndicator = false,
                    tooltip = "Create a group"
                ) {
                    Box(
                        modifier = Modifier
                            .size(Spacing.serverIconSize)
                            .background(
                                NostrordColors.SurfaceVariant,
                                RoundedCornerShape(NostrordShapes.serverIconDefault)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Server",
                            tint = NostrordColors.Success,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // User avatar at bottom
        Spacer(modifier = Modifier.height(Spacing.sm))
        UserAvatar(
            avatarUrl = userAvatarUrl,
            displayName = userDisplayName,
            pubkey = userPubkey,
            onClick = onUserClick,
            tooltip = userDisplayName ?: "You",
            isActive = isProfileActive
        )
    }
}

/**
 * Individual item in the server rail with pill indicator and hover states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerRailItem(
    isActive: Boolean,
    hasUnread: Boolean,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    showIndicator: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Animate indicator height based on state
    val indicatorHeight by animateDpAsState(
        targetValue = when {
            isActive -> Spacing.activeIndicatorHeight
            isHovered -> Spacing.hoverIndicatorHeight
            hasUnread -> Spacing.unreadDotSize
            else -> 0.dp
        },
        animationSpec = NostrordAnimation.indicatorSpec()
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.serverIconSize + Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left pill indicator
        if (showIndicator) {
            Box(
                modifier = Modifier
                    .width(Spacing.activeIndicatorWidth)
                    .height(indicatorHeight)
                    .background(
                        if (indicatorHeight > 0.dp) Color.White else Color.Transparent,
                        RoundedCornerShape(
                            topEnd = Spacing.activeIndicatorWidth,
                            bottomEnd = Spacing.activeIndicatorWidth
                        )
                    )
            )
        } else {
            Spacer(modifier = Modifier.width(Spacing.activeIndicatorWidth))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Server icon with badge and tooltip
        val iconContent: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(Spacing.serverIconSize)
                    .hoverable(interactionSource)
                    .clickable(onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                content()

                // Unread badge
                if (hasUnread && unreadCount > 0) {
                    UnreadBadge(
                        count = unreadCount,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp),
                        size = Spacing.badgeSize
                    )
                }
            }
        }

        if (tooltip != null) {
            TooltipBox(
                positionProvider = RightSideTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip(
                        containerColor = NostrordColors.Surface,
                        contentColor = NostrordColors.TextPrimary
                    ) {
                        Text(tooltip)
                    }
                },
                state = rememberTooltipState()
            ) {
                iconContent()
            }
        } else {
            iconContent()
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Server/group icon with morphing corner radius on hover/active.
 * Uses OptimizedServerIcon for crisp rendering at small sizes.
 */
@Composable
private fun ServerIcon(
    groupId: String,
    groupName: String,
    pictureUrl: String?,
    isActive: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Animate corner radius: 16dp -> 12dp on hover/active
    val cornerRadius by animateDpAsState(
        targetValue = when {
            isActive || isHovered -> NostrordShapes.serverIconActive
            else -> NostrordShapes.serverIconDefault
        },
        animationSpec = NostrordAnimation.standardSpec()
    )

    Box(
        modifier = Modifier
            .size(Spacing.serverIconSize)
            .hoverable(interactionSource),
        contentAlignment = Alignment.Center
    ) {
        OptimizedServerIcon(
            imageUrl = pictureUrl,
            groupId = groupId,
            groupName = groupName,
            size = Spacing.serverIconSize,
            cornerRadius = cornerRadius
        )
    }
}

/**
 * User avatar displayed at the bottom of the server rail.
 * Uses OptimizedUserAvatar for crisp rendering at small sizes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserAvatar(
    avatarUrl: String?,
    displayName: String?,
    pubkey: String?,
    onClick: () -> Unit,
    tooltip: String = "You",
    isActive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Animate indicator height based on state
    val indicatorHeight by animateDpAsState(
        targetValue = when {
            isActive -> Spacing.activeIndicatorHeight
            isHovered -> Spacing.hoverIndicatorHeight
            else -> 0.dp
        },
        animationSpec = NostrordAnimation.indicatorSpec()
    )

    val avatarContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.serverIconSize + Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left pill indicator
            Box(
                modifier = Modifier
                    .width(Spacing.activeIndicatorWidth)
                    .height(indicatorHeight)
                    .background(
                        if (indicatorHeight > 0.dp) Color.White else Color.Transparent,
                        RoundedCornerShape(
                            topEnd = Spacing.activeIndicatorWidth,
                            bottomEnd = Spacing.activeIndicatorWidth
                        )
                    )
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(Spacing.serverIconSize)
                    .hoverable(interactionSource)
                    .clickable(onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                OptimizedUserAvatar(
                    imageUrl = avatarUrl,
                    pubkey = pubkey ?: "unknown",
                    displayName = displayName,
                    size = Spacing.serverIconSize
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    TooltipBox(
        positionProvider = RightSideTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = NostrordColors.Surface,
                contentColor = NostrordColors.TextPrimary
            ) {
                Text(tooltip)
            }
        },
        state = rememberTooltipState()
    ) {
        avatarContent()
    }
}

/**
 * Custom tooltip position provider that places tooltips to the right of the anchor.
 */
private class RightSideTooltipPositionProvider(
    private val spacing: Int = 8
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        // Position to the right of the anchor, vertically centered
        val x = anchorBounds.right + spacing
        val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        return IntOffset(x, y)
    }
}
