package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

// Type alias to bridge new parser to existing rendering code
private typealias ContentPart = MessageContentParser.ParsedPart
private typealias TextPart = MessageContentParser.ParsedPart.Text
private typealias ImagePart = MessageContentParser.ParsedPart.Image
private typealias LinkPart = MessageContentParser.ParsedPart.Link
private typealias MentionPart = MessageContentParser.ParsedPart.Mention
private typealias CustomEmojiPart = MessageContentParser.ParsedPart.CustomEmoji

/**
 * Parses message content into parts using the robust MessageContentParser.
 */
private fun parseContent(content: String, emojiMap: Map<String, String> = emptyMap()): List<ContentPart> {
    return MessageContentParser.parse(content, emojiMap)
}

/**
 * Check if a content part should be rendered as a block element (on its own line)
 */
private fun isBlockPart(part: ContentPart): Boolean {
    return when (part) {
        is ImagePart -> true
        is MentionPart -> {
            // Quoted events (nevent, note) are block elements
            when (part.reference.entity) {
                is Nip19.Entity.Nevent, is Nip19.Entity.Note -> true
                else -> false
            }
        }
        else -> false
    }
}

/**
 * # MessageContent - Robust Inline Text Rendering
 *
 * This component uses a single AnnotatedString with InlineContent to render
 * mixed content (text, links, mentions, emojis) as truly inline text.
 *
 * ## Why AnnotatedString instead of FlowRow?
 *
 * FlowRow treats each child as a separate layout box, causing:
 * - Mentions to wrap as entire units instead of flowing with text
 * - Whitespace to become independent boxes with inconsistent spacing
 * - Baseline misalignment between text and styled mentions
 *
 * AnnotatedString keeps everything in a single Text composable where:
 * - Line breaking happens at character/word boundaries, not component boundaries
 * - Baseline alignment is consistent across all styled spans
 * - Whitespace flows naturally with surrounding text
 *
 * ## Architecture
 *
 * 1. Parse content into typed parts (Text, Link, Mention, CustomEmoji, Image)
 * 2. Group into inline sequences vs block elements
 * 3. For inline groups: Build single AnnotatedString with:
 *    - SpanStyle for visual styling (color, font weight)
 *    - LinkAnnotation for clickable URLs and mentions
 *    - InlineContent placeholders for custom emojis
 * 4. For block elements: Render as separate composables in Column
 */
