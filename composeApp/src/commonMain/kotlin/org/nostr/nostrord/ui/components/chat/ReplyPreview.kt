package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Extract the parent event ID from a message's tags.
 * For kind 9 messages: Only "q" tags indicate a reply (not "e" tags).
 * For other kinds: Both "q" and "e" tags can indicate a reply.
 * Returns the parent event ID or null if not a reply.
 */
fun getReplyParentId(message: NostrGroupClient.NostrMessage): String? {
    // Check for "q" tag first: ["q", <event_id>, <relay_hint?>, <author_pubkey?>]
    val qTag = message.tags.firstOrNull { it.size >= 2 && it[0] == "q" }
    if (qTag != null) {
        val eventId = qTag.getOrNull(1)
        if (!eventId.isNullOrBlank()) {
            return eventId
        }
    }

    // For kind 9 messages, ONLY "q" tag indicates a reply, not "e" tag
    if (message.kind == 9) {
        return null
    }

    // For other event kinds, fall back to "e" tag: ["e", <event_id>]
    val eTag = message.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
    val eventId = eTag?.getOrNull(1)
    return if (!eventId.isNullOrBlank()) eventId else null
}

/**
 * Check if a message is a reply to another message.
 */
fun isReply(message: NostrGroupClient.NostrMessage): Boolean {
    return getReplyParentId(message) != null
}

/**
 * Compact reply preview shown above a message that is replying to another message.
 * Shows the parent message author and a truncated preview of the content.
 */
@Composable
fun ReplyPreview(
    parentMessage: NostrGroupClient.NostrMessage?,
    parentMetadata: UserMetadata?,
    userMetadata: Map<String, UserMetadata> = emptyMap(),
    onReplyClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (parentMessage == null) {
        // Parent message not found - show placeholder
        ReplyPreviewContainer(
            onClick = onReplyClick,
            modifier = modifier
        ) {
            Text(
                text = "Replying to a message...",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
                maxLines = 1
            )
        }
        return
    }

    val authorName = parentMetadata?.displayName
        ?: parentMetadata?.name
        ?: parentMessage.pubkey.take(8) + "..."

    // Request metadata for any pubkeys mentioned in the content
    LaunchedEffect(parentMessage.content) {
        val pubkeysToFetch = extractPubkeysFromContent(parentMessage.content)
            .filter { !userMetadata.containsKey(it) }
            .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            NostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    // Process mentions in content to show @name instead of nostr:npub...
    val processedContent = remember(parentMessage.content, userMetadata) {
        processMentionsInContent(parentMessage.content, userMetadata)
            .replace('\n', ' ')
    }

    ReplyPreviewContainer(
        onClick = onReplyClick,
        modifier = modifier
    ) {
        // Author name
        Text(
            text = authorName,
            color = NostrordColors.Primary,
            style = NostrordTypography.Caption,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Content preview (truncated)
        Text(
            text = processedContent.take(100),
            color = NostrordColors.TextSecondary,
            style = NostrordTypography.Caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Container for reply preview with left accent bar.
 */
@Composable
private fun ReplyPreviewContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(NostrordColors.Surface.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(32.dp)
                .background(
                    color = NostrordColors.Primary,
                    shape = RoundedCornerShape(1.5.dp)
                )
        )

        Spacer(modifier = Modifier.width(Spacing.sm))

        // Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = Spacing.sm)
        ) {
            content()
        }
    }
}
