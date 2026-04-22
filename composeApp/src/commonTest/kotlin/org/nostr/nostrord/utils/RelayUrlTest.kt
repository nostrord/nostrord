package org.nostr.nostrord.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayUrlTest {

    @Test
    fun `toRelayUrl adds wss to bare public host`() {
        assertEquals("wss://relay.damus.io", "relay.damus.io".toRelayUrl())
    }

    @Test
    fun `toRelayUrl adds ws to bare localhost`() {
        assertEquals("ws://localhost:7777", "localhost:7777".toRelayUrl())
    }

    @Test
    fun `toRelayUrl preserves existing scheme`() {
        assertEquals("ws://localhost:7777", "ws://localhost:7777".toRelayUrl())
        assertEquals("wss://relay.damus.io", "wss://relay.damus.io".toRelayUrl())
    }

    @Test
    fun `toRelayUrl rejects userinfo masquerading as loopback`() {
        assertEquals("", "ws://localhost:7777@evil.com/".toRelayUrl())
        assertEquals("", "localhost:7777@evil.com".toRelayUrl())
    }

    @Test
    fun `toRelayUrl allows at sign after path`() {
        assertEquals("wss://relay.example.com/foo@bar", "wss://relay.example.com/foo@bar".toRelayUrl())
    }

    @Test
    fun `isValidRelayUrl accepts wss public host`() {
        assertTrue(isValidRelayUrl("wss://relay.damus.io"))
    }

    @Test
    fun `isValidRelayUrl accepts ws localhost`() {
        assertTrue(isValidRelayUrl("ws://localhost:7777"))
    }

    @Test
    fun `isValidRelayUrl rejects ws for public host`() {
        assertFalse(isValidRelayUrl("ws://relay.damus.io"))
    }

    @Test
    fun `isValidRelayUrl rejects userinfo`() {
        assertFalse(isValidRelayUrl("ws://localhost:7777@evil.com/"))
        assertFalse(isValidRelayUrl("wss://user:pass@relay.example.com/"))
    }

    @Test
    fun `isValidRelayUrl rejects empty and schemeless`() {
        assertFalse(isValidRelayUrl(""))
        assertFalse(isValidRelayUrl("relay.example.com"))
    }
}
