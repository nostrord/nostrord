package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.utils.getImageUrl
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.ui.components.avatars.OptimizedUserAvatar
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
// New type aliases for rich text formatting
private typealias BoldPart = MessageContentParser.ParsedPart.Bold
private typealias ItalicPart = MessageContentParser.ParsedPart.Italic
private typealias MonospacePart = MessageContentParser.ParsedPart.Monospace
private typealias CodeBlockPart = MessageContentParser.ParsedPart.CodeBlock
private typealias HashtagPart = MessageContentParser.ParsedPart.Hashtag
private typealias VideoPart = MessageContentParser.ParsedPart.Video
private typealias AudioPart = MessageContentParser.ParsedPart.Audio
// Nostr-specific type aliases
private typealias RelayPart = MessageContentParser.ParsedPart.Relay
private typealias CashuPart = MessageContentParser.ParsedPart.Cashu
private typealias CashuRequestPart = MessageContentParser.ParsedPart.CashuRequest

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
        is CodeBlockPart -> true
        is VideoPart -> true
        is AudioPart -> true
        is RelayPart -> true
        is CashuPart -> true
        is CashuRequestPart -> true
        is MentionPart -> {
            // Quoted events (nevent, note, naddr) are block elements
            when (part.reference.entity) {
                is Nip19.Entity.Nevent, is Nip19.Entity.Note, is Nip19.Entity.Naddr -> true
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
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {},
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> }
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
                    is CodeBlockPart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        CodeBlockContent(
                            code = firstPart.code,
                            language = firstPart.language
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is VideoPart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        VideoContent(
                            url = firstPart.url,
                            videoId = firstPart.videoId,
                            onClick = {
                                try {
                                    uriHandler.openUri(firstPart.url)
                                } catch (_: Exception) {}
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is AudioPart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        AudioContent(
                            url = firstPart.url,
                            onClick = {
                                try {
                                    uriHandler.openUri(firstPart.url)
                                } catch (_: Exception) {}
                            }
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
                            },
                            currentGroupId = currentGroupId,
                            currentRelayUrl = currentRelayUrl,
                            onNavigateToGroup = onNavigateToGroup
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is RelayPart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        RelayContent(
                            url = firstPart.url,
                            onClick = {
                                // Open relay URL in browser (http version for viewing)
                                try {
                                    val httpUrl = firstPart.url
                                        .replace("wss://", "https://")
                                        .replace("ws://", "http://")
                                    uriHandler.openUri(httpUrl)
                                } catch (_: Exception) {}
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is CashuPart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        CashuContent(
                            token = firstPart.token,
                            isRequest = false
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    is CashuRequestPart -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        CashuContent(
                            token = firstPart.request,
                            isRequest = true
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
                    onMentionClick = onMentionClick,
                    onHashtagClick = onHashtagClick
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
 * ## Custom Emoji Strategy: Conditional Selection Disabling
 *
 * InlineTextContent with images causes crashes in Compose Desktop/Skiko when
 * text selection intersects with inline content placeholders. The crash occurs
 * in SkiaParagraph.getLineForOffset() which returns null for placeholder offsets.
 *
 * **Solution**: When a message contains custom emojis:
 * 1. Wrap the Text in DisableSelection to prevent the crash
 * 2. Use InlineTextContent to render actual emoji images
 *
 * When a message has NO custom emojis:
 * - Render normally with full text selection support
 *
 * This gives us the best of both worlds:
 * - Emoji images display correctly
 * - No crashes
 * - Text selection works for messages without emojis
 */
@Composable
private fun InlineContentGroup(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    // Check if this group contains any custom emojis
    val hasCustomEmojis = remember(parts) {
        parts.any { it is CustomEmojiPart }
    }

    if (hasCustomEmojis) {
        // Messages with emojis: disable selection to prevent crash, but show images
        DisableSelection {
            InlineContentWithEmojis(
                parts = parts,
                userMetadata = userMetadata,
                modifier = modifier,
                onMentionClick = onMentionClick,
                onHashtagClick = onHashtagClick
            )
        }
    } else {
        // Messages without emojis: full selection support
        InlineContentTextOnly(
            parts = parts,
            userMetadata = userMetadata,
            modifier = modifier,
            onMentionClick = onMentionClick,
            onHashtagClick = onHashtagClick
        )
    }
}

/**
 * Renders inline content WITH custom emoji images.
 * Must be wrapped in DisableSelection to prevent crashes.
 */
@Composable
private fun InlineContentWithEmojis(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    // Build inline content map for custom emojis with unique sequential IDs
    val inlineContentMap = remember(parts) {
        var emojiIndex = 0
        parts.filterIsInstance<CustomEmojiPart>()
            .associate { emoji ->
                val id = "emoji_${emojiIndex++}_${emoji.shortcode}"
                id to InlineTextContent(
                    placeholder = Placeholder(
                        width = 22.sp,
                        height = 22.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    SafeEmojiImage(
                        shortcode = emoji.shortcode,
                        imageUrl = emoji.imageUrl
                    )
                }
            }
    }

    // Build the annotated string with matching sequential emoji IDs
    val annotatedString = remember(parts, userMetadata) {
        var emojiIndex = 0
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is TextPart -> append(part.content)
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
                        val entity = part.reference.entity
                        // For user mentions (npub/nprofile), use clickable to open profile modal
                        // For other mentions (nevent, note), use URL to open in external handler
                        val pubkey = when (entity) {
                            is Nip19.Entity.Npub -> entity.pubkey
                            is Nip19.Entity.Nprofile -> entity.pubkey
                            else -> null
                        }
                        if (pubkey != null) {
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
                    is CustomEmojiPart -> {
                        appendInlineContent(
                            "emoji_${emojiIndex++}_${part.shortcode}",
                            ":${part.shortcode}:"
                        )
                    }
                    // Text formatting
                    is BoldPart -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(part.content)
                        }
                    }
                    is ItalicPart -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(part.content)
                        }
                    }
                    is MonospacePart -> {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = NostrordColors.CodeBackground.copy(alpha = 0.5f),
                            color = NostrordColors.CodeText
                        )) {
                            append(part.content)
                        }
                    }
                    is HashtagPart -> {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "hashtag_${part.tag}",
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = NostrordColors.HashtagText)
                                ),
                                linkInteractionListener = { onHashtagClick(part.tag) }
                            )
                        ) {
                            append("#${part.tag}")
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
        style = NostrordTypography.MessageBody,
        inlineContent = inlineContentMap,
        modifier = modifier
    )
}

/**
 * Renders inline content WITHOUT custom emojis.
 * Supports full text selection.
 */
@Composable
private fun InlineContentTextOnly(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier,
    onMentionClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    val annotatedString = remember(parts, userMetadata) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part) {
                    is TextPart -> append(part.content)
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
                        val entity = part.reference.entity
                        // For user mentions (npub/nprofile), use clickable to open profile modal
                        // For other mentions (nevent, note), use URL to open in external handler
                        val pubkey = when (entity) {
                            is Nip19.Entity.Npub -> entity.pubkey
                            is Nip19.Entity.Nprofile -> entity.pubkey
                            else -> null
                        }
                        if (pubkey != null) {
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
                    // Text formatting
                    is BoldPart -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(part.content)
                        }
                    }
                    is ItalicPart -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(part.content)
                        }
                    }
                    is MonospacePart -> {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = NostrordColors.CodeBackground.copy(alpha = 0.5f),
                            color = NostrordColors.CodeText
                        )) {
                            append(part.content)
                        }
                    }
                    is HashtagPart -> {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "hashtag_${part.tag}",
                                styles = TextLinkStyles(
                                    style = SpanStyle(color = NostrordColors.HashtagText)
                                ),
                                linkInteractionListener = { onHashtagClick(part.tag) }
                            )
                        ) {
                            append("#${part.tag}")
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
        style = NostrordTypography.MessageBody,
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

/**
 * Process content to replace nostr: mentions with @name format.
 * Used for plain text display in embedded events like reply previews.
 */
fun processMentionsInContent(
    content: String,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>
): String {
    // Regex to match nostr: URIs (npub, nprofile, note, nevent, naddr)
    val nostrUriRegex = Regex("""nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""", RegexOption.IGNORE_CASE)

    return nostrUriRegex.replace(content) { matchResult ->
        val uri = matchResult.value
        val bech32 = matchResult.groupValues[1]

        try {
            val entity = Nip19.decode(bech32)
            when (entity) {
                is Nip19.Entity.Npub -> {
                    val metadata = userMetadata[entity.pubkey]
                    val name = metadata?.displayName ?: metadata?.name
                    if (name != null) "@$name" else "@${entity.pubkey.take(8)}..."
                }
                is Nip19.Entity.Nprofile -> {
                    val metadata = userMetadata[entity.pubkey]
                    val name = metadata?.displayName ?: metadata?.name
                    if (name != null) "@$name" else "@${entity.pubkey.take(8)}..."
                }
                is Nip19.Entity.Note -> "[note]"
                is Nip19.Entity.Nevent -> "[event]"
                is Nip19.Entity.Naddr -> "[article]"
                else -> uri
            }
        } catch (_: Exception) {
            uri // Return original if decoding fails
        }
    }
}

/**
 * Extract all pubkeys from nostr: mentions in content.
 * Used to request metadata for mentioned users.
 */
fun extractPubkeysFromContent(content: String): List<String> {
    val nostrUriRegex = Regex("""nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+)""", RegexOption.IGNORE_CASE)

    return nostrUriRegex.findAll(content).mapNotNull { matchResult ->
        val bech32 = matchResult.groupValues[1]
        try {
            when (val entity = Nip19.decode(bech32)) {
                is Nip19.Entity.Npub -> entity.pubkey
                is Nip19.Entity.Nprofile -> entity.pubkey
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }.toList()
}

// =============================================================================
// SAFE EMOJI IMAGE RENDERING
// =============================================================================
//
// Custom emojis are rendered as inline images using InlineTextContent.
// To prevent crashes from SelectionContainer + InlineTextContent interaction,
// messages containing emojis are wrapped in DisableSelection.
//
// Key safety measures:
// 1. URL validation before image loading
// 2. Size constraints to prevent OOM
// 3. Error state with text fallback
// 4. No exceptions can escape to parent composables
// =============================================================================

/**
 * Safe emoji image renderer with comprehensive error handling.
 *
 * Renders custom emoji as an inline image, or falls back to text on failure.
 * Size is fixed at 22dp to match line height.
 */
@Composable
private fun SafeEmojiImage(
    shortcode: String,
    imageUrl: String,
    modifier: Modifier = Modifier
) {
    // Validate and sanitize inputs
    val safeShortcode = remember(shortcode) {
        shortcode.take(32).filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .ifEmpty { "emoji" }
    }

    val isValidUrl = remember(imageUrl) {
        imageUrl.isNotBlank() &&
        imageUrl.length <= 2048 &&
        (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) &&
        !imageUrl.lowercase().contains("javascript:") &&
        !imageUrl.lowercase().contains("data:")
    }

    // Track error state
    var showFallback by remember(imageUrl) { mutableStateOf(!isValidUrl) }

    if (showFallback) {
        // Text fallback - always safe
        Box(
            modifier = modifier.size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ":$safeShortcode:",
                color = NostrordColors.Primary,
                style = NostrordTypography.Caption,
                maxLines = 1
            )
        }
    } else {
        val context = LocalPlatformContext.current

        // Build image request with safety constraints
        val imageRequest = remember(imageUrl, context) {
            runCatching {
                ImageRequest.Builder(context)
                    .data(getImageUrl(imageUrl))
                    .crossfade(false) // Disable animations for stability
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(Size(44, 44)) // 2x for retina displays
                    .build()
            }.getOrNull()
        }

        if (imageRequest == null) {
            // Failed to build request - show fallback
            showFallback = true
            return
        }

        Box(
            modifier = modifier.size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageRequest,
                contentDescription = ":$safeShortcode:",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.size(22.dp),
                onState = { state: AsyncImagePainter.State ->
                    if (state is AsyncImagePainter.State.Error) {
                        showFallback = true
                    }
                }
            )
        }
    }
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
                    .data(getImageUrl(imageUrl))
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
    modifier: Modifier = Modifier,
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> }
) {
    val entity = mention.reference.entity

    when (entity) {
        is Nip19.Entity.Nevent -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = entity.relays,
                author = entity.author,
                kind = entity.kind,
                onClick = onClick,
                modifier = modifier,
                currentGroupId = currentGroupId,
                currentRelayUrl = currentRelayUrl,
                onNavigateToGroup = onNavigateToGroup
            )
        }
        is Nip19.Entity.Note -> {
            QuotedEvent(
                eventId = entity.eventId,
                relayHints = emptyList(),
                author = null,
                kind = null,
                onClick = onClick,
                modifier = modifier,
                currentGroupId = currentGroupId,
                currentRelayUrl = currentRelayUrl,
                onNavigateToGroup = onNavigateToGroup
            )
        }
        is Nip19.Entity.Naddr -> {
            AddressableEvent(
                identifier = entity.identifier,
                pubkey = entity.pubkey,
                kind = entity.kind,
                relayHints = entity.relays,
                onClick = onClick,
                modifier = modifier
            )
        }
        else -> {}
    }
}

