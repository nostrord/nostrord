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
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

// Regex to match nostr:nevent, nostr:note, or nostr:naddr references
private val NOSTR_EVENT_REGEX = Regex("""nostr:(nevent1[a-zA-Z0-9]+|note1[a-zA-Z0-9]+|naddr1[a-zA-Z0-9]+)""")

/**
 * Extract event IDs/coordinates embedded in content via nostr:nevent, nostr:note, or nostr:naddr.
 * Used to avoid showing duplicate reply preview when event is already quoted inline.
 *
 * Returns both hex event IDs and naddr coordinates (kind:pubkey:identifier format).
 */
fun extractEmbeddedEventIds(content: String): Set<String> {
    return NOSTR_EVENT_REGEX.findAll(content)
        .mapNotNull { match ->
            val bech32 = match.groupValues[1]
            when (val entity = Nip19.decode(bech32)) {
                is Nip19.Entity.Nevent -> entity.eventId
                is Nip19.Entity.Note -> entity.eventId
                is Nip19.Entity.Naddr -> "${entity.kind}:${entity.pubkey}:${entity.identifier}"
                else -> null
            }
        }
        .toSet()
}

/**
 * Extract the parent event ID from a message's tags for REPLY threading.
 *
 * For NIP-29 group messages (kind 9), replies are detected via:
 * 1. "q" tag with hex event ID or naddr coordinate (quoted reply)
 * 2. "e" tag with "reply" marker (legacy format)
 * 3. Plain "e" tag (fallback)
 *
 * If the reply target is already embedded in content (via nostr:nevent/naddr),
 * returns null to avoid duplicate display.
 *
 * Returns the parent event ID/coordinate or null if not a reply (or already embedded).
 */
fun getReplyParentId(message: NostrGroupClient.NostrMessage): String? {
    // Only kind 9 messages can have replies in group context
    if (message.kind != 9) {
        return null
    }

    // Extract event IDs/coordinates already embedded in content
    val embeddedEventIds = extractEmbeddedEventIds(message.content)

    // 1. Check for "q" tag (quoted reply)
    // Can be either 64-char hex event ID or naddr coordinate (kind:pubkey:identifier)
    val qTag = message.tags.find { tag ->
        tag.size >= 2 && tag[0] == "q" && tag[1].isNotBlank()
    }
    if (qTag != null) {
        val reference = qTag[1]
        // Skip if already embedded in content (will be shown as inline quote)
        if (reference in embeddedEventIds) {
            return null
        }
        // Only return if it's a valid hex event ID (64 chars)
        // naddr coordinates are handled inline via content rendering
        if (reference.length == 64 && reference.all { it.isLetterOrDigit() }) {
            return reference
        }
        // For naddr-style coordinates in q tag, skip if content has matching naddr
        // These are rendered inline, not as reply headers
        if (reference.contains(":")) {
            return null
        }
    }

    // 2. Check for "e" tag with "reply" marker (legacy format)
    val replyMarkerTag = message.tags.find { tag ->
        tag.size >= 4 && tag[0] == "e" && tag[3] == "reply"
    }
    if (replyMarkerTag != null) {
        val eventId = replyMarkerTag[1]
        if (eventId in embeddedEventIds) {
            return null
        }
        if (eventId.length == 64) {
            return eventId
        }
    }

    // 3. Check for plain "e" tag (fallback)
    val eTag = message.tags.find { tag ->
        tag.size >= 2 && tag[0] == "e" && tag[1].length == 64
    }
    if (eTag != null) {
        val eventId = eTag[1]
        if (eventId in embeddedEventIds) {
            return null
        }
        return eventId
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
