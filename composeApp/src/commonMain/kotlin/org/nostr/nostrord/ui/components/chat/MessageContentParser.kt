package org.nostr.nostrord.ui.components.chat

import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27

/**
 * # Chat Message Content Parser
 *
 * A robust, deterministic parser for chat messages containing mixed content:
 * - URLs (http/https with query strings, fragments, special characters)
 * - Nostr mentions (nostr: URIs and bare bech32 identifiers)
 * - Custom emojis (NIP-30: :shortcode: patterns with image URLs)
 * - Plain text (preserved exactly, including whitespace)
 *
 * ## Design Decisions
 *
 * ### Why Priority-based Regex + Post-processing?
 *
 * 1. **Regex for pattern matching**: Efficient and well-suited for finding URLs and mentions
 * 2. **Priority system**: URLs always win over embedded mentions (a mention inside a URL is NOT a mention)
 * 3. **Post-processing**: Handles edge cases that pure regex cannot (trailing punctuation, balanced parens)
 * 4. **Deterministic**: Same input always produces same output, critical for real-time chat
 *
 * ### Why NOT other approaches?
 *
 * - **Pure tokenization/state machine**: Overkill for this use case; URLs don't have nested structure
 * - **AST parsing**: Chat messages aren't a formal grammar; adds complexity without benefit
 * - **Single-pass regex**: Cannot handle priority and edge cases correctly
 *
 * ## Parsing Strategy
 *
 * 1. Find all URL matches with improved regex
 * 2. Post-process URLs to strip trailing punctuation and handle balanced parentheses
 * 3. Find nostr references ONLY in text regions not covered by URLs
 * 4. Find custom emojis ONLY in text regions not covered by URLs or mentions
 * 5. Sort all matches by position
 * 6. Build final parts list, preserving ALL text (including whitespace)
 *
 * ## Known Edge Cases Handled
 *
 * - Trailing punctuation: "Check https://example.com." → URL is "https://example.com", period is text
 * - Balanced parentheses: "https://en.wikipedia.org/wiki/Rust_(programming_language)" → preserved
 * - Mentions in URLs: "https://example.com/npub1abc..." → NOT parsed as mention
 * - Adjacent entities: "https://a.com https://b.com" → two separate links
 * - Query strings: "https://example.com?foo=bar&baz=qux" → preserved
 * - Fragments: "https://example.com#section" → preserved
 * - Custom emojis: "Hello :wave:" with emoji tag → renders inline image
 */
object MessageContentParser {

    // ============================================================================
    // PARSE CACHE — avoids re-running 8 regex passes for the same content
    // ============================================================================

    private const val CACHE_MAX_SIZE = 300

    // LRU cache: key = "content\0emojiMapHash", value = parsed parts.
    // KMP-compatible: uses custom LruCache instead of java.util.LinkedHashMap.
    private val parseCache = org.nostr.nostrord.utils.LruCache<String, List<ParsedPart>>(CACHE_MAX_SIZE)

    // ============================================================================
    // PUBLIC API
    // ============================================================================

    /**
     * Represents a parsed segment of message content.
     */
    sealed class ParsedPart {
        /** Plain text segment (may contain whitespace, newlines, etc.) */
        data class Text(val content: String) : ParsedPart()

        /** URL that should be rendered as a clickable link */
        data class Link(val url: String) : ParsedPart()

        /** URL that points to an image (for inline preview) */
        data class Image(val url: String) : ParsedPart()

        /** Nostr mention (npub, note, nevent, etc.) */
        data class Mention(val reference: Nip27.NostrReference) : ParsedPart()

        /** Custom emoji (NIP-30) - rendered as inline image */
        data class CustomEmoji(val shortcode: String, val imageUrl: String) : ParsedPart()

        // Text formatting (inline)
        /** Bold text (*text*) */
        data class Bold(val content: String) : ParsedPart()

        /** Italic text (_text_) */
        data class Italic(val content: String) : ParsedPart()

