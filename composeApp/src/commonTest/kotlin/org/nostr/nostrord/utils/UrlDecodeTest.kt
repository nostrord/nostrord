package org.nostr.nostrord.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlDecodeTest {

    // -------------------------------------------------------------------------
    // ASCII / no-op cases
    // -------------------------------------------------------------------------

    @Test
    fun `plain ascii string is unchanged`() {
        assertEquals("hello", "hello".urlDecode())
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", "".urlDecode())
    }

    // -------------------------------------------------------------------------
    // Percent-encoded ASCII
    // -------------------------------------------------------------------------

    @Test
    fun `percent-encoded space becomes space`() {
        assertEquals("hello world", "hello%20world".urlDecode())
    }

    @Test
    fun `plus becomes space`() {
        assertEquals("hello world", "hello+world".urlDecode())
    }

    @Test
    fun `multiple percent sequences decoded`() {
        assertEquals("a=1&b=2", "a%3D1%26b%3D2".urlDecode())
    }

    @Test
    fun `percent-encoded slash`() {
        assertEquals("a/b", "a%2Fb".urlDecode())
    }

    @Test
    fun `uppercase hex digits decoded`() {
        assertEquals(" ", "%20".urlDecode())
        assertEquals(" ", "%20".urlDecode())
    }

    // -------------------------------------------------------------------------
    // Multi-byte UTF-8 (the regression case documented in the source)
    // -------------------------------------------------------------------------

    @Test
    fun `3-byte CJK character decoded correctly`() {
        // 日 = U+65E5 = UTF-8: E6 97 A5
        assertEquals("日", "%E6%97%A5".urlDecode())
    }

    @Test
    fun `CJK string decoded correctly`() {
        assertEquals("日本語", "%E6%97%A5%E6%9C%AC%E8%AA%9E".urlDecode())
    }

    @Test
    fun `4-byte emoji decoded correctly`() {
        // 😀 = U+1F600 = UTF-8: F0 9F 98 80
        assertEquals("😀", "%F0%9F%98%80".urlDecode())
    }

    @Test
    fun `mixed ascii and multibyte`() {
        assertEquals("hi 日", "hi%20%E6%97%A5".urlDecode())
    }

    // -------------------------------------------------------------------------
    // Invalid / edge percent sequences
    // -------------------------------------------------------------------------

    @Test
    fun `truncated percent at end of string kept as-is`() {
        // "%" at end — not enough chars for hex, kept literally
        val result = "abc%".urlDecode()
        assertEquals("abc%", result)
    }

    @Test
    fun `invalid hex sequence kept as-is`() {
        // %ZZ is not valid hex
        val result = "%ZZ".urlDecode()
        assertEquals("%ZZ", result)
    }

    @Test
    fun `percent followed by one char at end kept as-is`() {
        val result = "x%2".urlDecode()
        assertEquals("x%2", result)
    }

    // -------------------------------------------------------------------------
    // Real-world relay URL param
    // -------------------------------------------------------------------------

    @Test
    fun `relay url with encoded colon and slashes`() {
        assertEquals(
            "wss://relay.example.com",
            "wss%3A%2F%2Frelay.example.com".urlDecode()
        )
    }
}
