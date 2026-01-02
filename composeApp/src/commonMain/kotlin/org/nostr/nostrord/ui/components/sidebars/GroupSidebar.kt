package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Channel sidebar for group screens.
 *
 * Layout:
 * - 240dp fixed width
 * - Group name header (48dp)
 * - Section headers (uppercase, 12sp)
 * - Channel items (32dp height, proper hover states)
 */
@Composable
fun GroupSidebar(
    groupName: String?,
    selectedId: String,
    onSelect: (String) -> Unit,
    unreadChannels: Set<String> = emptySet(),
    unreadCounts: Map<String, Int> = emptyMap()
) {
    val channels = listOf("general")

    Column(
        modifier = Modifier
            .width(Spacing.channelSidebarWidth)
            .fillMaxHeight()
            .background(NostrordColors.Surface)
    ) {
        // Group header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.headerHeight)
                .background(NostrordColors.BackgroundDark)
                .padding(horizontal = Spacing.lg),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = groupName ?: "Unknown Group",
                style = NostrordTypography.ServerHeader,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = Spacing.md, start = Spacing.sm, end = Spacing.sm)
            ) {
                item {
                    SectionHeader(title = "CHANNELS")
                }

                items(channels) { channel ->
                    ChannelItem(
                        name = channel,
                        isSelected = selectedId == channel,
                        hasUnread = unreadChannels.contains(channel),
                        unreadCount = unreadCounts[channel] ?: 0,
                        onClick = { onSelect(channel) }
                    )
                }
            }

            VerticalScrollbarWrapper(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

/**
 * Section header - uppercase label for channel groups.
 */
@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Spacing.sm,
                vertical = Spacing.xs
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = NostrordTypography.SectionHeader,
            color = NostrordColors.TextMuted
        )
    }
}

/**
 * Channel list item with proper states.
 *
 * States:
 * - Default: muted text, transparent background
 * - Hover: lighter text, subtle background
 * - Selected: white text, highlighted background
 * - Unread: bold text + badge (when not selected)
 */
@Composable
private fun ChannelItem(
    name: String,
    isSelected: Boolean,
    hasUnread: Boolean = false,
    unreadCount: Int = 0,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Determine colors based on state
    val backgroundColor = when {
        isSelected -> NostrordColors.SurfaceVariant
        isHovered -> NostrordColors.HoverBackground
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected -> NostrordColors.ChannelActive
        hasUnread -> NostrordColors.ChannelUnread
        isHovered -> NostrordColors.ChannelHover
        else -> NostrordColors.ChannelInactive
    }

    val textStyle = when {
        hasUnread && !isSelected -> NostrordTypography.ChannelNameUnread
        else -> NostrordTypography.ChannelName
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.channelItemHeight)
            .clip(NostrordShapes.channelItemShape)
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.channelItemPaddingH),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hash symbol
        Text(
            text = "#",
            style = NostrordTypography.ChannelHash,
            color = textColor
        )

        Spacer(modifier = Modifier.width(Spacing.xs))

        // Channel name
        Text(
            text = name,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Unread badge
        if (hasUnread && unreadCount > 0 && !isSelected) {
            UnreadBadge(count = unreadCount)
        }
    }
}

/**
 * Unread count badge - pill with count.
 */
@Composable
private fun UnreadBadge(count: Int) {
    val displayCount = if (count > 99) "99+" else count.toString()

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = Spacing.badgeSize)
            .height(Spacing.badgeSize)
            .clip(CircleShape)
            .background(NostrordColors.Error)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayCount,
            style = NostrordTypography.Badge,
            color = Color.White
        )
    }
}