        /** Monospace/code text (`code`) */
        data class Monospace(val content: String) : ParsedPart()

        // Code blocks (block-level)
        /** Fenced code block (```language\ncode```) */
        data class CodeBlock(val code: String, val language: String?) : ParsedPart()

        // Hashtags (inline)
        /** Hashtag (#tag) */
        data class Hashtag(val tag: String) : ParsedPart()

        // Media (block-level)
        /** Video URL (YouTube or direct video file) */
        data class Video(val url: String, val videoId: String? = null) : ParsedPart()

        /** Audio URL (mp3, wav, etc.) */
        data class Audio(val url: String) : ParsedPart()

        // Nostr-specific types (block-level)
        /** WebSocket relay URL (ws://, wss://) */
        data class Relay(val url: String) : ParsedPart()

        /** Cashu ecash token (cashuA... or cashuB...) */
        data class Cashu(val token: String) : ParsedPart()

        /** Cashu payment request (creqA...) */
        data class CashuRequest(val request: String) : ParsedPart()
    }

    /**
     * Parse message content into a list of typed parts.
     *
     * Uses a two-pass strategy:
     * - Pass 1: Extract code blocks first (they protect content from formatting)
     * - Pass 2: Parse remaining content (URLs, mentions, formatting, hashtags, emojis)
     *
     * @param content Raw message content
     * @param emojiMap Map of shortcode to image URL from NIP-30 emoji tags
     * @return List of parsed parts in order, preserving original text positions
     */
    fun parse(content: String, emojiMap: Map<String, String> = emptyMap()): List<ParsedPart> {
        if (content.isEmpty()) return emptyList()

        // Check cache first — avoids 8 regex passes for previously seen messages
        val cacheKey = if (emojiMap.isEmpty()) content else "$content\u0000${emojiMap.hashCode()}"
        parseCache.get(cacheKey)?.let { return it }

        // Pass 1: Extract code blocks and replace with placeholders
        val (contentWithPlaceholders, codeBlockMatches) = extractCodeBlocks(content)

        // Pass 2: Parse remaining content with priority system

        // Step 1: Find all URL matches (enhanced for video/audio detection)
        val urlMatches = findUrls(contentWithPlaceholders)

        // Step 2: Find relay URLs (ws://, wss://)
        val coveredByUrls = urlMatches.map { it.range }
        val relayMatches = findRelayUrls(contentWithPlaceholders, coveredByUrls)

        // Step 3: Find Cashu tokens and requests
        val coveredByUrlsAndRelays = (urlMatches + relayMatches).map { it.range }
        val cashuMatches = findCashuTokens(contentWithPlaceholders, coveredByUrlsAndRelays)

        // Step 4: Find nostr references in non-URL/relay/cashu regions
        val urlRelaysCashuMatches = urlMatches + relayMatches + cashuMatches
        val nostrMatches = findNostrReferences(contentWithPlaceholders, urlRelaysCashuMatches)

        // Step 5: Find text formatting in non-covered regions
        val coveredByUrlsRelaysCashuMentions = (urlMatches + relayMatches + cashuMatches + nostrMatches).map { it.range }
        val formattingMatches = findFormatting(contentWithPlaceholders, coveredByUrlsRelaysCashuMentions)

        // Step 6: Find hashtags in non-covered regions
        val coveredByUrlsRelaysCashuMentionsFormatting = (urlMatches + relayMatches + cashuMatches + nostrMatches + formattingMatches).map { it.range }
        val hashtagMatches = findHashtags(contentWithPlaceholders, coveredByUrlsRelaysCashuMentionsFormatting)

        // Step 7: Find custom emojis in remaining regions
        val allCoveredRanges = (urlMatches + relayMatches + cashuMatches + nostrMatches + formattingMatches + hashtagMatches).map { it.range }
        val emojiMatches = findCustomEmojis(contentWithPlaceholders, emojiMap, allCoveredRanges)

        // Step 8: Combine all matches (including code blocks) and sort by position
        val allMatches = (codeBlockMatches + urlMatches + relayMatches + cashuMatches + nostrMatches + formattingMatches + hashtagMatches + emojiMatches)
            .sortedBy { it.range.first }

        // Step 9: Build parts list with text between matches, cache, and return
        val result = buildParts(content, allMatches)
        parseCache.put(cacheKey, result)
        return result
    }

