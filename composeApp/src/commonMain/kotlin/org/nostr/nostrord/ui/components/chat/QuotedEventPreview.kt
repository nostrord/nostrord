package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Data class representing a quoted event reference from a "q" tag.
 */
data class QuotedEventReference(
    val eventId: String,
    val relayHint: String?,
    val authorPubkey: String?
)

/**
 * Extract quoted event reference from a message's "q" tag.
 * Format: ["q", <event-id>, <relay-url>, <pubkey>]
 * Returns null if no "q" tag is present.
 */
fun getQuotedEventReference(message: NostrGroupClient.NostrMessage): QuotedEventReference? {
    val qTag = message.tags.firstOrNull { it.size >= 2 && it[0] == "q" } ?: return null
    return QuotedEventReference(
        eventId = qTag[1],
        relayHint = qTag.getOrNull(2),
        authorPubkey = qTag.getOrNull(3)
    )
}

/**
 * Check if a message has a quoted event (q tag).
 */
fun hasQuotedEvent(message: NostrGroupClient.NostrMessage): Boolean {
    return message.tags.any { it.size >= 2 && it[0] == "q" }
}

/**
 * Displays a quoted event preview for messages with a "q" tag.
 * Fetches the event from cache or requests it from relays.
 */
@Composable
fun QuotedEventPreview(
    reference: QuotedEventReference,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cachedEvents by NostrRepository.cachedEvents.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val event = cachedEvents[reference.eventId]

    // Fetch event if not cached - use primary relay directly since quoted events are from same group
    LaunchedEffect(reference.eventId) {
        if (!cachedEvents.containsKey(reference.eventId)) {
            NostrRepository.requestQuotedEvent(reference.eventId)
        }
    }

    // Fetch metadata for the event author
    LaunchedEffect(event?.pubkey) {
        val pubkey = event?.pubkey
        if (pubkey != null && !userMetadata.containsKey(pubkey)) {
            NostrRepository.requestUserMetadata(setOf(pubkey))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(Spacing.md)
        ) {
            if (event != null) {
                val metadata = userMetadata[event.pubkey]
                val authorName = metadata?.displayName
                    ?: metadata?.name
                    ?: event.pubkey.take(8) + "..."

                // Author row with avatar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(
                        imageUrl = metadata?.picture,
                        displayName = authorName,
                        pubkey = event.pubkey,
                        size = 20.dp
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = authorName,
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Content preview (truncated)
                Text(
                    text = event.content.replace('\n', ' '),
                    color = NostrordColors.TextContent,
                    style = NostrordTypography.Quote,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // Loading state
                Text(
                    text = "Loading quoted event...",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
            }
        }
    }
}
