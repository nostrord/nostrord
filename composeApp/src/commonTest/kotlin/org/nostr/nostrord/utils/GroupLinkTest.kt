package org.nostr.nostrord.utils

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
}
