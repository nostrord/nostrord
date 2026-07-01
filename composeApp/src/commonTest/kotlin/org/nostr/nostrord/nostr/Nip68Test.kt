package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip68Test {

    @Test
    fun `extracts url to dimensions from imeta tag`() {
        val tags = listOf(
            listOf("imeta", "url https://example.com/a.jpg", "dim 800x600", "m image/jpeg"),
        )

        val dims = Nip68.extractImetaDimensions(tags)

        assertEquals(800 to 600, dims["https://example.com/a.jpg"])
    }

    @Test
    fun `ignores imeta entries with missing or invalid dim`() {
        val tags = listOf(
            listOf("imeta", "url https://example.com/no-dim.jpg", "m image/jpeg"),
            listOf("imeta", "url https://example.com/bad.jpg", "dim 0x600"),
            listOf("imeta", "url https://example.com/nan.jpg", "dim axb"),
            listOf("p", "somepubkey"),
        )

        val dims = Nip68.extractImetaDimensions(tags)

        assertTrue(dims.isEmpty())
    }

    @Test
    fun `extracts thumbnail and falls back to image field`() {
        val tags = listOf(
            listOf("imeta", "url https://example.com/a.jpg", "thumb https://example.com/a-thumb.jpg"),
            listOf("imeta", "url https://example.com/b.jpg", "image https://example.com/b-image.jpg"),
        )

        val thumbs = Nip68.extractImetaThumbnails(tags)

        assertEquals("https://example.com/a-thumb.jpg", thumbs["https://example.com/a.jpg"])
        assertEquals("https://example.com/b-image.jpg", thumbs["https://example.com/b.jpg"])
    }

    @Test
    fun `thumb wins over image when both present`() {
        val tags = listOf(
            listOf(
                "imeta",
                "url https://example.com/a.jpg",
                "image https://example.com/a-image.jpg",
                "thumb https://example.com/a-thumb.jpg",
            ),
        )

        val thumbs = Nip68.extractImetaThumbnails(tags)

        assertEquals("https://example.com/a-thumb.jpg", thumbs["https://example.com/a.jpg"])
    }

    @Test
    fun `returns nothing for non-imeta tags`() {
        val tags = listOf(listOf("e", "eventid"), listOf("p", "pubkey"))

        assertTrue(Nip68.extractImetaDimensions(tags).isEmpty())
        assertNull(Nip68.extractImetaThumbnails(tags)["anything"])
    }
}
