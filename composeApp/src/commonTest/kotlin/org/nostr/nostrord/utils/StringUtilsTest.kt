package org.nostr.nostrord.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class StringUtilsTest {

    @Test
    fun `bold fraktur normalizes to ascii`() {
        assertEquals("settoshi", "𝖘𝖊𝖙𝖙𝖔𝖘𝖍𝖎".normalizeForSearch())
        assertEquals("abc", "𝕬𝕭𝕮".normalizeForSearch())
    }

    @Test
    fun `mathematical bold lowercase normalizes to ascii`() {
        assertEquals("hello", "𝐡𝐞𝐥𝐥𝐨".normalizeForSearch())
    }

    @Test
    fun `mathematical italic lowercase normalizes to ascii`() {
        assertEquals("world", "𝑤𝑜𝑟𝑙𝑑".normalizeForSearch())
    }

    @Test
    fun `mathematical sans-serif bold normalizes to ascii`() {
        assertEquals("nostr", "𝗻𝗼𝘀𝘁𝗿".normalizeForSearch())
    }

    @Test
    fun `mathematical monospace normalizes to ascii`() {
        assertEquals("code", "𝚌𝚘𝚍𝚎".normalizeForSearch())
    }

    @Test
    fun `full-width latin normalizes to ascii`() {
        assertEquals("hello", "ＨＥＬＬＯ".normalizeForSearch())
        assertEquals("hello", "ｈｅｌｌｏ".normalizeForSearch())
    }

    @Test
    fun `plain ascii is returned unchanged and lowercased`() {
        assertEquals("nostr", "Nostr".normalizeForSearch())
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", "".normalizeForSearch())
    }

    @Test
    fun `mixed plain and styled normalizes correctly`() {
        assertEquals("hello world", "𝐡𝐞𝐥𝐥𝐨 world".normalizeForSearch())
    }
}