@Composable
fun MessageContent(
    content: String,
    tags: List<List<String>> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Extract custom emoji map from NIP-30 tags
    val emojiMap = remember(tags) { MessageContentParser.extractEmojiMap(tags) }
    val parts = remember(content, emojiMap) { parseContent(content, emojiMap) }
    val uriHandler = LocalUriHandler.current

    // Image viewer modal state
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }

    // Collect user metadata for mention display names
    val userMetadata by NostrRepository.userMetadata.collectAsState()

    // Group parts into inline sequences and block elements
    val groups = remember(parts) {
        val result = mutableListOf<List<ContentPart>>()
        var currentInlineGroup = mutableListOf<ContentPart>()

        parts.forEach { part ->
            if (isBlockPart(part)) {
                // Flush current inline group if not empty
                if (currentInlineGroup.isNotEmpty()) {
                    result.add(currentInlineGroup.toList())
                    currentInlineGroup = mutableListOf()
                }
                // Add block element as its own group
                result.add(listOf(part))
            } else {
                currentInlineGroup.add(part)
            }
        }
        // Flush remaining inline group
        if (currentInlineGroup.isNotEmpty()) {
            result.add(currentInlineGroup.toList())
        }
        result
    }

    // Request metadata for all mentions
    LaunchedEffect(parts) {
        val pubkeysToFetch = parts.filterIsInstance<MentionPart>()
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
            NostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    Column(modifier = modifier) {
        groups.forEach { group ->
            val firstPart = group.firstOrNull()

            // Check if this is a block element group (single block element)
            if (group.size == 1 && isBlockPart(firstPart!!)) {
                when (firstPart) {
                    is ImagePart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        ChatImage(
                            imageUrl = firstPart.url,
                            onClick = { selectedImageUrl = firstPart.url }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is MentionPart -> {
                        Spacer(modifier = Modifier.height(4.dp))
                        QuotedEventBlock(
                            mention = firstPart,
                            onClick = {
                                try {
                                    uriHandler.openUri(firstPart.reference.uri)
                                } catch (_: Exception) {}
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    else -> {}
                }
            } else {
                // Render inline group as single AnnotatedString
                InlineContentGroup(
                    parts = group,
                    userMetadata = userMetadata,
                    onLinkClick = { url ->
                        try {
                            uriHandler.openUri(url)
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }

    // Image viewer modal
    selectedImageUrl?.let { imageUrl ->
        ImageViewerModal(
            imageUrl = imageUrl,
            onDismiss = { selectedImageUrl = null }
        )
    }
}

/**
 * Renders a group of inline parts as a single Text with AnnotatedString.
 *
 * This is the key fix for the displacement bug: all inline content is
 * rendered in one Text composable, so line breaking and baseline alignment
 * work correctly.
 */
@Composable
private fun InlineContentGroup(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Build inline content map for custom emojis
    val inlineContentMap = remember(parts) {
        parts.filterIsInstance<CustomEmojiPart>()
            .associate { emoji ->
                val id = "emoji_${emoji.shortcode}"
                id to InlineTextContent(
                    placeholder = Placeholder(
                        width = 22.sp,  // Match line height
                        height = 22.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    InlineCustomEmoji(
                        shortcode = emoji.shortcode,
                        imageUrl = emoji.imageUrl
                    )
                }
            }
    }

    // Build the annotated string
    val annotatedString = remember(parts, userMetadata) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is TextPart -> {
                        append(part.content)
                    }
                    is LinkPart -> {
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
                    is MentionPart -> {
                        val displayText = getMentionDisplayText(part, userMetadata)
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
                    is CustomEmojiPart -> {
                        // Insert placeholder for inline content
                        appendInlineContent("emoji_${part.shortcode}", "[${part.shortcode}]")
                    }
                    else -> {}
                }
            }
        }
    }

    Text(
        text = annotatedString,
        color = NostrordColors.TextContent,
        style = NostrordTypography.MessageBody,
        inlineContent = inlineContentMap,
        modifier = modifier
    )
}

/**
 * Get display text for a mention, using cached metadata if available.
 */
private fun getMentionDisplayText(
    mention: MentionPart,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>
): String {
    return when (val entity = mention.reference.entity) {
        is Nip19.Entity.Npub -> {
            val metadata = userMetadata[entity.pubkey]
            metadata?.displayName ?: metadata?.name ?: Nip19.getDisplayName(entity)
        }
        is Nip19.Entity.Nprofile -> {
            val metadata = userMetadata[entity.pubkey]
            metadata?.displayName ?: metadata?.name ?: Nip19.getDisplayName(entity)
        }
        else -> Nip19.getDisplayName(entity)
    }
}

/**
 * Renders a custom emoji as an inline image.
 * Size matches line height for proper text alignment.
 */
@Composable
private fun InlineCustomEmoji(
    shortcode: String,
    imageUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build(),
        contentDescription = ":$shortcode:",
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
        modifier = modifier.fillMaxSize()
    )
}

@Composable
private fun ChatImage(
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var showError by remember { mutableStateOf(false) }

    if (showError) {
        // Show URL as link if image fails to load
        Text(
            text = imageUrl,
            color = NostrordColors.TextLink,
            style = NostrordTypography.Link,
            modifier = Modifier.clickable(onClick = onClick)
        )
    } else {
        Box(
            modifier = modifier
                .clip(NostrordShapes.imageShape)
                .background(NostrordColors.Surface)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "Image",
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .heightIn(max = 300.dp)
                    .clip(NostrordShapes.imageShape),
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Error) {
                        showError = true
                    }
                }
            )

            // Show loading indicator with placeholder
            if (imageState is AsyncImagePainter.State.Loading) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 200.dp)
                        .heightIn(min = 100.dp)
                        .background(NostrordColors.Surface, NostrordShapes.imageShape),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 3.dp
                    )
                }
            }
        }
    }
}

/**
 * Block-level quoted event display (for nevent/note mentions).
 */
@Composable
private fun QuotedEventBlock(
    mention: MentionPart,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entity = mention.reference.entity

    when (entity) {
        is Nip19.Entity.Nevent -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = entity.relays,
                author = entity.author,
                onClick = onClick,
                modifier = modifier
            )
        }
        is Nip19.Entity.Note -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = emptyList(),
                author = null,
                onClick = onClick,
                modifier = modifier
            )
        }
        else -> {}
    }
}

