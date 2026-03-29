package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Displays reaction badges for a message.
 * Each badge shows an emoji (or custom emoji image) and the count of users who reacted with it.
 *
 * @param reactions Map of emoji to ReactionInfo containing URL and list of reactors
 * @param currentUserPubkey The current user's pubkey (to highlight their reactions)
 * @param onReactionClick Called when a reaction badge is clicked (for adding/removing reactions)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionBadges(
    reactions: Map<String, GroupManager.ReactionInfo>,
    currentUserPubkey: String? = null,
    onReactionClick: (emoji: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (reactions.isEmpty()) return

    FlowRow(
        modifier = modifier.padding(top = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        // Sort by count (highest first), then alphabetically — memoized to avoid
        // re-sorting on every recomposition when the reactions map hasn't changed.
        val sortedReactions = remember(reactions) {
            reactions.entries
                .sortedWith(compareByDescending<Map.Entry<String, GroupManager.ReactionInfo>> { it.value.reactors.size }
                    .thenBy { it.key })
        }

        sortedReactions.forEach { (emoji, info) ->
            val hasCurrentUserReacted = currentUserPubkey != null && currentUserPubkey in info.reactors

            ReactionBadge(
                emoji = emoji,
                emojiUrl = info.emojiUrl,
                count = info.reactors.size,
                isUserReacted = hasCurrentUserReacted,
                onClick = { onReactionClick(emoji) }
            )
        }
    }
}

/**
 * Individual reaction badge showing emoji (or custom emoji image) and count.
 */
@Composable
private fun ReactionBadge(
    emoji: String,
    emojiUrl: String?,
    count: Int,
    isUserReacted: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val shape = RoundedCornerShape(8.dp)

    // Background and border colors based on state
    val backgroundColor = when {
        isUserReacted -> NostrordColors.PrimarySubtle
        isHovered -> NostrordColors.HoverBackground
        else -> NostrordColors.SurfaceVariant.copy(alpha = 0.6f)
    }

    val borderColor = when {
        isUserReacted -> NostrordColors.Primary
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .clip(shape)
            .background(backgroundColor)
            .then(
                if (isUserReacted) {
                    Modifier.border(1.dp, borderColor, shape)
                } else {
                    Modifier
                }
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // Display the emoji - either as image (custom emoji) or text
            if (emojiUrl != null) {
                // Custom emoji - render as image
                CustomEmojiImage(
                    url = emojiUrl,
                    shortcode = emoji.trim(':'),
                    size = 18
                )
            } else {
                // Standard emoji or special cases
                val displayEmoji = when (emoji) {
                    "+" -> "\uD83D\uDC4D" // 👍
                    "-" -> "\uD83D\uDC4E" // 👎
                    else -> emoji
                }

                val emojiFontFamily = rememberEmojiFontFamily()
                Text(
                    text = displayEmoji,
                    fontSize = 16.sp,
                    fontFamily = emojiFontFamily
                )
            }

            // Count
            Text(
                text = count.toString(),
                color = if (isUserReacted) NostrordColors.Primary else NostrordColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isUserReacted) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/**
 * Renders a custom emoji as an image with fallback to shortcode text.
 */
@Composable
private fun CustomEmojiImage(
    url: String,
    shortcode: String,
    size: Int = 18
) {
    var loadState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }

    // Show fallback text if image fails to load
    if (loadState is AsyncImagePainter.State.Error) {
        Text(
            text = ":$shortcode:",
            fontSize = 12.sp,
            color = NostrordColors.TextSecondary
        )
    } else {
        AsyncImage(
            model = url,
            contentDescription = shortcode,
            modifier = Modifier.size(size.dp),
            contentScale = ContentScale.Fit,
            onState = { loadState = it }
        )
    }
}