/**
 * Extracts source group info from an event's h tag.
 * Returns (groupId, relayUrl) if found, null otherwise.
 */
private fun extractGroupFromEvent(event: CachedEvent): Pair<String, String?>? {
    val hTag = event.tags.find { it.firstOrNull() == "h" } ?: return null
    val groupId = hTag.getOrNull(1) ?: return null
    val relayUrl = hTag.getOrNull(2)
    return groupId to relayUrl
}

/**
 * Forwarded event card - displays an event that originated from a different group.
 * Shows "forwarded from [group name]" header with group info, author info,
 * optional reply preview (if q tag exists), and the message content.
 *
 * Clicking the header navigates to the source group:
 * - If on same relay: navigates directly to the group
 * - If on different relay: switches relay first, then navigates to group
 */
@Composable
fun ForwardedEventCard(
    event: CachedEvent,
    sourceGroupId: String,
    sourceGroupName: String?,
    sourceGroupPicture: String?,
    sourceRelayUrl: String?,
    onClick: () -> Unit,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val cachedEvents by NostrRepository.cachedEvents.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    val authorMetadata = userMetadata[event.pubkey]
    val authorName = authorMetadata?.displayName ?: authorMetadata?.name ?: event.pubkey.take(8) + "..."

    // Check for reply (q tag) - indicates this event is a reply to another
    val replyEventId = event.tags.find { it.firstOrNull() == "q" }?.getOrNull(1)
    val replyEvent = replyEventId?.let { cachedEvents[it] }

    // Request reply event if we have a q tag but don't have the event cached
    LaunchedEffect(replyEventId) {
        if (replyEventId != null && !cachedEvents.containsKey(replyEventId)) {
            // Extract relay hint from q tag if available
            val qTag = event.tags.find { it.firstOrNull() == "q" }
            val relayHint = qTag?.getOrNull(2)?.let { listOf(it) } ?: emptyList()
            NostrRepository.requestEventById(replyEventId, relayHint, null)
        }
    }

    // Request author metadata
    LaunchedEffect(event.pubkey) {
        if (!userMetadata.containsKey(event.pubkey)) {
            NostrRepository.requestUserMetadata(setOf(event.pubkey))
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
    ) {
        // Forwarded header - clickable to navigate to source group
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onNavigateToGroup(sourceGroupId, sourceGroupName, sourceRelayUrl)
                }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // Forward arrow icon
            Text(
                text = "↪",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption
            )
            Text(
                text = "forwarded from",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption
            )
            // Group avatar (small)
            if (sourceGroupPicture != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(getImageUrl(sourceGroupPicture))
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .size(Size(32, 32))
                        .build(),
                    contentDescription = "Group avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
            // Group name (with underline on hover style)
            Text(
                text = sourceGroupName ?: sourceGroupId.take(12) + "...",
                color = NostrordColors.Primary,
                style = NostrordTypography.Caption,
                fontWeight = FontWeight.Medium
            )
            // Relay indicator if available
            if (sourceRelayUrl != null) {
                Text(
                    text = "@ ${sourceRelayUrl.removePrefix("wss://").removePrefix("ws://").take(20)}",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
            }
        }

        // Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NostrordColors.Background)
        )

        // Main content area
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md)
        ) {
            // Author row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OptimizedUserAvatar(
                    imageUrl = authorMetadata?.picture,
                    pubkey = event.pubkey,
                    displayName = authorName,
                    size = 24.dp
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = authorName,
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(event.createdAt),
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                // 3-dot menu button
                DisableSelection {
                    Box {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "More options",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { showMenu = true }
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Event JSON") },
                                onClick = {
                                    val json = buildJsonObject {
                                        put("id", event.id)
                                        put("pubkey", event.pubkey)
                                        put("created_at", event.createdAt)
                                        put("kind", event.kind)
                                        put("tags", buildJsonArray {
                                            event.tags.forEach { tag ->
                                                add(buildJsonArray {
                                                    tag.forEach { add(JsonPrimitive(it)) }
                                                })
                                            }
                                        })
                                        put("content", event.content)
                                    }.toString()
                                    clipboardManager.setText(AnnotatedString(json))
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Reply preview (if q tag exists and we have the parent event)
            if (replyEvent != null) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                ReplyPreview(
                    parentEvent = replyEvent,
                    userMetadata = userMetadata
                )
            } else if (replyEventId != null) {
                // Show loading state for reply
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
                        .background(NostrordColors.Background)
                        .padding(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "↳",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = "Loading reply...",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
            }

            // Message content
            Spacer(modifier = Modifier.height(Spacing.sm))
            QuotedEventContent(content = event.content, tags = event.tags)
        }
    }
}

/**
 * Reply preview bar - shows the parent message being replied to.
 */
@Composable
private fun ReplyPreview(
    parentEvent: CachedEvent,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    modifier: Modifier = Modifier
) {
    val parentAuthorMetadata = userMetadata[parentEvent.pubkey]
    val parentAuthorName = parentAuthorMetadata?.displayName
        ?: parentAuthorMetadata?.name
        ?: parentEvent.pubkey.take(8) + "..."

    // Request metadata for any pubkeys mentioned in the content
    LaunchedEffect(parentEvent.content) {
        val pubkeysToFetch = extractPubkeysFromContent(parentEvent.content)
            .filter { !userMetadata.containsKey(it) }
            .toSet()
        if (pubkeysToFetch.isNotEmpty()) {
            NostrRepository.requestUserMetadata(pubkeysToFetch)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusSmall))
            .background(NostrordColors.Background)
            .padding(Spacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        // Reply arrow
        Text(
            text = "↳",
            color = NostrordColors.TextMuted,
            style = NostrordTypography.Caption
        )
        Spacer(modifier = Modifier.width(Spacing.xs))

        // Small avatar
        OptimizedUserAvatar(
            imageUrl = parentAuthorMetadata?.picture,
            pubkey = parentEvent.pubkey,
            displayName = parentAuthorName,
            size = 16.dp
        )
        Spacer(modifier = Modifier.width(Spacing.xs))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = parentAuthorName,
                color = NostrordColors.TextSecondary,
                style = NostrordTypography.Caption,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            // Process mentions in content to show @name instead of nostr:npub...
            val processedContent = remember(parentEvent.content, userMetadata) {
                processMentionsInContent(parentEvent.content, userMetadata)
            }
            Text(
                text = processedContent.take(100) + if (processedContent.length > 100) "..." else "",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun QuotedEvent(
    eventId: String,
    relayHints: List<String> = emptyList(),
    author: String? = null,
    kind: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    currentGroupId: String? = null,
    currentRelayUrl: String? = null,
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> }
) {
    val cachedEvents by NostrRepository.cachedEvents.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val groups by NostrRepository.groups.collectAsState()
    val event = cachedEvents[eventId]

    // Track if event was not found after timeout
    var eventNotFound by remember { mutableStateOf(false) }

    LaunchedEffect(eventId, relayHints, author) {
        if (!cachedEvents.containsKey(eventId)) {
            NostrRepository.requestEventById(eventId, relayHints, author)
            // Wait 5 seconds before marking as not found
            kotlinx.coroutines.delay(5000)
            if (!cachedEvents.containsKey(eventId)) {
                eventNotFound = true
            }
        }
    }

    // Reset not found state when event arrives
    LaunchedEffect(event) {
        if (event != null) {
            eventNotFound = false
        }
    }

    LaunchedEffect(event?.pubkey) {
        val pubkey = event?.pubkey
        if (pubkey != null && !userMetadata.containsKey(pubkey)) {
            NostrRepository.requestUserMetadata(setOf(pubkey))
        }
    }

    // Check if this is a kind 30040 (book) event - render as BookCard
    if (event?.kind == 30040) {
        val metadata = userMetadata[event.pubkey]
        val authorName = metadata?.displayName ?: metadata?.name ?: event.pubkey.take(8) + "..."

        BookCard(
            event = event,
            authorName = authorName,
            authorPicture = metadata?.picture,
            pubkey = event.pubkey,
            onClick = onClick,
            modifier = modifier
        )
        return
    }

    // Check if this event is from a different group (forwarded)
    if (event != null) {
        val sourceGroupInfo = extractGroupFromEvent(event)
        if (sourceGroupInfo != null) {
            val (sourceGroupId, sourceRelayUrl) = sourceGroupInfo

            // Determine if this is a forwarded event
            // It's forwarded if: we have a current group context AND the source group is different
            // OR if the source relay is different from current relay
            val isFromDifferentGroup = when {
                currentGroupId != null && sourceGroupId != currentGroupId -> true
                currentRelayUrl != null && sourceRelayUrl != null &&
                    sourceRelayUrl != currentRelayUrl -> true
                // If no current context provided, assume it's a quote from same group (not forwarded)
                else -> false
            }

            if (isFromDifferentGroup) {
                // Look up group metadata for source group
                val sourceGroup = groups.find { it.id == sourceGroupId }

                ForwardedEventCard(
                    event = event,
                    sourceGroupId = sourceGroupId,
                    sourceGroupName = sourceGroup?.name,
                    sourceGroupPicture = sourceGroup?.picture,
                    sourceRelayUrl = sourceRelayUrl,
                    onClick = onClick,
                    onNavigateToGroup = onNavigateToGroup,
                    modifier = modifier
                )
                return
            }
        }
    }

    // Event not found - non-clickable with selectable text showing full parsed content
    if (event == null && eventNotFound) {
        val clipboardManager = LocalClipboardManager.current
        var showMenu by remember { mutableStateOf(false) }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.Surface)
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = "⚠",
                        style = NostrordTypography.MessageBody,
                        color = NostrordColors.TextMuted
                    )
                    Text(
                        text = "Event not found",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
                // Show full parsed content
                if (kind != null) {
                    Text(
                        text = "kind: $kind",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
                Text(
                    text = "id: $eventId",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
                if (author != null) {
                    Text(
                        text = "author: $author",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
                if (relayHints.isNotEmpty()) {
                    Text(
                        text = "relays: ${relayHints.joinToString(", ")}",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
            }
            // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict with parent SelectionContainer
            DisableSelection {
                Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "More options",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { showMenu = true }
                            .pointerHoverIcon(PointerIcon.Hand)
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Parsed JSON") },
                            onClick = {
                                val json = buildJsonObject {
                                    put("type", if (kind != null) "nevent" else "note")
                                    put("event_id", eventId)
                                    if (kind != null) put("kind", kind)
                                    if (author != null) put("author", author)
                                    put("relays", buildJsonArray {
                                        relayHints.forEach { add(JsonPrimitive(it)) }
                                    })
                                }.toString()
                                clipboardManager.setText(AnnotatedString(json))
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
        return
    }

    // Still loading
    if (event == null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.Surface)
                .padding(Spacing.md)
        ) {
            Text(
                text = "Loading event...",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Caption
            )
        }
        return
    }

    // Default rendering for other event kinds (clickable)
    val metadata = userMetadata[event.pubkey]
    val authorName = metadata?.displayName ?: metadata?.name ?: event.pubkey.take(8) + "..."
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptimizedUserAvatar(
                    imageUrl = metadata?.picture,
                    pubkey = event.pubkey,
                    displayName = authorName,
                    size = 24.dp
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = authorName,
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                DisableSelection {
                    Box {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "More options",
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { showMenu = true }
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Copy Event JSON") },
                                onClick = {
                                    val json = buildJsonObject {
                                        put("id", event.id)
                                        put("pubkey", event.pubkey)
                                        put("created_at", event.createdAt)
                                        put("kind", event.kind)
                                        put("tags", buildJsonArray {
                                            event.tags.forEach { tag ->
                                                add(buildJsonArray {
                                                    tag.forEach { add(JsonPrimitive(it)) }
                                                })
                                            }
                                        })
                                        put("content", event.content)
                                    }.toString()
                                    clipboardManager.setText(AnnotatedString(json))
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.sm))
            QuotedEventContent(content = event.content, tags = event.tags)
        }
    }
}

/**
 * Content inside a quoted event - uses same AnnotatedString approach.
 */
@Composable
private fun QuotedEventContent(
    content: String,
    tags: List<List<String>> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Extract custom emoji map from NIP-30 tags
    val emojiMap = remember(tags) { MessageContentParser.extractEmojiMap(tags) }
    val parts = remember(content, emojiMap) { parseContent(content, emojiMap) }
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
 * Supports custom emojis via InlineTextContent.
 */
@Composable
private fun QuotedInlineContentGroup(
    parts: List<ContentPart>,
    userMetadata: Map<String, org.nostr.nostrord.network.UserMetadata>,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Check if this group contains any custom emojis
    val hasCustomEmojis = remember(parts) {
        parts.any { it is CustomEmojiPart }
    }

    // Build inline content map for custom emojis with unique sequential IDs
    val inlineContentMap = remember(parts) {
        if (!hasCustomEmojis) return@remember emptyMap()
        var emojiIndex = 0
        parts.filterIsInstance<CustomEmojiPart>()
            .associate { emoji ->
                val id = "quoted_emoji_${emojiIndex++}_${emoji.shortcode}"
                id to InlineTextContent(
                    placeholder = Placeholder(
                        width = 18.sp, // Smaller for caption style
                        height = 18.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    SafeEmojiImage(
                        shortcode = emoji.shortcode,
                        imageUrl = emoji.imageUrl
                    )
                }
            }
    }

    val annotatedString = remember(parts, userMetadata) {
        var emojiIndex = 0
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
                    is CustomEmojiPart -> {
                        appendInlineContent(
                            "quoted_emoji_${emojiIndex++}_${part.shortcode}",
                            ":${part.shortcode}:"
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    if (hasCustomEmojis) {
        // Wrap in DisableSelection to prevent crashes with InlineTextContent
        DisableSelection {
            Text(
                text = annotatedString,
                color = NostrordColors.TextContent,
                style = NostrordTypography.Caption,
                inlineContent = inlineContentMap,
                maxLines = 6,
                modifier = modifier
            )
        }
    } else {
        Text(
            text = annotatedString,
            color = NostrordColors.TextContent,
            style = NostrordTypography.Caption,
            maxLines = 6,
            modifier = modifier
        )
    }
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
                    .data(getImageUrl(imageUrl))
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

// ============================================================================
// BLOCK COMPOSABLES FOR NEW RICH TEXT TYPES
// ============================================================================

/**
 * Renders a fenced code block with optional language label.
 */
@Composable
private fun CodeBlockContent(
    code: String,
    language: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(NostrordColors.CodeBackground)
    ) {
        // Language badge (if present)
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        // Code content (horizontally scrollable for long lines)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = code,
                style = NostrordTypography.MessageBody.copy(fontFamily = FontFamily.Monospace),
                color = NostrordColors.CodeText,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

/**
 * Renders a video thumbnail with play button overlay.
 * For YouTube, shows the thumbnail; for other videos, shows a placeholder.
 */
@Composable
private fun VideoContent(
    url: String,
    videoId: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalPlatformContext.current
    val thumbnailUrl = videoId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl != null) {
            // YouTube video - show thumbnail
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = "Video thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )
        } else {
            // Generic video - show placeholder with aspect ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(NostrordColors.SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = url.substringAfterLast("/").take(50),
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted
                )
            }
        }

        // Play button overlay
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(NostrordColors.Background.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "▶",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextPrimary
            )
        }
    }
}

/**
 * Renders an audio file link with a simple player-style UI.
 */
@Composable
private fun AudioContent(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Audio icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NostrordColors.Primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "♪",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.Primary
            )
        }
        Spacer(Modifier.width(12.dp))
        // File name
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = url.substringAfterLast("/").take(40),
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                maxLines = 1
            )
            Text(
                text = "Tap to open",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted
            )
        }
    }
}

/**
 * Renders a Nostr relay URL with a relay icon.
 */
@Composable
private fun RelayContent(
    url: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Relay icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(NostrordColors.Primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "⚡",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.Primary
            )
        }
        Spacer(Modifier.width(12.dp))
        // Relay URL
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = url,
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                maxLines = 1
            )
            Text(
                text = "Nostr Relay",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted
            )
        }
    }
}

/**
 * Renders a Cashu ecash token or payment request.
 */
@Composable
private fun CashuContent(
    token: String,
    isRequest: Boolean,
    modifier: Modifier = Modifier
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant)
            .clickable {
                // Copy token to clipboard
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(token))
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Cashu icon
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (isRequest) NostrordColors.MentionText.copy(alpha = 0.2f)
                    else NostrordColors.Primary.copy(alpha = 0.2f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "🥜",
                style = NostrordTypography.MessageBody
            )
        }
        Spacer(Modifier.width(12.dp))
        // Token preview
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isRequest) "Cashu Request" else "Cashu Token",
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextContent,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = token.take(32) + "...",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
                maxLines = 1
            )
            Text(
                text = "Tap to copy",
                style = NostrordTypography.Caption,
                color = NostrordColors.TextLink
            )
        }
    }
}

