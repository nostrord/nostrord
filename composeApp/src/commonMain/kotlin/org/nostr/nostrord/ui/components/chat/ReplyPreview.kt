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
 * Extract the parent event ID from a message's tags for REPLY threading.
 *
 * For NIP-29 group messages (kind 9), replies are detected via:
 * 1. "q" tag with 64-char hex event ID (quoted reply)
 * 2. "e" tag with "reply" marker (legacy format)
 * 3. Plain "e" tag (fallback)
 *
 * Returns the parent event ID or null if not a reply.
 */
fun getReplyParentId(message: NostrGroupClient.NostrMessage): String? {
    // Only kind 9 messages can have replies in group context
    if (message.kind != 9) {
        return null
    }

    // 1. Check for "q" tag with 64-char hex event ID (quoted reply)
    val qTag = message.tags.find { tag ->
        tag.size >= 2 && tag[0] == "q" && tag[1].length == 64
    }
    if (qTag != null) {
        return qTag[1]
    }

    // 2. Check for "e" tag with "reply" marker (legacy format)
    val replyMarkerTag = message.tags.find { tag ->
        tag.size >= 4 && tag[0] == "e" && tag[3] == "reply"
    }
    if (replyMarkerTag != null) {
        return replyMarkerTag[1]
    }

    // 3. Check for plain "e" tag (fallback)
    val eTag = message.tags.find { tag ->
        tag.size >= 2 && tag[0] == "e" && tag[1].length == 64
    }
    if (eTag != null) {
        return eTag[1]
    }

    return null
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
