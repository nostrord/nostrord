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
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
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
 * Returns null if no valid "q" tag is present (event ID must be non-empty).
 */
fun getQuotedEventReference(message: NostrGroupClient.NostrMessage): QuotedEventReference? {
    val qTag = message.tags.firstOrNull { it.size >= 2 && it[0] == "q" } ?: return null
    val eventId = qTag[1]

    // Validate that event ID is not empty
    if (eventId.isBlank()) return null

    return QuotedEventReference(
        eventId = eventId,
        relayHint = qTag.getOrNull(2)?.takeIf { it.isNotBlank() },
        authorPubkey = qTag.getOrNull(3)?.takeIf { it.isNotBlank() }
    )
}

/**
 * Check if a message has a valid quoted event (q tag with non-empty event ID).
 */
fun hasQuotedEvent(message: NostrGroupClient.NostrMessage): Boolean {
    return message.tags.any { it.size >= 2 && it[0] == "q" && it[1].isNotBlank() }
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
    val cachedEvents by AppModule.nostrRepository.cachedEvents.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val event = cachedEvents[reference.eventId]

    // Fetch event if not cached - use primary relay directly since quoted events are from same group
    LaunchedEffect(reference.eventId) {
        if (!cachedEvents.containsKey(reference.eventId)) {
            AppModule.nostrRepository.requestQuotedEvent(reference.eventId)
        }
    }

    // Fetch metadata for the event author
    LaunchedEffect(event?.pubkey) {
        val pubkey = event?.pubkey
        if (pubkey != null && !userMetadata.containsKey(pubkey)) {
            AppModule.nostrRepository.requestUserMetadata(setOf(pubkey))
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

                // Request metadata for any pubkeys mentioned in the content
                LaunchedEffect(event.content) {
                    val pubkeysToFetch = extractPubkeysFromContent(event.content)
                        .filter { !userMetadata.containsKey(it) }
                        .toSet()
                    if (pubkeysToFetch.isNotEmpty()) {
                        AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
                    }
                }

                // Process mentions in content to show @name instead of nostr:npub...
                val processedContent = remember(event.content, userMetadata) {
                    processMentionsInContent(event.content, userMetadata)
                        .replace('\n', ' ')
                }

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
                    text = processedContent,
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