/**
 * Renders an addressable event (naddr) as a card.
 * Addressable events include profiles, articles, live streams, etc.
 */
@Composable
private fun AddressableEvent(
    identifier: String,
    pubkey: String,
    kind: Int,
    relayHints: List<String> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cachedEvents by NostrRepository.cachedEvents.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()

    // Create a composite key for addressable events: kind:pubkey:identifier
    val addressKey = "$kind:$pubkey:$identifier"
    val event = cachedEvents[addressKey]

    // Track if event was not found after timeout
    var eventNotFound by remember { mutableStateOf(false) }

    // Request the addressable event
    LaunchedEffect(addressKey, relayHints) {
        if (!cachedEvents.containsKey(addressKey)) {
            NostrRepository.requestAddressableEvent(
                kind = kind,
                pubkey = pubkey,
                identifier = identifier,
                relays = relayHints
            )
            // Wait 5 seconds before marking as not found
            kotlinx.coroutines.delay(5000)
            if (!cachedEvents.containsKey(addressKey)) {
                eventNotFound = true
            }
        }
    }

    // Reset not found state when event arrives
    LaunchedEffect(event) {
        if (event != null) {
            eventNotFound = false
        }
    }

    // Request author metadata
    LaunchedEffect(pubkey) {
        if (!userMetadata.containsKey(pubkey)) {
            NostrRepository.requestUserMetadata(setOf(pubkey))
        }
    }

    // If event not found after timeout, show error card with full parsed content (non-clickable, selectable)
    if (event == null && eventNotFound) {
        val clipboardManager = LocalClipboardManager.current
        var showMenu by remember { mutableStateOf(false) }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
                .background(NostrordColors.Surface)
                .padding(Spacing.md),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = "⚠",
                        style = NostrordTypography.MessageBody,
                        color = NostrordColors.TextMuted
                    )
                    Text(
                        text = "Event not found",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
                // Show full parsed naddr content
                Text(
                    text = "kind: $kind",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
                Text(
                    text = "pubkey: $pubkey",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
                Text(
                    text = "d: $identifier",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
                if (relayHints.isNotEmpty()) {
                    Text(
                        text = "relays: ${relayHints.joinToString(", ")}",
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )
                }
            }
            // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
            DisableSelection {
                Box {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "More options",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { showMenu = true }
                            .pointerHoverIcon(PointerIcon.Hand)
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy Parsed JSON") },
                            onClick = {
                                val json = buildJsonObject {
                                    put("type", "naddr")
                                    put("identifier", identifier)
                                    put("pubkey", pubkey)
                                    put("kind", kind)
                                    put("relays", buildJsonArray {
                                        relayHints.forEach { add(JsonPrimitive(it)) }
                                    })
                                }.toString()
                                clipboardManager.setText(AnnotatedString(json))
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
        return
    }

    val metadata = userMetadata[pubkey]
    val authorName = metadata?.displayName ?: metadata?.name ?: pubkey.take(8) + "..."

    // Render different card styles based on kind
    when (kind) {
        0 -> {
            // Kind 0: User profile metadata
            ProfileCard(
                metadata = metadata,
                pubkey = pubkey,
                onClick = onClick,
                modifier = modifier
            )
        }
        30023 -> {
            // Kind 30023: Long-form content (articles)
            ArticleCard(
                event = event,
                authorName = authorName,
                authorPicture = metadata?.picture,
                onClick = onClick,
                modifier = modifier
            )
        }
        30040 -> {
            // Kind 30040: Books/publications
            BookCard(
                event = event,
                authorName = authorName,
                authorPicture = metadata?.picture,
                pubkey = pubkey,
                onClick = onClick,
                modifier = modifier
            )
        }
        else -> {
            // Generic addressable event card
            GenericAddressableCard(
                event = event,
                authorName = authorName,
                authorPicture = metadata?.picture,
                kind = kind,
                identifier = identifier,
                onClick = onClick,
                modifier = modifier
            )
        }
    }
}

/**
 * Profile card for kind 0 (user metadata) naddr
 */
@Composable
private fun ProfileCard(
    metadata: org.nostr.nostrord.network.UserMetadata?,
    pubkey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NostrordShapes.radiusMedium))
            .background(NostrordColors.Surface)
            .clickable(onClick = onClick)
            .padding(Spacing.md)
    ) {
        // Avatar
        OptimizedUserAvatar(
            imageUrl = metadata?.picture,
            pubkey = pubkey,
            displayName = metadata?.displayName ?: metadata?.name ?: "Unknown",
            size = 64.dp
        )

        Spacer(modifier = Modifier.width(Spacing.md))

        // Profile info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metadata?.displayName ?: metadata?.name ?: pubkey.take(8) + "...",
                color = NostrordColors.TextPrimary,
                style = NostrordTypography.MessageBody,
                fontWeight = FontWeight.Bold
            )

            if (metadata?.nip05 != null) {
                Text(
                    text = metadata.nip05,
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption
                )
            }

            if (metadata?.about != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = metadata.about,
                    color = NostrordColors.TextContent,
                    style = NostrordTypography.Caption,
                    maxLines = 3
                )
            }
        }
    }
}

