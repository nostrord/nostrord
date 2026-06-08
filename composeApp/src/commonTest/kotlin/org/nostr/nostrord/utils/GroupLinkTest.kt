package org.nostr.nostrord.utils

import org.nostr.nostrord.nostr.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GroupLinkTest {
    @Test
    fun `parses NIP-29 group address with scheme`() {
        val target = parseGroupJoinInput("wss://relay.com'abc123")
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123"), target)
    }

    @Test
    fun `parses bare host group address by adding wss`() {
        val target = parseGroupJoinInput("relay.com'abc123")
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123"), target)
    }

    @Test
    fun `trims surrounding whitespace on group address`() {
        val target = parseGroupJoinInput("  wss://relay.com'abc123  ")
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123"), target)
    }

    @Test
    fun `parses invite link with relay group and code`() {
        val target = parseGroupJoinInput("https://nostrord.com/open/?relay=relay.com&group=abc123&code=secret")
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123", "secret"), target)
    }

    @Test
    fun `invite link without code yields null code`() {
        val target = parseGroupJoinInput("nostrord://open?relay=relay.com&group=abc123")
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123", null), target)
    }

    @Test
    fun `group address without id is rejected`() {
        assertNull(parseGroupJoinInput("wss://relay.com'"))
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(parseGroupJoinInput("   "))
    }

    @Test
    fun `plain relay url without id or query is rejected`() {
        assertNull(parseGroupJoinInput("wss://relay.com"))
    }

    @Test
    fun `buildGroupAddress round-trips through the parser`() {
        val address = buildGroupAddress("wss://relay.com", "abc123")
        assertEquals("wss://relay.com'abc123", address)
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123"), parseGroupJoinInput(address))
    }

    @Test
    fun `buildGroupAddress adds scheme to bare host`() {
        assertEquals("wss://relay.com'abc123", buildGroupAddress("relay.com", "abc123"))
    }

    @Test
    fun `parses group naddr with relay hint`() {
        val naddr = Nip19.encodeNaddr(identifier = "abc123", relay = "wss://relay.com", kind = 39000)
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123"), parseGroupJoinInput(naddr))
    }

    @Test
    fun `parses group naddr with nostr prefix`() {
        val naddr = "nostr:" + Nip19.encodeNaddr(identifier = "abc123", relay = "wss://relay.com", kind = 39000)
        assertEquals(GroupJoinTarget("wss://relay.com", "abc123"), parseGroupJoinInput(naddr))
    }

    @Test
    fun `naddr without a relay hint is rejected`() {
        val naddr = Nip19.encodeNaddr(identifier = "abc123", relay = "", kind = 39000)
        assertNull(parseGroupJoinInput(naddr))
    }

    @Test
    fun `non-group naddr kind is rejected`() {
        val naddr = Nip19.encodeNaddr(identifier = "abc123", relay = "wss://relay.com", kind = 30023)
        assertNull(parseGroupJoinInput(naddr))
    }

    @Test
    fun `garbage naddr is rejected`() {
        assertNull(parseGroupJoinInput("naddr1notvalidbech32"))
    }

    @Test
    fun `address with userinfo relay is rejected`() {
        // toRelayUrl rejects `@` userinfo (it could bypass loopback checks); none of the forms
        // should let a blank relay slip through.
        assertNull(parseGroupJoinInput("ws://localhost:7777@evil.com'abc123"))
    }

    @Test
    fun `invite link with userinfo relay is rejected`() {
        assertNull(parseGroupJoinInput("nostrord://open?relay=ws://localhost:7777@evil.com&group=abc123"))
    }

    @Test
    fun `naddr with userinfo relay hint is rejected`() {
        val naddr = Nip19.encodeNaddr(identifier = "abc123", relay = "ws://localhost:7777@evil.com", kind = 39000)
        assertNull(parseGroupJoinInput(naddr))
    }
}
