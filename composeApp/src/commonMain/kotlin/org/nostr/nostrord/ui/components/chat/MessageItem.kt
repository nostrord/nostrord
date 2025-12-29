package org.nostr.nostrord.ui.components.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.formatTime

/**
 * Enhanced message item with grouping support and hover actions.
 * Consecutive messages from the same author are grouped together.
 */
@Composable
fun MessageItem(
    message: NostrGroupClient.NostrMessage,
    metadata: UserMetadata? = null,
    isFirstInGroup: Boolean = true,
    isLastInGroup: Boolean = true,
    onReplyClick: () -> Unit = {},
    onReactionClick: () -> Unit = {},
    onMoreClick: () -> Unit = {}
) {
    val displayName = metadata?.displayName ?: metadata?.name ?: message.pubkey.take(8)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                if (isHovered) NostrordColors.Surface.copy(alpha = 0.3f) else Color.Transparent
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = if (isFirstInGroup) 8.dp else 2.dp,
                    bottom = if (isLastInGroup) 8.dp else 2.dp
                )
        ) {
            // Avatar column - show avatar for first in group, spacer for others
            Box(
                modifier = Modifier.width(48.dp),
                contentAlignment = Alignment.TopStart
            ) {
                if (isFirstInGroup) {
                    ProfileAvatar(
                        imageUrl = metadata?.picture,
                        displayName = displayName,
                        pubkey = message.pubkey,
                        size = 40.dp
                    )
                } else if (isHovered) {
                    // Show time on hover for grouped messages
                    Text(
                        text = formatTime(message.createdAt),
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Header with name and time - only for first in group
                if (isFirstInGroup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatTime(message.createdAt),
                            color = NostrordColors.TextMuted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Message content
                MessageContent(content = message.content)
            }
        }

        // Hover actions - positioned at top right
        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            MessageActions(
                onReplyClick = onReplyClick,
                onReactionClick = onReactionClick,
                onMoreClick = onMoreClick
            )
        }
    }
}

@Composable
private fun MessageActions(
    onReplyClick: () -> Unit,
    onReactionClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(end = 8.dp, top = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(NostrordColors.Surface)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        ActionButton(
            icon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = "React", modifier = Modifier.size(16.dp)) },
            onClick = onReactionClick
        )
        ActionButton(
            icon = { Icon(Icons.Outlined.Reply, contentDescription = "Reply", modifier = Modifier.size(16.dp)) },
            onClick = onReplyClick
        )
        ActionButton(
            icon = { Icon(Icons.Outlined.MoreVert, contentDescription = "More", modifier = Modifier.size(16.dp)) },
            onClick = onMoreClick
        )
    }
}

@Composable
private fun ActionButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides NostrordColors.TextSecondary
        ) {
            icon()
        }
    }
}