/**
 * Article card for kind 30023 (long-form content)
 */
@Composable
private fun ArticleCard(
    event: CachedEvent?,
    authorName: String,
    authorPicture: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

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
                // Extract title from tags
                val title = event.tags.find { it.firstOrNull() == "title" }?.getOrNull(1)
                val summary = event.tags.find { it.firstOrNull() == "summary" }?.getOrNull(1)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptimizedUserAvatar(
                        imageUrl = authorPicture,
                        pubkey = event.pubkey,
                        displayName = authorName,
                        size = 20.dp
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = authorName,
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                    DisableSelection {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextMuted,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { showMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Event JSON") },
                                    onClick = {
                                        val json = buildJsonObject {
                                            put("id", event.id)
                                            put("pubkey", event.pubkey)
                                            put("created_at", event.createdAt)
                                            put("kind", event.kind)
                                            put("tags", buildJsonArray {
                                                event.tags.forEach { tag ->
                                                    add(buildJsonArray {
                                                        tag.forEach { add(JsonPrimitive(it)) }
                                                    })
                                                }
                                            })
                                            put("content", event.content)
                                        }.toString()
                                        clipboardManager.setText(AnnotatedString(json))
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (title != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = title,
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.MessageBody,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                }

                if (summary != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = summary,
                        color = NostrordColors.TextContent,
                        style = NostrordTypography.Caption,
                        maxLines = 3
                    )
                }
            } else {
                Text(
                    text = "Loading article...",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
            }
        }
    }
}

/**
 * Book card for kind 30040 (books/publications)
 * Displays title, author credit, and publication metadata.
 */
@Composable
private fun BookCard(
    event: CachedEvent?,
    authorName: String,
    authorPicture: String?,
    pubkey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

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
                // Extract title and author from tags
                val title = event.tags.find { it.firstOrNull() == "title" }?.getOrNull(1)
                val bookAuthor = event.tags.find { it.firstOrNull() == "author" }?.getOrNull(1)

                // Header row with avatar, name, date, and menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Author info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        OptimizedUserAvatar(
                            imageUrl = authorPicture,
                            pubkey = pubkey,
                            displayName = authorName,
                            size = 24.dp
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        Text(
                            text = authorName,
                            color = NostrordColors.TextSecondary,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Date
                    Text(
                        text = formatTime(event.createdAt),
                        color = NostrordColors.TextMuted,
                        style = NostrordTypography.Caption
                    )

                    Spacer(modifier = Modifier.width(Spacing.xs))

                    // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                    DisableSelection {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextMuted,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { showMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Event JSON") },
                                    onClick = {
                                        val json = buildJsonObject {
                                            put("id", event.id)
                                            put("pubkey", event.pubkey)
                                            put("created_at", event.createdAt)
                                            put("kind", event.kind)
                                            put("tags", buildJsonArray {
                                                event.tags.forEach { tag ->
                                                    add(buildJsonArray {
                                                        tag.forEach { add(JsonPrimitive(it)) }
                                                    })
                                                }
                                            })
                                            put("content", event.content)
                                        }.toString()
                                        clipboardManager.setText(AnnotatedString(json))
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Book content
                if (title != null) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        text = title,
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.MessageBody.copy(fontSize = 18.sp),
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                }

                if (bookAuthor != null) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "by $bookAuthor",
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption
                    )
                }
            } else {
                Text(
                    text = "Loading book...",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
            }
        }
    }
}

