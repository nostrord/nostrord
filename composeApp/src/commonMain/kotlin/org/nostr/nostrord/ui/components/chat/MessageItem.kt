package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.formatTime

/**
 * Enhanced message item with grouping support and hover actions.
 *
 * Interaction behavior:
 * - Hover: Shows subtle background highlight after 50ms
 * - Actions toolbar appears at top-right with 100ms fade in
 * - Actions disappear immediately (50ms fade out) on mouse leave
 * - Right-click (desktop) or tap "More" button: Shows context menu
 *
 * TEXT SELECTION: This component is designed to NOT interfere with text selection.
 * - NO long-press handler (conflicts with selection start gesture)
 * - Context menu triggered via right-click (desktop) or "More" button (mobile)
 * - All text content is selectable via parent SelectionContainer
 *
 * Spacing:
 * - 72dp total left column (16dp padding + 40dp avatar + 16dp gap)
 * - 16dp first message top padding (start of cluster)
 * - 2dp grouped message gap
 */
@Composable
fun MessageItem(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata? = null,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    isAuthor: Boolean = false,
    isAdmin: Boolean = false,
    onReplyClick: () -> Unit = {},
    onReactionClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onCopyText: () -> Unit = {},
    onCopyLink: () -> Unit = {},
    onPinMessage: () -> Unit = {},
    onDeleteMessage: () -> Unit = {}
) {
    val displayName = metadata?.displayName ?: metadata?.name ?: message.pubkey.take(8) + "..."
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    // Delayed hover state for actions toolbar (50ms delay)
    // On mobile (touch), show actions immediately on press
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(isHovered, isPressed) {
        if (isHovered || isPressed) {
            delay(NostrordAnimation.hoverActionsDelay.toLong())
            showActions = true
        } else {
            showActions = false
        }
    }

    // Context menu state
    var showContextMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            // Right-click opens context menu directly (bypasses hover actions)
            .then(rightClickContextMenuModifier {
                showActions = false  // Hide hover actions immediately
                showContextMenu = true
            })
            .background(
                if (isHovered || isPressed) NostrordColors.MessageHover else Color.Transparent
            )
    ) {
        // Main message content - this defines the Box size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.messagePaddingHorizontal,
                    end = Spacing.messagePaddingHorizontal,
                    top = if (isFirstInGroup) Spacing.messageGroupStart else Spacing.messageGroupGap,
                    bottom = if (isLastInGroup) Spacing.sm else Spacing.messageGroupGap
                )
        ) {
            // Avatar column - 72dp total width
            Box(
                modifier = Modifier.width(Spacing.avatarColumnWidth - Spacing.messagePaddingHorizontal),
                contentAlignment = Alignment.TopStart
            ) {
                if (isFirstInGroup) {
                    ProfileAvatar(
                        imageUrl = metadata?.picture,
                        displayName = displayName,
                        pubkey = message.pubkey,
                        size = Spacing.avatarSize
                    )
                } else if (isHovered) {
                    // Show time on hover for grouped messages
                    Text(
                        text = formatTime(message.createdAt),
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Timestamp,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // Header with name and time - only for first in group
                if (isFirstInGroup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            color = Color.White,
                            style = NostrordTypography.Username
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = formatTime(message.createdAt),
                            color = NostrordColors.TextMuted,
                            style = NostrordTypography.Timestamp
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                }

                // Message content with NIP-30 custom emoji support
                MessageContent(content = message.content, tags = message.tags)
            }
        }

        // Overlay layer for hover actions - uses matchParentSize to not affect layout
        // This Box matches parent size exactly, then positions content inside
        Box(
            modifier = Modifier
                .matchParentSize()  // Critical: doesn't affect parent measurement
                .padding(end = Spacing.sm),
            contentAlignment = Alignment.TopEnd
        ) {
            // Hover actions - fade in/out without affecting layout
            androidx.compose.animation.AnimatedVisibility(
                visible = showActions && !showContextMenu,
                enter = fadeIn(animationSpec = tween(NostrordAnimation.actionsAppear)),
                exit = fadeOut(animationSpec = tween(NostrordAnimation.actionsDisappear))
            ) {
                // DisableSelection prevents toolbar from being part of text selection
                DisableSelection {
                    MessageActions(
                        onReplyClick = onReplyClick,
                        onReactionClick = onReactionClick,
                        onMoreClick = { showContextMenu = true }
                    )
                }
            }
        }

        // Context menu - appears on right-click or "More" click
        // This is a Popup so it floats outside the layout tree
        MessageContextMenu(
            visible = showContextMenu,
            onDismiss = { showContextMenu = false },
            onAction = { action ->
                when (action) {
                    MessageContextAction.AddReaction -> onReactionClick()
                    MessageContextAction.Reply -> onReplyClick()
                    MessageContextAction.CopyText -> onCopyText()
                    MessageContextAction.CopyMessageLink -> onCopyLink()
                    MessageContextAction.PinMessage -> onPinMessage()
                    MessageContextAction.DeleteMessage -> onDeleteMessage()
                }
            },
            isAuthor = isAuthor,
            isAdmin = isAdmin
        )
    }
}

/**
 * Hover actions toolbar for messages.
 *
 * Behavior:
 * - Positioned at top-right, overlapping message slightly
 * - Appears with 100ms fade in after 50ms hover delay
 * - Disappears immediately (50ms fade out)
 * - Order: Reaction, Reply, More
 */
@Composable
private fun MessageActions(
    onReplyClick: () -> Unit,
    onReactionClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(end = Spacing.sm)
            .clip(NostrordShapes.messageActionsShape)
            .background(NostrordColors.SurfaceVariant)
            .padding(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // 1. Add Reaction (emoji)
        ActionButton(
            icon = {
                Icon(
                    Icons.Outlined.EmojiEmotions,
                    contentDescription = "React",
                    modifier = Modifier.size(Spacing.iconSm + Spacing.xs) // 16dp
                )
            },
            onClick = onReactionClick
        )
        // 2. Reply
        ActionButton(
            icon = {
                Icon(
                    Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = "Reply",
                    modifier = Modifier.size(Spacing.iconSm + Spacing.xs) // 16dp
                )
            },
            onClick = onReplyClick
        )
        // 3. More (three dots)
        ActionButton(
            icon = {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    modifier = Modifier.size(Spacing.iconSm + Spacing.xs) // 16dp
                )
            },
            onClick = onMoreClick
        )
    }
}

/**
 * Individual action button in the hover toolbar.
 */
@Composable
private fun ActionButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .size(28.dp) // Fixed size for touch target
            .clip(NostrordShapes.channelItemShape)
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (isHovered) NostrordColors.TextPrimary else NostrordColors.TextSecondary
        ) {
            icon()
        }
    }
}
