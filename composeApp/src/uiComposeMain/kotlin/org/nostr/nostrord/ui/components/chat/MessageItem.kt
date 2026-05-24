package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.zap.ZapBadge
import org.nostr.nostrord.ui.components.zap.ZapController
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.formatTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Enhanced message item with grouping support and hover actions.
 *
 * Interaction behavior:
 * - Desktop: hover shows action toolbar; right-click opens context menu
 * - Mobile (Android / touch web): single tap opens the context menu directly
 *
 * Spacing:
 * - 72dp total left column (16dp padding + 40dp avatar + 16dp gap)
 * - 6dp uniform top/bottom padding on every row (no asymmetric first/last bumps)
 * - Hairline divider above each grouped message to mark the boundary
 */
@Composable
fun MessageItem(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata? = null,
    resolveReplyMessage: (String) -> NostrGroupClient.NostrMessage? = { null },
    resolveMetadata: (String) -> UserMetadata? = { null },
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    isAuthor: Boolean = false,
    isAdmin: Boolean = false,
    reactions: Map<String, GroupManager.ReactionInfo> = emptyMap(),
    currentUserPubkey: String? = null,
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onReplyClick: () -> Unit = {},
    onReactionClick: () -> Unit = {},
    onReactionBadgeClick: (emoji: String) -> Unit = {},
    onMoreClick: () -> Unit = {},
    onCopyText: () -> Unit = {},
    onCopyLink: () -> Unit = {},
    onShareLink: () -> Unit = {},
    onCopyJson: () -> Unit = {},
    onPinMessage: () -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {},
    onScrollToMessage: (String) -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> },
    isHighlighted: Boolean = false,
    isContextMenuOpen: Boolean = false,
    onContextMenuOpenChange: (Boolean) -> Unit = {},
    swipeToReplyEnabled: Boolean = false,
) {
    // Use rememberUpdatedState to avoid recomposition when callbacks change reference
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnReplyClick by rememberUpdatedState(onReplyClick)
    val currentOnReactionClick by rememberUpdatedState(onReactionClick)
    val currentOnCopyText by rememberUpdatedState(onCopyText)
    val currentOnCopyLink by rememberUpdatedState(onCopyLink)
    val currentOnShareLink by rememberUpdatedState(onShareLink)
    val currentOnCopyJson by rememberUpdatedState(onCopyJson)
    val currentOnPinMessage by rememberUpdatedState(onPinMessage)
    val currentOnDeleteMessage by rememberUpdatedState(onDeleteMessage)

    // Memoize display name calculation
    val displayName =
        remember(metadata?.displayName, metadata?.name, message.pubkey) {
            metadata?.displayName ?: metadata?.name ?: message.pubkey.take(8) + "..."
        }

    // Check if this message is a reply and find the parent message
    val replyParentId = remember(message.tags) { getReplyParentId(message) }

    // Collect cached events from repository for parent message lookup
    val cachedEvents by AppModule.nostrRepository.cachedEvents.collectAsState()

    // NIP-57 zaps: offer the action when the author has a Lightning address (and isn't
    // the current user), and surface the running total for this message.
    val zapTotals by AppModule.nostrRepository.zaps.collectAsState()
    val zapInfo = zapTotals[message.id]
    val canZap = message.pubkey != currentUserPubkey &&
        (!metadata?.lud16.isNullOrBlank() || !metadata?.lud06.isNullOrBlank())

    // Look up parent via caller-provided resolver, then in cachedEvents
    val parentMessage =
        remember(replyParentId) {
            if (replyParentId != null) resolveReplyMessage(replyParentId) else null
        }

    // If not in allMessages, check cachedEvents and convert to NostrMessage
    val parentFromCache: NostrGroupClient.NostrMessage? =
        remember(replyParentId, cachedEvents, parentMessage) {
            if (replyParentId != null && parentMessage == null) {
                cachedEvents[replyParentId]?.let { cached ->
                    NostrGroupClient.NostrMessage(
                        id = cached.id,
                        pubkey = cached.pubkey,
                        content = cached.content,
                        createdAt = cached.createdAt,
                        kind = cached.kind,
                        tags = cached.tags,
                    )
                }
            } else {
                null
            }
        }

    // Use whichever parent we found
    val resolvedParentMessage = parentMessage ?: parentFromCache

    // Request parent event from relay if not found locally
    LaunchedEffect(replyParentId, resolvedParentMessage) {
        if (replyParentId != null && resolvedParentMessage == null) {
            // Extract relay hints from q tag if available
            val qTag = message.tags.find { it.size >= 2 && it[0] == "q" && it[1] == replyParentId }
            val relayHints = qTag?.getOrNull(2)?.let { listOf(it) } ?: emptyList()

            // Also check e tag for relay hints
            val eTag = message.tags.find { it.size >= 2 && it[0] == "e" && it[1] == replyParentId }
            val eRelayHints = eTag?.getOrNull(2)?.let { listOf(it) } ?: emptyList()

            val allRelayHints = (relayHints + eRelayHints).distinct()

            AppModule.nostrRepository.requestEventById(replyParentId, allRelayHints)
        }
    }

    val parentMetadata =
        remember(resolvedParentMessage?.pubkey) {
            resolvedParentMessage?.let { resolveMetadata(it.pubkey) }
        }

    // Request metadata for parent message author if not available
    LaunchedEffect(resolvedParentMessage?.pubkey, parentMetadata) {
        if (resolvedParentMessage != null && parentMetadata == null) {
            AppModule.nostrRepository.requestUserMetadata(setOf(resolvedParentMessage.pubkey))
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    // Delayed hover state for actions toolbar (50ms delay). This is a desktop-only
    // pointer affordance: on touch/compact layouts (Android, iOS, narrow web) a tap
    // would otherwise register as a press and trip it. Those layouts use tap (context
    // menu) and swipe (reply) instead, so the toolbar is suppressed there — gated by
    // swipeToReplyEnabled, the same compact-layout signal.
    var showActions by remember { mutableStateOf(false) }
    LaunchedEffect(isHovered, isPressed, swipeToReplyEnabled) {
        if (!swipeToReplyEnabled && (isHovered || isPressed)) {
            delay(NostrordAnimation.hoverActionsDelay.toLong())
            showActions = true
        } else {
            showActions = false
        }
    }

    var highlightActive by remember(isHighlighted) { mutableStateOf(isHighlighted) }
    LaunchedEffect(isHighlighted) {
        if (isHighlighted) {
            highlightActive = true
            delay(2500)
            highlightActive = false
        }
    }
    val highlightColor by animateColorAsState(
        targetValue = if (highlightActive) NostrordColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = if (highlightActive) snap() else tween(durationMillis = 1200),
    )

    // Swipe-to-reply (touch layouts only): drag the message left to reveal a reply
    // affordance; releasing past the threshold enters reply mode and focuses the input.
    // Left direction is deliberate so it doesn't fight the left-edge swipe that opens
    // the side menu (that gesture pulls content the other way).
    val swipeScope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val swipeOffset = remember { Animatable(0f) }
    val triggerPx = with(density) { 56.dp.toPx() }
    val maxOffsetPx = with(density) { 80.dp.toPx() }
    var swipeArmed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Hairline sits outside the hover Box so the hover/highlight background
        // doesn't extend across the boundary between two grouped messages.
        // Aligned with the content column (starts after the avatar gutter).
        if (!isFirstInGroup) {
            androidx.compose.material3.HorizontalDivider(
                thickness = Spacing.dividerThickness,
                color = Color.White.copy(alpha = 0.05f),
                modifier =
                Modifier.padding(
                    start = Spacing.avatarColumnWidth,
                    end = Spacing.messagePaddingHorizontal,
                ),
            )
        }
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .hoverable(interactionSource)
                // Tap (mobile) / right-click (desktop) opens the context menu directly.
                // Closing is handled by the menu's full-screen scrim (see MessageContextMenu).
                .then(
                    rightClickContextMenuModifier {
                        showActions = false // Hide hover actions immediately
                        onContextMenuOpenChange(true)
                    },
                ).then(
                    if (swipeToReplyEnabled) {
                        Modifier.pointerInput(Unit) {
                            val touchSlop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var totalX = 0f
                                var totalY = 0f
                                var claimed = false
                                var offset = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    val delta = change.positionChange()
                                    if (!claimed) {
                                        totalX += delta.x
                                        totalY += delta.y
                                        // Vertical intent → let the list scroll; never consume.
                                        if (abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)) break
                                        // Rightward intent → let the left-edge swipe open the side menu.
                                        if (totalX >= touchSlop) break
                                        // Leftward past slop → this is a reply swipe; claim it.
                                        if (totalX <= -touchSlop) claimed = true
                                    }
                                    if (claimed) {
                                        change.consume()
                                        offset = (offset + delta.x).coerceIn(-maxOffsetPx, 0f)
                                        swipeScope.launch { swipeOffset.snapTo(offset) }
                                        if (!swipeArmed && offset <= -triggerPx) {
                                            swipeArmed = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else if (swipeArmed && offset > -triggerPx) {
                                            swipeArmed = false
                                        }
                                    }
                                }
                                if (claimed && offset <= -triggerPx) currentOnReplyClick()
                                swipeArmed = false
                                swipeScope.launch { swipeOffset.animateTo(0f) }
                            }
                        }
                    } else {
                        Modifier
                    },
                ).background(
                    when {
                        isContextMenuOpen -> NostrordColors.SurfaceVariant
                        // Hover tint is a desktop-only pointer affordance; a touch tap fires
                        // isPressed too, so suppress it in compact/touch layouts.
                        !swipeToReplyEnabled && (isHovered || isPressed) -> NostrordColors.MessageHover
                        else -> highlightColor
                    },
                ),
        ) {
            // Reply affordance revealed underneath the message as it slides left.
            if (swipeToReplyEnabled && swipeOffset.value < 0f) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = null,
                    tint = if (swipeArmed) NostrordColors.Primary else NostrordColors.TextMuted,
                    modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Spacing.messagePaddingHorizontal)
                        .size(24.dp)
                        .alpha((-swipeOffset.value / triggerPx).coerceIn(0f, 1f)),
                )
            }
            // Main message content - this defines the Box size
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                    .padding(
                        start = Spacing.messagePaddingHorizontal,
                        end = Spacing.messagePaddingHorizontal,
                        top = Spacing.messageGroupGap,
                        bottom = Spacing.messageGroupGap,
                    ),
            ) {
                // Avatar column - 72dp total width
                Box(
                    modifier = Modifier.width(Spacing.avatarColumnWidth - Spacing.messagePaddingHorizontal),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (isFirstInGroup) {
                        Box(
                            modifier =
                            Modifier
                                .size(Spacing.avatarSize)
                                .clip(CircleShape)
                                .clickable { currentOnUsernameClick(message.pubkey) }
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            ProfileAvatar(
                                imageUrl = metadata?.picture,
                                displayName = displayName,
                                pubkey = message.pubkey,
                                size = Spacing.avatarSize,
                            )
                        }
                    } else if (isHovered) {
                        // No padding/fixed height: this lives in the main Row, so any
                        // extra vertical footprint here would grow the row on hover.
                        Text(
                            text = formatTime(message.createdAt),
                            color = NostrordColors.TextMuted,
                            style = NostrordTypography.Timestamp,
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
                                style = NostrordTypography.Username,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier =
                                Modifier
                                    .weight(1f, fill = false)
                                    .clickable { currentOnUsernameClick(message.pubkey) }
                                    .pointerHoverIcon(PointerIcon.Hand),
                            )
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Text(
                                text = formatTime(message.createdAt),
                                color = NostrordColors.TextMuted,
                                style = NostrordTypography.Timestamp,
                                maxLines = 1,
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    // Reply preview — shown whenever this message is a reply,
                    // even if the parent hasn't loaded yet (ReplyPreview handles null with a placeholder)
                    if (replyParentId != null) {
                        ReplyPreview(
                            parentMessage = resolvedParentMessage,
                            parentMetadata = parentMetadata,
                            resolveMetadata = resolveMetadata,
                            onReplyClick = { onScrollToMessage(replyParentId) },
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    // Message content with NIP-30 custom emoji support
                    MessageContent(
                        content = message.content,
                        tags = message.tags,
                        onMentionClick = currentOnUsernameClick,
                        onHashtagClick = { hashtag ->
                            // TODO: Implement hashtag click handler (e.g., search for hashtag)
                        },
                        currentGroupId = currentGroupId,
                        currentRelayUrl = currentRelayUrl,
                        onNavigateToGroup = onNavigateToGroup,
                    )

                    // Reaction badges
                    if (reactions.isNotEmpty()) {
                        ReactionBadges(
                            reactions = reactions,
                            currentUserPubkey = currentUserPubkey,
                            resolveMetadata = resolveMetadata,
                            onReactionClick = onReactionBadgeClick,
                        )
                    }

                    // Zap total badge
                    if (zapInfo != null && zapInfo.totalMsats > 0) {
                        ZapBadge(
                            totalMsats = zapInfo.totalMsats,
                            count = zapInfo.count,
                            zappedByMe = currentUserPubkey != null && currentUserPubkey in zapInfo.zappers,
                            onClick = { if (canZap) ZapController.request(message.pubkey, message.id) },
                        )
                    }
                }
            }

            // Overlay layer for hover actions - uses matchParentSize to not affect layout
            // This Box matches parent size exactly, then positions content inside
            Box(
                modifier =
                Modifier
                    .matchParentSize() // Critical: doesn't affect parent measurement
                    .padding(end = Spacing.sm),
                contentAlignment = Alignment.TopEnd,
            ) {
                // Hover actions - fade in/out without affecting layout
                androidx.compose.animation.AnimatedVisibility(
                    visible = showActions && !isContextMenuOpen,
                    enter = fadeIn(animationSpec = tween(NostrordAnimation.actionsAppear)),
                    exit = fadeOut(animationSpec = tween(NostrordAnimation.actionsDisappear)),
                ) {
                    // DisableSelection prevents toolbar from being part of text selection
                    DisableSelection {
                        MessageActions(
                            onReplyClick = currentOnReplyClick,
                            onReactionClick = currentOnReactionClick,
                            onMoreClick = { onContextMenuOpenChange(true) },
                            canZap = canZap,
                            onZapClick = { ZapController.request(message.pubkey, message.id) },
                        )
                    }
                }
            }

            // Context menu - appears on right-click or "More" click
            // This is a Popup so it floats outside the layout tree
            MessageContextMenu(
                visible = isContextMenuOpen,
                onDismiss = { onContextMenuOpenChange(false) },
                onAction = { action ->
                    when (action) {
                        MessageContextAction.AddReaction -> currentOnReactionClick()
                        MessageContextAction.Reply -> currentOnReplyClick()
                        MessageContextAction.CopyText -> currentOnCopyText()
                        MessageContextAction.CopyMessageLink -> currentOnCopyLink()
                        MessageContextAction.ShareMessageLink -> currentOnShareLink()
                        MessageContextAction.CopyEventJson -> currentOnCopyJson()
                        MessageContextAction.PinMessage -> currentOnPinMessage()
                        MessageContextAction.DeleteMessage -> currentOnDeleteMessage()
                        MessageContextAction.ZapMessage -> ZapController.request(message.pubkey, message.id)
                    }
                },
                isAuthor = isAuthor,
                isAdmin = isAdmin,
                canZap = canZap,
            )
        }
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
    canZap: Boolean,
    onZapClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .padding(end = Spacing.sm)
            .clip(NostrordShapes.messageActionsShape)
            .background(NostrordColors.SurfaceVariant)
            .padding(horizontal = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // 1. Add Reaction (emoji)
        ActionButton(
            icon = {
                Icon(
                    Icons.Outlined.EmojiEmotions,
                    contentDescription = "React",
                    modifier = Modifier.size(Spacing.iconSm + Spacing.xs), // 16dp
                )
            },
            onClick = onReactionClick,
        )
        // 2. Reply
        ActionButton(
            icon = {
                Icon(
                    Icons.AutoMirrored.Outlined.Reply,
                    contentDescription = "Reply",
                    modifier = Modifier.size(Spacing.iconSm + Spacing.xs), // 16dp
                )
            },
            onClick = onReplyClick,
        )
        // 3. Zap (when the author has a Lightning address) — highlights amber on hover
        if (canZap) {
            ActionButton(
                icon = { isHovered ->
                    Icon(
                        Icons.Outlined.Bolt,
                        contentDescription = "Zap",
                        tint = if (isHovered) NostrordColors.Warning else NostrordColors.TextSecondary,
                        modifier = Modifier.size(Spacing.iconSm + Spacing.xs), // 16dp
                    )
                },
                onClick = onZapClick,
            )
        }
        // 4. More (three dots)
        ActionButton(
            icon = {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "More options",
                    modifier = Modifier.size(Spacing.iconSm + Spacing.xs), // 16dp
                )
            },
            onClick = onMoreClick,
        )
    }
}

/**
 * Individual action button in the hover toolbar.
 */
@Composable
private fun ActionButton(
    icon: @Composable (isHovered: Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier =
        Modifier
            .size(Spacing.messageActionButtonSize)
            .clip(NostrordShapes.channelItemShape)
            .background(if (isHovered) NostrordColors.HoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (isHovered) NostrordColors.TextPrimary else NostrordColors.TextSecondary,
        ) {
            icon(isHovered)
        }
    }
}
