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
    }

    /**
     * Parse message content into a list of typed parts.
     *
     * @param content Raw message content
     * @param emojiMap Map of shortcode to image URL from NIP-30 emoji tags
     * @return List of parsed parts in order, preserving original text positions
     */
    fun parse(content: String, emojiMap: Map<String, String> = emptyMap()): List<ParsedPart> {
        if (content.isEmpty()) return emptyList()

        // Step 1: Find all URL matches
        val urlMatches = findUrls(content)

        // Step 2: Find nostr references in non-URL regions
        val nostrMatches = findNostrReferences(content, urlMatches)

        // Step 3: Find custom emojis in non-URL, non-mention regions
        val coveredRanges = (urlMatches + nostrMatches).map { it.range }
        val emojiMatches = findCustomEmojis(content, emojiMap, coveredRanges)

        // Step 4: Combine and sort all matches
        val allMatches = (urlMatches + nostrMatches + emojiMatches).sortedBy { it.range.first }

        // Step 5: Build parts list with text between matches
        return buildParts(content, allMatches)
    }

    /**
     * Extract emoji map from NIP-30 tags.
     *
     * @param tags List of tags from a Nostr event
     * @return Map of shortcode to image URL
     */
    fun extractEmojiMap(tags: List<List<String>>): Map<String, String> {
        return tags
            .filter { it.size >= 3 && it[0] == "emoji" }
            .associate { it[1] to it[2] }
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
     * Characters that should not end a URL (trailing punctuation).
     * These are often part of natural language surrounding the URL.
     */
    private val trailingPunctuation = setOf('.', ',', ';', ':', '!', '?', '\'', '"')

    /**
     * Find all URLs in content with proper boundary detection.
     */
    private fun findUrls(content: String): List<ParsedMatch> {
        return urlRegex.findAll(content).mapNotNull { match ->
            val cleanedUrl = cleanUrl(match.value)
            if (cleanedUrl.length < 10) return@mapNotNull null // "https://x" minimum

            // Calculate the actual range after cleaning
            val trimmedFromEnd = match.value.length - cleanedUrl.length
            val actualRange = match.range.first..(match.range.last - trimmedFromEnd)

            val part = if (isImageUrl(cleanedUrl)) {
                ParsedPart.Image(cleanedUrl)
            } else {
                ParsedPart.Link(cleanedUrl)
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

        // Check file extensions (before query string)
        val pathPart = lowercase.substringBefore('?').substringBefore('#')
        val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp", ".ico", ".avif")
        if (imageExtensions.any { pathPart.endsWith(it) }) {
            return true
        }

        // Check known image hosting domains
        val imageHosts = listOf(
            "imgur.com", "i.imgur.com",
            "i.redd.it",
            "pbs.twimg.com",
            "cdn.discordapp.com",
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