    /**
     * Extract emoji map from NIP-30 tags with validation.
     *
     * Validates:
     * - Tag structure (must have emoji, shortcode, URL)
     * - Shortcode format (alphanumeric, underscores, hyphens only)
     * - URL format (must be valid http/https URL)
     * - Reasonable limits (shortcode <= 64 chars, URL <= 2048 chars)
     *
     * Invalid entries are silently skipped to prevent crashes.
     *
     * @param tags List of tags from a Nostr event
     * @return Map of validated shortcode to image URL
     */
    fun extractEmojiMap(tags: List<List<String>>): Map<String, String> {
        return tags
            .filter { tag ->
                // Basic structure validation
                tag.size >= 3 && tag[0] == "emoji"
            }
            .mapNotNull { tag ->
                val shortcode = tag[1]
                val url = tag[2]

                // Validate shortcode
                if (!isValidShortcode(shortcode)) return@mapNotNull null

                // Validate URL
                if (!isValidEmojiUrl(url)) return@mapNotNull null

                shortcode to url
            }
            .toMap()
    }

    /**
     * Extract image dimension hints from NIP-68 `imeta` tags.
     *
     * NIP-68 tags look like:
     *   ["imeta", "url https://example.com/img.jpg", "dim 800x600", "m image/jpeg", ...]
     *
     * Each field is a key-value pair separated by a single space.
     * This function extracts `url` → `dim` (width x height) pairs so that
     * [ChatImage] can pre-set the aspect ratio and avoid layout shift.
     *
     * @return Map of image URL to (width, height) pair
     */
    fun extractImetaDimensions(tags: List<List<String>>): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()
        for (tag in tags) {
            if (tag.isEmpty() || tag[0] != "imeta") continue
            var url: String? = null
            var dim: Pair<Int, Int>? = null
            for (i in 1 until tag.size) {
                val field = tag[i]
                when {
                    field.startsWith("url ") -> url = field.removePrefix("url ")
                    field.startsWith("dim ") -> {
                        val parts = field.removePrefix("dim ").split("x", limit = 2)
                        if (parts.size == 2) {
                            val w = parts[0].toIntOrNull()
                            val h = parts[1].toIntOrNull()
                            if (w != null && h != null && w > 0 && h > 0) {
                                dim = w to h
                            }
                        }
                    }
                }
            }
            if (url != null && dim != null) {
                result[url] = dim
            }
        }
        return result
    }

    fun extractImetaThumbnails(tags: List<List<String>>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (tag in tags) {
            if (tag.isEmpty() || tag[0] != "imeta") continue
            var url: String? = null
            var thumb: String? = null
            for (i in 1 until tag.size) {
                val field = tag[i]
                when {
                    field.startsWith("url ") -> url = field.removePrefix("url ")
                    field.startsWith("thumb ") -> thumb = field.removePrefix("thumb ")
                    field.startsWith("image ") -> if (thumb == null) thumb = field.removePrefix("image ")
                }
            }
            if (url != null && thumb != null) {
                result[url] = thumb
            }
        }
        return result
    }

    /**
     * Validate shortcode format.
     * Must be alphanumeric with underscores/hyphens, 1-64 characters.
     */
    private fun isValidShortcode(shortcode: String): Boolean {
        if (shortcode.isEmpty() || shortcode.length > 64) return false
        return shortcode.all { it.isLetterOrDigit() || it == '_' || it == '-' }
    }

    /**
     * Validate emoji image URL.
     * Must be http/https, reasonable length, and not contain dangerous characters.
     */
    private fun isValidEmojiUrl(url: String): Boolean {
        if (url.length < 10 || url.length > 2048) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false

        // Block javascript: and data: URLs that might be embedded
        val lowercase = url.lowercase()
        if (lowercase.contains("javascript:") || lowercase.contains("data:")) return false

        return true
    }

    // ============================================================================
    // URL PARSING
    // ============================================================================

    /**
     * URL regex that captures the core URL structure.
     *
     * This intentionally captures more than needed; post-processing will trim.
     * Pattern breakdown:
     * - https?://           - Protocol
     * - [^\s<>"]*           - Any non-whitespace, non-angle-bracket, non-quote chars
     *
     * We use a permissive pattern and then clean up, rather than trying to
     * capture the exact URL boundary in regex (which is error-prone).
     */
    private val urlRegex = Regex(
        """https?://[^\s<>"]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * WebSocket relay URL regex (ws:// or wss://)
     */
    private val relayUrlRegex = Regex(
        """wss?://[^\s<>"]+""",
        RegexOption.IGNORE_CASE
    )

    // ============================================================================
    // CASHU TOKEN PARSING
    // ============================================================================

    /**
     * Cashu ecash token regex (cashuA... or cashuB...)
     * Tokens are base64url encoded, starting with cashuA or cashuB
     */
    private val cashuTokenRegex = Regex(
        """cashu[AB][A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Cashu payment request regex (creqA...)
     * Requests are base64url encoded, starting with creqA
     */
    private val cashuRequestRegex = Regex(
        """creq[A-Za-z0-9_-]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Characters that should not end a URL (trailing punctuation).
     * These are often part of natural language surrounding the URL.
     */
    private val trailingPunctuation = setOf('.', ',', ';', ':', '!', '?', '\'', '"')

    /**
     * Find all URLs in content with proper boundary detection.
     * Enhanced to detect images, videos, and audio URLs.
     */
    private fun findUrls(content: String): List<ParsedMatch> {
        return urlRegex.findAll(content).mapNotNull { match ->
            val cleanedUrl = cleanUrl(match.value)
            if (cleanedUrl.length < 10) return@mapNotNull null // "https://x" minimum

            // Calculate the actual range after cleaning
            val trimmedFromEnd = match.value.length - cleanedUrl.length
            val actualRange = match.range.first..(match.range.last - trimmedFromEnd)

            // Check media types — video/audio by extension first (so .mp4 on
            // nostr.build isn't misclassified as an image by the host check).
            val (isVideo, videoId) = checkVideoUrl(cleanedUrl)
            val part = when {
                isVideo -> ParsedPart.Video(cleanedUrl, videoId)
                isAudioUrl(cleanedUrl) -> ParsedPart.Audio(cleanedUrl)
                isImageUrl(cleanedUrl) -> ParsedPart.Image(cleanedUrl)
                else -> ParsedPart.Link(cleanedUrl)
            }

            ParsedMatch(actualRange, part)
        }.toList()
    }

    /**
     * Clean a raw URL match by removing trailing punctuation and handling parentheses.
     */
    private fun cleanUrl(rawUrl: String): String {
        var url = rawUrl

        // Step 1: Handle balanced parentheses
        // URLs like https://en.wikipedia.org/wiki/Rust_(programming_language)
        // should keep the closing paren, but "Check (https://example.com)" should not
        url = handleParentheses(url)

        // Step 2: Strip trailing punctuation
        while (url.isNotEmpty() && url.last() in trailingPunctuation) {
            url = url.dropLast(1)
        }

        // Step 3: Handle trailing brackets that weren't part of the URL
        // Strip unbalanced trailing ] or )
        url = stripUnbalancedTrailing(url, '(', ')')
        url = stripUnbalancedTrailing(url, '[', ']')

        return url
    }

    /**
     * Handle parentheses in URLs.
     * If the URL ends with ) but has unbalanced parens, remove trailing ).
     */
    private fun handleParentheses(url: String): String {
        if (!url.endsWith(')')) return url

        val openCount = url.count { it == '(' }
        val closeCount = url.count { it == ')' }

        // If more closing than opening, strip trailing )
        var result = url
        var excess = closeCount - openCount
        while (excess > 0 && result.endsWith(')')) {
            result = result.dropLast(1)
            excess--
        }

        return result
    }

    /**
     * Strip unbalanced trailing closing brackets.
     */
    private fun stripUnbalancedTrailing(url: String, open: Char, close: Char): String {
        if (!url.endsWith(close)) return url

        val openCount = url.count { it == open }
        val closeCount = url.count { it == close }

        var result = url
        var excess = closeCount - openCount
        while (excess > 0 && result.endsWith(close)) {
            result = result.dropLast(1)
            excess--
        }

        return result
    }

    /**
     * Check if a URL points to an image.
     */
    private fun isImageUrl(url: String): Boolean {
        val lowercase = url.lowercase()

        // Hosts known to be unreliable — always show as clickable link
        if (org.nostr.nostrord.utils.isBlockedImageHost(url)) {
            return false
        }

        // Check file extensions (before query string)
        val pathPart = lowercase.substringBefore('?').substringBefore('#')
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".ico", ".avif")
        if (imageExtensions.any { pathPart.endsWith(it) }) {
            return true
        }

        // Check known image hosting domains
        val imageHosts = listOf(
            "imgur.com", "i.imgur.com",
            "i.redd.it",
            "pbs.twimg.com",
            "media.tenor.com",
            "giphy.com", "media.giphy.com",
            "nostr.build",
            "void.cat",
            "imgproxy",
            "primal.b-cdn.net"
        )
        if (imageHosts.any { lowercase.contains(it) }) {
            return true
        }

        return false
    }

    // ============================================================================
    // NOSTR REFERENCE PARSING
    // ============================================================================

    /**
     * Find nostr references only in text regions not covered by URLs.
     *
     * This is critical: a reference like npub1... inside a URL is NOT a mention.
     */
    private fun findNostrReferences(content: String, urlMatches: List<ParsedMatch>): List<ParsedMatch> {
        // Build a set of character positions covered by URLs
        val coveredPositions = mutableSetOf<Int>()
        for (match in urlMatches) {
            for (i in match.range) {
                coveredPositions.add(i)
            }
        }

        // Find all nostr references
        val allReferences = Nip27.findReferenceMatches(content)

        // Filter to only those not overlapping with URLs
        return allReferences.mapNotNull { (range, reference) ->
            // Check if ANY part of this reference overlaps with a URL
            val overlapsWithUrl = range.any { it in coveredPositions }
            if (overlapsWithUrl) {
                null
            } else {
                ParsedMatch(range, ParsedPart.Mention(reference))
            }
        }
    }

    // ============================================================================
    // CUSTOM EMOJI PARSING (NIP-30)
    // ============================================================================

    /**
     * Regex for custom emoji shortcodes.
     * Pattern: :shortcode: where shortcode is alphanumeric, underscores, or hyphens
     * Per NIP-30: shortcode MUST be comprised of only alphanumeric characters and underscores.
     * We also allow hyphens (-) as they're commonly used in practice (e.g., :GM-Chachi:)
     */
    private val emojiShortcodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

    /**
     * Find custom emojis in text regions not covered by other matches.
     *
     * Only matches shortcodes that have corresponding emoji tags.
     */
    private fun findCustomEmojis(
        content: String,
        emojiMap: Map<String, String>,
        coveredRanges: List<IntRange>
    ): List<ParsedMatch> {
        if (emojiMap.isEmpty()) return emptyList()

        // Build a set of covered positions
        val coveredPositions = mutableSetOf<Int>()
        for (range in coveredRanges) {
            for (i in range) {
                coveredPositions.add(i)
            }
        }

        return emojiShortcodeRegex.findAll(content).mapNotNull { match ->
            val shortcode = match.groupValues[1]
            val imageUrl = emojiMap[shortcode] ?: return@mapNotNull null

            // Check if this range overlaps with already covered regions
            val overlaps = match.range.any { it in coveredPositions }
            if (overlaps) {
                null
            } else {
                ParsedMatch(match.range, ParsedPart.CustomEmoji(shortcode, imageUrl))
            }
        }.toList()
    }

    // ============================================================================
    // CODE BLOCK PARSING
    // ============================================================================

    /**
     * Regex for fenced code blocks: ```language\ncode```
     * - Optional language identifier after opening backticks
     * - Content can span multiple lines
     * - Non-greedy to handle multiple code blocks
     */
    private val codeBlockRegex = Regex("```(\\w*)\\n?([\\s\\S]*?)```")

    /**
     * Placeholder character used to replace code blocks during parsing.
     * Using a private-use Unicode character that won't appear in normal text.
     */
    private const val CODE_BLOCK_PLACEHOLDER = '\uE000'

    /**
     * Extract code blocks from content and replace with placeholders.
     *
     * This must be done first to prevent formatting inside code blocks from being parsed.
     *
     * @return Pair of (content with placeholders, list of code block matches at original positions)
     */
    private fun extractCodeBlocks(content: String): Pair<String, List<ParsedMatch>> {
        val matches = codeBlockRegex.findAll(content).toList()
        if (matches.isEmpty()) return content to emptyList()

        val codeBlockMatches = mutableListOf<ParsedMatch>()
        val resultBuilder = StringBuilder()
        var lastIndex = 0

        for (match in matches) {
            // Add text before this code block
            resultBuilder.append(content.substring(lastIndex, match.range.first))

            // Add placeholder (same length as original to preserve positions)
            val placeholderLength = match.value.length
            repeat(placeholderLength) { resultBuilder.append(CODE_BLOCK_PLACEHOLDER) }

            // Extract language and code
            val language = match.groupValues[1].takeIf { it.isNotBlank() }
            val code = match.groupValues[2].trimEnd()

            codeBlockMatches.add(ParsedMatch(match.range, ParsedPart.CodeBlock(code, language)))
            lastIndex = match.range.last + 1
        }

        // Add remaining text
        if (lastIndex < content.length) {
            resultBuilder.append(content.substring(lastIndex))
        }

        return resultBuilder.toString() to codeBlockMatches
    }

    // ============================================================================
    // TEXT FORMATTING PARSING
    // ============================================================================

    /**
     * Bold text: *text* (non-greedy, allows any content including newlines)
     */
    private val boldRegex = Regex("\\*([\\s\\S]*?)\\*")

    /**
     * Italic text: _text_ (requires word boundary - space or start/end)
     * Avoids matching underscores in snake_case or emoji shortcodes
     */
    private val italicRegex = Regex("(?:^|(?<=\\s))_([^_]+)_(?=\\s|$|[.,!?;:])")

    /**
     * Monospace/inline code: `text` (not triple backticks)
     * Uses simple approach to avoid lookbehind compatibility issues
     */
    private val monospaceRegex = Regex("`([^`]+)`")

    /**
     * Find text formatting (bold, italic, monospace) in non-covered regions.
     */
    private fun findFormatting(content: String, coveredRanges: List<IntRange>): List<ParsedMatch> {
        val coveredPositions = buildCoveredPositions(coveredRanges)
        val matches = mutableListOf<ParsedMatch>()

        // Find bold *text*
        boldRegex.findAll(content).forEach { match ->
            if (!match.range.any { it in coveredPositions }) {
                matches.add(ParsedMatch(match.range, ParsedPart.Bold(match.groupValues[1])))
            }
        }

        // Find monospace `code` (before italic to avoid conflicts)
        monospaceRegex.findAll(content).forEach { match ->
            if (!match.range.any { it in coveredPositions }) {
                // Check for overlaps with already found matches
                val overlapsWithExisting = matches.any { existing ->
                    match.range.first <= existing.range.last && match.range.last >= existing.range.first
                }
                if (!overlapsWithExisting) {
                    matches.add(ParsedMatch(match.range, ParsedPart.Monospace(match.groupValues[1])))
                }
            }
        }

        // Find italic _text_
        italicRegex.findAll(content).forEach { match ->
            if (!match.range.any { it in coveredPositions }) {
                val overlapsWithExisting = matches.any { existing ->
                    match.range.first <= existing.range.last && match.range.last >= existing.range.first
                }
                if (!overlapsWithExisting) {
                    matches.add(ParsedMatch(match.range, ParsedPart.Italic(match.groupValues[1])))
                }
            }
        }

        return matches
    }

    // ============================================================================
    // HASHTAG PARSING
    // ============================================================================

    /**
     * Hashtag: #tag (must be preceded by whitespace or start of string)
     * Allows letters, numbers, and Unicode letters
     */
    private val hashtagRegex = Regex("(?:^|\\s)#([\\w\\p{L}]+)")

    /**
     * Find hashtags in non-covered regions.
     */
    private fun findHashtags(content: String, coveredRanges: List<IntRange>): List<ParsedMatch> {
        val coveredPositions = buildCoveredPositions(coveredRanges)
        val matches = mutableListOf<ParsedMatch>()

        hashtagRegex.findAll(content).forEach { match ->
            // Find the actual # position (after potential whitespace)
            val fullMatch = match.value
            val hashIndex = fullMatch.indexOf('#')
            val actualStart = match.range.first + hashIndex
            val actualRange = actualStart..match.range.last

            if (!actualRange.any { it in coveredPositions }) {
                matches.add(ParsedMatch(actualRange, ParsedPart.Hashtag(match.groupValues[1])))
            }
        }

        return matches
    }

    // ============================================================================
    // VIDEO/AUDIO DETECTION
    // ============================================================================

    /**
     * Video file extensions
     */
    private val videoExtensions = setOf("mp4", "webm", "mov", "avi", "mkv", "m4v", "ogv")

    /**
     * Audio file extensions
     */
    private val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")

    /**
     * YouTube URL patterns to extract video ID
     * Supports:
     * - Standard: youtube.com/watch?v=VIDEO_ID
     * - Short URL: youtu.be/VIDEO_ID
     * - Shorts: youtube.com/shorts/VIDEO_ID
     * - Live: youtube.com/live/VIDEO_ID
     * - Embed: youtube.com/embed/VIDEO_ID
     * Case-insensitive for domain, preserves case for video ID
     */
    private val youtubeRegex = Regex(
        """(?:youtube\.com/(?:watch\?v=|shorts/|live/|embed/)|youtu\.be/)([\w-]{11})""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Check if a URL points to a video file or YouTube.
     * @return Pair of (isVideo, youtubeVideoId or null)
     */
    private fun checkVideoUrl(url: String): Pair<Boolean, String?> {
        val lowercase = url.lowercase()

        // Check YouTube - match against original URL to preserve video ID case
        val youtubeMatch = youtubeRegex.find(url)
        if (youtubeMatch != null) {
            return true to youtubeMatch.groupValues[1]
        }

        // Check video extensions
        val pathPart = lowercase.substringBefore('?').substringBefore('#')
        val extension = pathPart.substringAfterLast('.', "")
        if (extension in videoExtensions) {
            return true to null
        }

        return false to null
    }

    /**
     * Check if a URL points to an audio file.
     */
    private fun isAudioUrl(url: String): Boolean {
        val lowercase = url.lowercase()
        val pathPart = lowercase.substringBefore('?').substringBefore('#')
        val extension = pathPart.substringAfterLast('.', "")
        return extension in audioExtensions
    }

    // ============================================================================
    // RELAY URL PARSING
    // ============================================================================

    /**
     * Find WebSocket relay URLs (ws://, wss://) in non-covered regions.
     */
    private fun findRelayUrls(content: String, coveredRanges: List<IntRange>): List<ParsedMatch> {
        val coveredPositions = buildCoveredPositions(coveredRanges)

        return relayUrlRegex.findAll(content).mapNotNull { match ->
            // Check if this range overlaps with already covered regions
            val overlaps = match.range.any { it in coveredPositions }
            if (overlaps) {
                null
            } else {
                val cleanedUrl = cleanUrl(match.value)
                if (cleanedUrl.length < 6) return@mapNotNull null // "ws://x" minimum

                // Calculate the actual range after cleaning
                val trimmedFromEnd = match.value.length - cleanedUrl.length
                val actualRange = match.range.first..(match.range.last - trimmedFromEnd)

                ParsedMatch(actualRange, ParsedPart.Relay(cleanedUrl))
            }
        }.toList()
    }

    // ============================================================================
    // CASHU TOKEN PARSING
    // ============================================================================

    /**
     * Find Cashu tokens (cashuA, cashuB) and requests (creqA) in non-covered regions.
     */
    private fun findCashuTokens(content: String, coveredRanges: List<IntRange>): List<ParsedMatch> {
        val coveredPositions = buildCoveredPositions(coveredRanges)
        val matches = mutableListOf<ParsedMatch>()

        // Find Cashu tokens (cashuA, cashuB)
        cashuTokenRegex.findAll(content).forEach { match ->
            if (!match.range.any { it in coveredPositions }) {
                matches.add(ParsedMatch(match.range, ParsedPart.Cashu(match.value)))
            }
        }

        // Find Cashu requests (creqA)
        cashuRequestRegex.findAll(content).forEach { match ->
            if (!match.range.any { it in coveredPositions }) {
                val overlapsWithExisting = matches.any { existing ->
                    match.range.first <= existing.range.last && match.range.last >= existing.range.first
                }
                if (!overlapsWithExisting) {
                    matches.add(ParsedMatch(match.range, ParsedPart.CashuRequest(match.value)))
                }
            }
        }

        return matches
    }

    // ============================================================================
    // HELPER FUNCTIONS
    // ============================================================================

    /**
     * Build a set of covered character positions from ranges.
     */
    private fun buildCoveredPositions(ranges: List<IntRange>): Set<Int> {
        val positions = mutableSetOf<Int>()
        for (range in ranges) {
            for (i in range) {
                positions.add(i)
            }
        }
        return positions
    }

    // ============================================================================
    // PARTS BUILDING
    // ============================================================================

    /**
     * Internal representation of a match during parsing.
     */
    private data class ParsedMatch(
        val range: IntRange,
        val part: ParsedPart
    )

    /**
     * Build the final list of parts, inserting text segments between matches.
     *
     * IMPORTANT: We preserve ALL text, including whitespace-only segments.
     * This is critical for proper rendering and text selection.
     */
    private fun buildParts(content: String, matches: List<ParsedMatch>): List<ParsedPart> {
        if (matches.isEmpty()) {
            return if (content.isNotEmpty()) listOf(ParsedPart.Text(content)) else emptyList()
        }

        val parts = mutableListOf<ParsedPart>()
        var lastIndex = 0

        for (match in matches) {
            // Add text before this match (even if whitespace-only)
            if (match.range.first > lastIndex) {
                val text = content.substring(lastIndex, match.range.first)
                if (text.isNotEmpty()) {
                    parts.add(ParsedPart.Text(text))
                }
            }

            // Add the matched part
            parts.add(match.part)
            lastIndex = match.range.last + 1
        }

        // Add remaining text after last match
        if (lastIndex < content.length) {
            val text = content.substring(lastIndex)
            if (text.isNotEmpty()) {
                parts.add(ParsedPart.Text(text))
            }
        }

        return parts
    }

    // ============================================================================
}
