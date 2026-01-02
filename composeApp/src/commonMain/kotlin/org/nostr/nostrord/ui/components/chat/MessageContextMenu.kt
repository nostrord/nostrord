package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Context menu actions for messages.
 *
 * Required items per design spec:
 * - Add Reaction
 * - Reply
 * - Copy Text
 * - Copy Message Link
 * - Pin Message (if admin)
 * - Delete Message (if author or admin)
 */
sealed class MessageContextAction {
    data object AddReaction : MessageContextAction()
    data object Reply : MessageContextAction()
    data object CopyText : MessageContextAction()
    data object CopyMessageLink : MessageContextAction()
    data object PinMessage : MessageContextAction()
    data object DeleteMessage : MessageContextAction()
}

/**
 * Message context menu - appears on right-click (desktop) or long-press (mobile).
 *
 * IMPORTANT: This is implemented as a true floating Popup overlay, completely
 * detached from the layout tree. This ensures:
 * - No layout shifts when showing/hiding
 * - No interference with text selection
 * - No recomposition of parent message content
 * - Proper z-ordering above all content
 *
 * Animation timing:
 * - Appear: 150ms with decelerate easing
 * - Disappear: 100ms with accelerate easing
 */
@Composable
fun MessageContextMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAction: (MessageContextAction) -> Unit,
    isAuthor: Boolean = false,
    isAdmin: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Only render Popup when visible to avoid layout participation
    if (visible) {
        Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(x = -8, y = 0),
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                // focusable = true allows clicking outside to dismiss
                // but must be careful not to steal focus from selection
                focusable = true,
                // Don't dismiss on back press automatically - let onDismissRequest handle it
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            // DisableSelection prevents the menu itself from participating in text selection
            DisableSelection {
                // Animated content inside the popup
                AnimatedVisibility(
                    visible = true, // Always visible when popup is shown
                    enter = fadeIn(animationSpec = tween(NostrordAnimation.popupEnter)) +
                            scaleIn(
                                animationSpec = tween(NostrordAnimation.popupEnter),
                                initialScale = 0.9f,
                                transformOrigin = TransformOrigin(1f, 0f)
                            ),
                    exit = fadeOut(animationSpec = tween(NostrordAnimation.popupExit)) +
                            scaleOut(
                                animationSpec = tween(NostrordAnimation.popupExit),
                                transformOrigin = TransformOrigin(1f, 0f)
                            )
                ) {
                    ContextMenuContent(
                        onAction = onAction,
                        onDismiss = onDismiss,
                        isAuthor = isAuthor,
                        isAdmin = isAdmin
                    )
                }
            }
        }
    }
}

/**
 * The actual menu content, separated for clarity.
 */
@Composable
private fun ContextMenuContent(
    onAction: (MessageContextAction) -> Unit,
    onDismiss: () -> Unit,
    isAuthor: Boolean,
    isAdmin: Boolean
) {
    Column(
        modifier = Modifier
            .width(Spacing.channelSidebarWidth * 0.75f) // ~180dp
            .shadow(
                elevation = 8.dp,
                shape = NostrordShapes.menuShape,
                ambientColor = Color.Black.copy(alpha = 0.3f),
                spotColor = Color.Black.copy(alpha = 0.3f)
            )
            .clip(NostrordShapes.menuShape)
            .background(NostrordColors.Surface)
            .padding(vertical = Spacing.xs)
            // Consume pointer events to prevent them from reaching underlying content
            .pointerInput(Unit) {
                detectTapGestures { /* consume */ }
            }
    ) {
        // Add Reaction
        ContextMenuItem(
            icon = Icons.Outlined.EmojiEmotions,
            label = "Add Reaction",
            onClick = {
                onAction(MessageContextAction.AddReaction)
                onDismiss()
            }
        )

        // Reply
        ContextMenuItem(
            icon = Icons.AutoMirrored.Outlined.Reply,
            label = "Reply",
            onClick = {
                onAction(MessageContextAction.Reply)
                onDismiss()
            }
        )

        ContextMenuDivider()

        // Copy Text
        ContextMenuItem(
            icon = Icons.Outlined.ContentCopy,
            label = "Copy Text",
            onClick = {
                onAction(MessageContextAction.CopyText)
                onDismiss()
            }
        )

        // Copy Message Link
        ContextMenuItem(
            icon = Icons.Outlined.Link,
            label = "Copy Message Link",
            onClick = {
                onAction(MessageContextAction.CopyMessageLink)
                onDismiss()
            }
        )

        // Pin Message (admin only)
        if (isAdmin) {
            ContextMenuDivider()

            ContextMenuItem(
                icon = Icons.Outlined.PushPin,
                label = "Pin Message",
                onClick = {
                    onAction(MessageContextAction.PinMessage)
                    onDismiss()
                }
            )
        }

        // Delete Message (author or admin)
        if (isAuthor || isAdmin) {
            ContextMenuDivider()

            ContextMenuItem(
                icon = Icons.Outlined.Delete,
                label = "Delete Message",
                onClick = {
                    onAction(MessageContextAction.DeleteMessage)
                    onDismiss()
                },
                isDestructive = true
            )
        }
    }
}

/**
 * Individual context menu item.
 */
@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val textColor = when {
        isDestructive -> NostrordColors.Error
        else -> NostrordColors.TextContent
    }

    val iconColor = when {
        isDestructive -> NostrordColors.Error
        else -> NostrordColors.TextSecondary
    }

    val backgroundColor = if (isHovered) NostrordColors.HoverBackground else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.channelItemHeight + Spacing.xs) // 36dp
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(Spacing.iconMd - Spacing.xs) // 20dp
        )

        Spacer(modifier = Modifier.width(Spacing.sm))

        Text(
            text = label,
            style = NostrordTypography.MenuItem,
            color = textColor
        )
    }
}

/**
 * Divider between menu sections.
 */
@Composable
private fun ContextMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = Spacing.xs),
        thickness = Spacing.dividerThickness,
        color = NostrordColors.Divider
    )
}