/**
 * Generic card for other addressable event kinds
 */
@Composable
private fun GenericAddressableCard(
    event: CachedEvent?,
    authorName: String,
    authorPicture: String?,
    kind: Int,
    identifier: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OptimizedUserAvatar(
                        imageUrl = authorPicture,
                        pubkey = event.pubkey,
                        displayName = authorName,
                        size = 24.dp
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = authorName,
                            color = NostrordColors.TextSecondary,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Kind $kind event",
                            color = NostrordColors.TextMuted,
                            style = NostrordTypography.Caption
                        )
                    }
                    // 3-dot menu button - wrapped in DisableSelection to avoid hierarchy conflict
                    DisableSelection {
                        Box {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextMuted,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { showMenu = true }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            )
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Copy Event JSON") },
                                    onClick = {
                                        val json = buildJsonObject {
                                            put("id", event.id)
                                            put("pubkey", event.pubkey)
                                            put("created_at", event.createdAt)
                                            put("kind", event.kind)
                                            put("tags", buildJsonArray {
                                                event.tags.forEach { tag ->
                                                    add(buildJsonArray {
                                                        tag.forEach { add(JsonPrimitive(it)) }
                                                    })
                                                }
                                            })
                                            put("content", event.content)
                                        }.toString()
                                        clipboardManager.setText(AnnotatedString(json))
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = event.content,
                    color = NostrordColors.TextContent,
                    style = NostrordTypography.Caption,
                    maxLines = 6
                )
            } else {
                Text(
                    text = "Loading event (kind $kind, id: ${identifier.take(12)}...)",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Caption
                )
            }
        }
    }
}