@Composable
private fun QuotedEvent(
    eventId: String,
    relayHints: List<String> = emptyList(),
    author: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedEvents by NostrRepository.cachedEvents.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val event = cachedEvents[eventId]

    LaunchedEffect(eventId, relayHints, author) {
        if (!cachedEvents.containsKey(eventId)) {
            NostrRepository.requestEventById(eventId, relayHints, author)
        }
    }

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
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(Spacing.md)
        ) {
            if (event != null) {
                val metadata = userMetadata[event.pubkey]
                val authorName = metadata?.displayName ?: metadata?.name ?: event.pubkey.take(8) + "..."

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NostrordColors.Primary.copy(alpha = 0.3f))
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Text(
                        text = authorName,
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.sm))
                QuotedEventContent(content = event.content)
            } else {
                Text(
                    text = "Loading event ${eventId.take(8)}...",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
            }
        }
    }
}

/**
 * Content inside a quoted event - uses same AnnotatedString approach.
 */
@Composable
private fun QuotedEventContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val parts = remember(content) { parseContent(content) }
    val uriHandler = LocalUriHandler.current
    val userMetadata by NostrRepository.userMetadata.collectAsState()

    // Image viewer modal state
    var selectedImageUrl by remember { mutableStateOf<String?>(null) }

    // Group parts into inline sequences and block elements
    val groups = remember(parts) {
        val result = mutableListOf<List<ContentPart>>()
        var currentInlineGroup = mutableListOf<ContentPart>()

        parts.forEach { part ->
            if (isBlockPart(part)) {
                if (currentInlineGroup.isNotEmpty()) {
                    result.add(currentInlineGroup.toList())
                    currentInlineGroup = mutableListOf()
                }
                result.add(listOf(part))
            } else {
                currentInlineGroup.add(part)
            }
        }
        if (currentInlineGroup.isNotEmpty()) {
            result.add(currentInlineGroup.toList())
        }
        result
    }

    Column(modifier = modifier) {
        groups.forEach { group ->
            val firstPart = group.firstOrNull()

            if (group.size == 1 && isBlockPart(firstPart!!)) {
                when (firstPart) {
                    is ImagePart -> {
                        Spacer(modifier = Modifier.height(6.dp))
                        QuotedImage(
                            imageUrl = firstPart.url,
                            onClick = { selectedImageUrl = firstPart.url }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is MentionPart -> {
                        // Nested quoted event - show as simple link
                        Text(
                            text = Nip19.getDisplayName(firstPart.reference.entity),
                            color = NostrordColors.MentionText,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable {
                                try {
                                    uriHandler.openUri(firstPart.reference.uri)
                                } catch (_: Exception) {}
                            }
                        )
                    }
                    else -> {}
                }
            } else {
                // Render inline group using AnnotatedString
                QuotedInlineContentGroup(
                    parts = group,
                    userMetadata = userMetadata,
                    onLinkClick = { url ->
                        try {
                            uriHandler.openUri(url)
                        } catch (_: Exception) {}
                    }
                )
            }
        }
    }

    // Image viewer modal
    selectedImageUrl?.let { imageUrl ->
        ImageViewerModal(
            imageUrl = imageUrl,
            onDismiss = { selectedImageUrl = null }
        )
    }
}

/**
 * Inline content group for quoted events - uses Caption style.
 */
@Composable
private fun QuotedInlineContentGroup(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotatedString = remember(parts, userMetadata) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is TextPart -> {
                        append(part.content)
                    }
                    is LinkPart -> {
                        withLink(
                            LinkAnnotation.Url(
                                url = part.url,
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = NostrordColors.Primary)
                                )
                            )
                        ) {
                            append(part.url)
                        }
                    }
                    is MentionPart -> {
                        val displayText = getMentionDisplayText(part, userMetadata)
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
                    else -> {}
                }
            }
        }
    }

    Text(
        text = annotatedString,
        color = NostrordColors.TextContent,
        style = NostrordTypography.Caption,
        maxLines = 6,
        modifier = modifier
    )
}

@Composable
private fun QuotedImage(
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var showError by remember { mutableStateOf(false) }

    if (showError) {
        Text(
            text = imageUrl,
            color = NostrordColors.Primary,
            style = NostrordTypography.Caption,
            modifier = Modifier.clickable(onClick = onClick)
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.BackgroundDark)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "Image",
                contentScale = ContentScale.FillWidth,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(NostrordShapes.radiusMedium)),
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Error) {
                        showError = true
                    }
                }
            )

            if (imageState is AsyncImagePainter.State.Loading) {
                Box(
                    modifier = Modifier
                        .widthIn(min = 150.dp)
                        .heightIn(min = 80.dp)
                        .background(NostrordColors.BackgroundDark, RoundedCornerShape(NostrordShapes.radiusMedium)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
