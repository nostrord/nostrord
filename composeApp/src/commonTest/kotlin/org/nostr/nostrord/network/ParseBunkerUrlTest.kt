package org.nostr.nostrord.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ParseBunkerUrlTest {

    // ============================================================================
    // Valid URLs
    // ============================================================================

    @Test
    fun `minimal valid bunker URL`() {
        val pubkey = "a".repeat(64)
        val url = "bunker://$pubkey?relay=wss://relay.example.com"
        val result = parseBunkerUrl(url)

        assertEquals(pubkey, result.pubkey)
        assertEquals(listOf("wss://relay.example.com"), result.relays)
        assertNull(result.secret)
    }

    @Test
    fun `bunker URL with multiple relays`() {
        val pubkey = "b".repeat(64)
        val url = "bunker://$pubkey?relay=wss://relay1.com&relay=wss://relay2.com"
        val result = parseBunkerUrl(url)

        assertEquals(2, result.relays.size)
        assertEquals("wss://relay1.com", result.relays[0])
        assertEquals("wss://relay2.com", result.relays[1])
    }

    @Test
    fun `bunker URL with secret`() {
        val pubkey = "c".repeat(64)
        val url = "bunker://$pubkey?relay=wss://relay.example.com&secret=mysecret123"
        val result = parseBunkerUrl(url)

        assertEquals("mysecret123", result.secret)
    }

    @Test
    fun `bunker URL with URL-encoded relay`() {
        val pubkey = "d".repeat(64)
        val encoded = "wss%3A%2F%2Frelay.example.com"
        val url = "bunker://$pubkey?relay=$encoded"
        val result = parseBunkerUrl(url)

        assertEquals("wss://relay.example.com", result.relays[0])
    }

    @Test
    fun `leading and trailing whitespace is stripped`() {
        val pubkey = "e".repeat(64)
        val url = "  bunker://$pubkey?relay=wss://relay.example.com  "
        val result = parseBunkerUrl(url)

        assertEquals(pubkey, result.pubkey)
    }

    // ============================================================================
    // Invalid URLs
    // ============================================================================

    @Test
    fun `missing bunker scheme throws`() {
        assertFailsWith<IllegalArgumentException> {
            parseBunkerUrl("nostr://abc?relay=wss://relay.example.com")
        }
    }

    @Test
    fun `missing relay throws`() {
        val pubkey = "f".repeat(64)
        assertFailsWith<IllegalArgumentException> {
            parseBunkerUrl("bunker://$pubkey?secret=only")
        }
    }

    @Test
    fun `invalid pubkey length throws`() {
        assertFailsWith<IllegalArgumentException> {
            parseBunkerUrl("bunker://tooshort?relay=wss://relay.example.com")
        }
    }

    @Test
    fun `non-hex pubkey throws`() {
        val badPubkey = "g".repeat(64) // 'g' is not hex
        assertFailsWith<IllegalArgumentException> {
            parseBunkerUrl("bunker://$badPubkey?relay=wss://relay.example.com")
        }
    }

    @Test
    fun `empty string throws`() {
        assertFailsWith<IllegalArgumentException> {
            parseBunkerUrl("")
        }
    }
}
