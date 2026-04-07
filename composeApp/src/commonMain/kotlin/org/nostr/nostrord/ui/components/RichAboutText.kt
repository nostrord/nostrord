package org.nostr.nostrord.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.chat.MessageContentParser
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Renders an "about" text with clickable links and resolved nostr mentions.
 *
 * Parses the text using [MessageContentParser] to detect:
 * - URLs → clickable links
 * - nostr:npub... / bare npub... → resolved display names from metadata
 *
 * Other parsed types (images, videos, code blocks, etc.) are rendered as plain text.
 */
@Composable
fun RichAboutText(
    text: String,
    userMetadata: Map<String, UserMetadata>,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    color: Color = NostrordColors.TextContent,
    onMentionClick: ((String) -> Unit)? = null
) {
    val parts = remember(text) { MessageContentParser.parse(text) }

    // Fetch metadata for mentioned pubkeys not yet in the map
    LaunchedEffect(parts) {
        val pubkeysToFetch = parts.filterIsInstance<MessageContentParser.ParsedPart.Mention>()
            .mapNotNull { mention ->
                when (val entity = mention.reference.entity) {
                    is Nip19.Entity.Npub -> entity.pubkey
                    is Nip19.Entity.Nprofile -> entity.pubkey
                    else -> null
                }
            }
            .filter { !userMetadata.containsKey(it) }
            .toSet()

        if (pubkeysToFetch.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    val annotatedString = remember(parts, userMetadata) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is MessageContentParser.ParsedPart.Link -> {
                        withLink(
                            LinkAnnotation.Url(
                                url = part.url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = NostrordColors.TextLink)
                                )
                            )
                        ) {
                            append(part.url)
                        }
                    }
                    is MessageContentParser.ParsedPart.Mention -> {
                        val displayText = getMentionDisplayText(part, userMetadata)
                        val entity = part.reference.entity
                        val pubkey = when (entity) {
                            is Nip19.Entity.Npub -> entity.pubkey
                            is Nip19.Entity.Nprofile -> entity.pubkey
                            else -> null
                        }
                        if (pubkey != null && onMentionClick != null) {
                            withLink(
                                LinkAnnotation.Clickable(
                                    tag = "mention_$pubkey",
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = NostrordColors.MentionText,
                                            fontWeight = FontWeight.Medium
                                        )
                                    ),
                                    linkInteractionListener = { onMentionClick(pubkey) }
                                )
                            ) {
                                append(displayText)
                            }
                        } else {
                            withLink(
                                LinkAnnotation.Url(
                                    url = part.reference.uri,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = NostrordColors.MentionText,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                )
                            ) {
                                append(displayText)
                            }
                        }
                    }
                    else -> {
                        // All other types (text, image, video, etc.) render as plain text
                        append(partToPlainText(part))
                    }
                }
            }
        }
    }

    BasicText(
        text = annotatedString,
        modifier = modifier,
        style = style.copy(color = color)
    )
}

private fun getMentionDisplayText(
    mention: MessageContentParser.ParsedPart.Mention,
    userMetadata: Map<String, UserMetadata>
): String {
    return when (val entity = mention.reference.entity) {
        is Nip19.Entity.Npub -> {
            val metadata = userMetadata[entity.pubkey]
            val name = metadata?.displayName ?: metadata?.name
            if (name != null) "@$name" else Nip19.getDisplayName(entity)
        }
        is Nip19.Entity.Nprofile -> {
            val metadata = userMetadata[entity.pubkey]
            val name = metadata?.displayName ?: metadata?.name
            if (name != null) "@$name" else Nip19.getDisplayName(entity)
        }
        else -> Nip19.getDisplayName(entity)
    }
}

private fun partToPlainText(part: MessageContentParser.ParsedPart): String {
    return when (part) {
        is MessageContentParser.ParsedPart.Text -> part.content
        is MessageContentParser.ParsedPart.Link -> part.url
        is MessageContentParser.ParsedPart.Image -> part.url
        is MessageContentParser.ParsedPart.Video -> part.url
        is MessageContentParser.ParsedPart.Audio -> part.url
        is MessageContentParser.ParsedPart.Relay -> part.url
        is MessageContentParser.ParsedPart.Mention -> part.reference.bech32
        is MessageContentParser.ParsedPart.CustomEmoji -> ":${part.shortcode}:"
        is MessageContentParser.ParsedPart.Bold -> part.content
        is MessageContentParser.ParsedPart.Italic -> part.content
        is MessageContentParser.ParsedPart.Monospace -> part.content
        is MessageContentParser.ParsedPart.CodeBlock -> part.code
        is MessageContentParser.ParsedPart.Hashtag -> "#${part.tag}"
        is MessageContentParser.ParsedPart.Cashu -> part.token
        is MessageContentParser.ParsedPart.CashuRequest -> part.request
    }
}
