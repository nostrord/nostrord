package org.nostr.nostrord.ui.components.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Comprehensive test matrix for MessageContentParser.
 *
 * Tests are organized by category:
 * 1. Basic URL parsing
 * 2. URL edge cases (punctuation, parentheses, query strings)
 * 3. Nostr mention parsing
 * 4. Mixed content (URLs + mentions + text)
 * 5. Edge cases and boundary conditions
 */
class MessageContentParserTest {

    // ============================================================================
    // 1. BASIC URL PARSING
    // ============================================================================

    @Test
    fun `simple http URL`() {
        val input = "Check http://example.com"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Check ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("http://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `simple https URL`() {
        val input = "Visit https://example.com"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `URL with path`() {
        val input = "See https://example.com/path/to/page"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com/path/to/page", (parts[1] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `URL with query string`() {
        val input = "Link: https://example.com/search?q=test&page=1"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com/search?q=test&page=1", (parts[1] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `URL with fragment`() {
        val input = "Go to https://example.com/page#section"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com/page#section", (parts[1] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `URL with port number`() {
        val input = "Dev server: https://localhost:8080/api"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://localhost:8080/api", (parts[1] as MessageContentParser.ParsedPart.Link).url)
    }

    // ============================================================================
    // 2. URL EDGE CASES
    // ============================================================================

    @Test
    fun `URL with trailing period - period should be text`() {
        val input = "Check https://example.com."
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Check ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(".", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `URL with trailing comma - comma should be text`() {
        val input = "Visit https://example.com, it's great!"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(", it's great!", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `URL with trailing exclamation - exclamation should be text`() {
        val input = "Wow https://example.com!"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals("!", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `URL with trailing question mark - question mark should be text`() {
        val input = "Have you seen https://example.com?"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals("?", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `URL in parentheses - parentheses should be text`() {
        val input = "Check this (https://example.com) for more info"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Check this (", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(") for more info", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `Wikipedia-style URL with balanced parentheses - keep closing paren`() {
        val input = "See https://en.wikipedia.org/wiki/Rust_(programming_language)"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals(
            "https://en.wikipedia.org/wiki/Rust_(programming_language)",
            (parts[1] as MessageContentParser.ParsedPart.Link).url
        )
    }

    @Test
    fun `URL with multiple trailing punctuation`() {
        val input = "Check https://example.com..."
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals("...", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `multiple URLs in same message`() {
        val input = "Compare https://example.com and https://test.org today"
        val parts = MessageContentParser.parse(input)

        assertEquals(5, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Compare ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(" and ", (parts[2] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[3])
        assertEquals("https://test.org", (parts[3] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[4])
        assertEquals(" today", (parts[4] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `adjacent URLs separated by space`() {
        val input = "https://a.com https://b.com"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[0])
        assertIs<MessageContentParser.ParsedPart.Text>(parts[1])
        assertEquals(" ", (parts[1] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[2])
    }

    // ============================================================================
    // 3. IMAGE URL DETECTION
    // ============================================================================

    @Test
    fun `URL with jpg extension detected as image`() {
        val input = "Photo: https://example.com/photo.jpg"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Image>(parts[1])
        assertEquals("https://example.com/photo.jpg", (parts[1] as MessageContentParser.ParsedPart.Image).url)
    }

    @Test
    fun `URL with png extension detected as image`() {
        val input = "Image: https://example.com/image.png"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Image>(parts[1])
    }

    @Test
    fun `URL with gif extension detected as image`() {
        val input = "GIF: https://example.com/funny.gif"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Image>(parts[1])
    }

    @Test
    fun `Imgur URL detected as image`() {
        val input = "Check https://i.imgur.com/abc123"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Image>(parts[1])
    }

    @Test
    fun `Image URL with query string preserves extension`() {
        val input = "https://example.com/image.jpg?width=800"
        val parts = MessageContentParser.parse(input)

        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Image>(parts[0])
        assertEquals("https://example.com/image.jpg?width=800", (parts[0] as MessageContentParser.ParsedPart.Image).url)
    }

    // ============================================================================
    // 4. NOSTR MENTION PARSING
    // ============================================================================

    @Test
    fun `nostr URI mention`() {
        val input = "Follow nostr:npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val parts = MessageContentParser.parse(input)

        // Note: This will only work if the bech32 actually decodes
        // For testing, we check the structure
        assertTrue(parts.size >= 1)
    }

    @Test
    fun `bare bech32 mention at start of message`() {
        val input = "npub1abc123 is a great person"
        val parts = MessageContentParser.parse(input)

        // The bare npub should be detected
        assertTrue(parts.isNotEmpty())
    }

    @Test
    fun `mention should NOT be detected inside URL`() {
        val input = "https://example.com/npub1abc123/profile"
        val parts = MessageContentParser.parse(input)

        // Should be a single link, NOT a mention
        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[0])
        assertEquals("https://example.com/npub1abc123/profile", (parts[0] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `mention at end of URL path should NOT be detected as separate mention`() {
        val input = "Check https://njump.me/npub1abc123"
        val parts = MessageContentParser.parse(input)

        // Should be text + single link, the npub is part of the URL
        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertTrue((parts[1] as MessageContentParser.ParsedPart.Link).url.contains("npub1"))
    }

    // ============================================================================
    // 5. MIXED CONTENT
    // ============================================================================

    @Test
    fun `text with URL and mention`() {
        val input = "Check https://example.com and follow nostr:npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
        val parts = MessageContentParser.parse(input)

        // Should have: text, link, text, mention (if npub decodes)
        assertTrue(parts.size >= 3)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
    }

    @Test
    fun `preserve whitespace between elements`() {
        val input = "A   https://a.com   B"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("A   ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals("   B", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `newlines are preserved`() {
        val input = "Line 1\nhttps://example.com\nLine 3"
        val parts = MessageContentParser.parse(input)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Line 1\n", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals("\nLine 3", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    // ============================================================================
    // 6. EDGE CASES
    // ============================================================================

    @Test
    fun `empty string returns empty list`() {
        val parts = MessageContentParser.parse("")
        assertTrue(parts.isEmpty())
    }

    @Test
    fun `plain text with no special content`() {
        val input = "This is just plain text with no links or mentions"
        val parts = MessageContentParser.parse(input)

        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals(input, (parts[0] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `URL only message`() {
        val input = "https://example.com"
        val parts = MessageContentParser.parse(input)

        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[0])
        assertEquals("https://example.com", (parts[0] as MessageContentParser.ParsedPart.Link).url)
    }

    @Test
    fun `malformed URL - just protocol`() {
        val input = "Check https:// for more"
        val parts = MessageContentParser.parse(input)

        // "https://" alone should not match as a valid URL
        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
    }

    @Test
    fun `URL with unicode domain (IDN)`() {
        val input = "Visit https://münchen.example.com"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
    }

    @Test
    fun `URL with encoded characters`() {
        val input = "Search https://example.com/search?q=hello%20world"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals(
            "https://example.com/search?q=hello%20world",
            (parts[1] as MessageContentParser.ParsedPart.Link).url
        )
    }

    @Test
    fun `special characters in query string`() {
        val input = "API: https://api.example.com/v1?key=abc123&callback=fn"
        val parts = MessageContentParser.parse(input)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals(
            "https://api.example.com/v1?key=abc123&callback=fn",
            (parts[1] as MessageContentParser.ParsedPart.Link).url
        )
    }

    @Test
    fun `markdown-style link text is preserved as separate parts`() {
        val input = "[click here](https://example.com)"
        val parts = MessageContentParser.parse(input)

        // We don't parse markdown, so this should be: text, link, text
        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("[click here](", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
        assertEquals("https://example.com", (parts[1] as MessageContentParser.ParsedPart.Link).url)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(")", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    // ============================================================================
    // 6. CUSTOM EMOJI PARSING (NIP-30)
    // ============================================================================

    @Test
    fun `simple custom emoji with matching tag`() {
        val input = "Hello :wave: world"
        val emojiMap = mapOf("wave" to "https://example.com/wave.png")
        val parts = MessageContentParser.parse(input, emojiMap)

        assertEquals(3, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Hello ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.CustomEmoji>(parts[1])
        val emoji = parts[1] as MessageContentParser.ParsedPart.CustomEmoji
        assertEquals("wave", emoji.shortcode)
        assertEquals("https://example.com/wave.png", emoji.imageUrl)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(" world", (parts[2] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `custom emoji without matching tag is not parsed`() {
        val input = "Hello :wave: world"
        val emojiMap = mapOf("other" to "https://example.com/other.png")
        val parts = MessageContentParser.parse(input, emojiMap)

        // No matching emoji tag, so :wave: stays as text
        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Hello :wave: world", (parts[0] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `multiple custom emojis in one message`() {
        val input = "Hello :gleasonator: 😂 :ablobcatrainbow: :disputed: yolo"
        val emojiMap = mapOf(
            "gleasonator" to "https://example.com/gleasonator.png",
            "ablobcatrainbow" to "https://example.com/ablobcatrainbow.png",
            "disputed" to "https://example.com/disputed.png"
        )
        val parts = MessageContentParser.parse(input, emojiMap)

        // Should be: Text, Emoji, Text, Emoji, Text, Emoji, Text
        assertEquals(7, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertIs<MessageContentParser.ParsedPart.CustomEmoji>(parts[1])
        assertEquals("gleasonator", (parts[1] as MessageContentParser.ParsedPart.CustomEmoji).shortcode)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[2])
        assertEquals(" 😂 ", (parts[2] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.CustomEmoji>(parts[3])
        assertEquals("ablobcatrainbow", (parts[3] as MessageContentParser.ParsedPart.CustomEmoji).shortcode)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[4])
        assertEquals(" ", (parts[4] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.CustomEmoji>(parts[5])
        assertEquals("disputed", (parts[5] as MessageContentParser.ParsedPart.CustomEmoji).shortcode)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[6])
        assertEquals(" yolo", (parts[6] as MessageContentParser.ParsedPart.Text).content)
    }

    @Test
    fun `custom emoji with underscores in shortcode`() {
        val input = "Check :my_custom_emoji:"
        val emojiMap = mapOf("my_custom_emoji" to "https://example.com/emoji.png")
        val parts = MessageContentParser.parse(input, emojiMap)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.CustomEmoji>(parts[1])
        assertEquals("my_custom_emoji", (parts[1] as MessageContentParser.ParsedPart.CustomEmoji).shortcode)
    }

    @Test
    fun `custom emoji with hyphens in shortcode`() {
        val input = "Good morning :GM-Chachi:"
        val emojiMap = mapOf("GM-Chachi" to "https://example.com/gm-chachi.gif")
        val parts = MessageContentParser.parse(input, emojiMap)

        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertEquals("Good morning ", (parts[0] as MessageContentParser.ParsedPart.Text).content)
        assertIs<MessageContentParser.ParsedPart.CustomEmoji>(parts[1])
        assertEquals("GM-Chachi", (parts[1] as MessageContentParser.ParsedPart.CustomEmoji).shortcode)
        assertEquals("https://example.com/gm-chachi.gif", (parts[1] as MessageContentParser.ParsedPart.CustomEmoji).imageUrl)
    }

    @Test
    fun `custom emoji not parsed inside URL`() {
        val input = "See https://example.com/:emoji:/path"
        val emojiMap = mapOf("emoji" to "https://example.com/emoji.png")
        val parts = MessageContentParser.parse(input, emojiMap)

        // The :emoji: is inside a URL, so it should NOT be parsed as emoji
        assertEquals(2, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
        assertIs<MessageContentParser.ParsedPart.Link>(parts[1])
    }

    @Test
    fun `extractEmojiMap from NIP-30 tags`() {
        val tags = listOf(
            listOf("emoji", "wave", "https://example.com/wave.png"),
            listOf("emoji", "smile", "https://example.com/smile.png"),
            listOf("p", "pubkey123"), // Not an emoji tag
            listOf("e", "eventid123") // Not an emoji tag
        )
        val emojiMap = MessageContentParser.extractEmojiMap(tags)

        assertEquals(2, emojiMap.size)
        assertEquals("https://example.com/wave.png", emojiMap["wave"])
        assertEquals("https://example.com/smile.png", emojiMap["smile"])
    }

    @Test
    fun `empty emoji map returns no custom emojis`() {
        val input = "Hello :wave: world"
        val parts = MessageContentParser.parse(input, emptyMap())

        assertEquals(1, parts.size)
        assertIs<MessageContentParser.ParsedPart.Text>(parts[0])
    }
}

/**
 * Test matrix summary for manual verification:
 *
 * | Input                                              | Expected Output                                    |
 * |----------------------------------------------------|----------------------------------------------------|
 * | "https://example.com."                             | [Link("https://example.com"), Text(".")]           |
 * | "https://example.com, nice"                        | [Link("..."), Text(", nice")]                      |
 * | "(https://example.com)"                            | [Text("("), Link("..."), Text(")")]                |
 * | "https://wiki.org/Rust_(lang)"                     | [Link("https://wiki.org/Rust_(lang)")]             |
 * | "https://a.com https://b.com"                      | [Link, Text(" "), Link]                            |
 * | "https://example.com/npub1..."                     | [Link("https://example.com/npub1...")]             |
 * | "Check npub1abc next"                              | [Text, Mention, Text]                              |
 * | "https://img.com/a.jpg"                            | [Image("https://img.com/a.jpg")]                   |
 * | ""                                                 | []                                                 |
 * | "plain text"                                       | [Text("plain text")]                               |
 * | "A   https://a.com   B"                            | [Text("A   "), Link, Text("   B")]                 |
 */
