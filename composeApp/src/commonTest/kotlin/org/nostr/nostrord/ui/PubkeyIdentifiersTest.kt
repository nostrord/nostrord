package org.nostr.nostrord.ui

import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PubkeyIdentifiersTest {
    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @Test
    fun `lists real formats in the prototype order`() {
        val ids = pubkeyIdentifiers(hex, nip05 = "fiatjaf@fiatjaf.com")
        assertEquals(listOf("npub", "nprofile", "nostrord link", "hex", "nip-05"), ids.map { it.label })
        assertEquals(Nip19.encodeNpub(hex), ids[0].value)
        assertTrue(ids[1].value.startsWith("nprofile1"))
        // nprofile round-trips back to the same pubkey.
        val decoded = Nip19.decode(ids[1].value) as Nip19.Entity.Nprofile
        assertEquals(hex, decoded.pubkey)
        assertTrue(ids[2].value.startsWith("https://nostrord.com/#/u/npub1"))
        assertEquals(hex, ids[3].value)
        assertEquals("fiatjaf@fiatjaf.com", ids[4].value)
    }

    @Test
    fun `omits nip-05 when absent`() {
        val ids = pubkeyIdentifiers(hex, nip05 = null)
        assertEquals(listOf("npub", "nprofile", "nostrord link", "hex"), ids.map { it.label })
    }

    @Test
    fun `nprofile carries relay hints`() {
        val ids = pubkeyIdentifiers(hex, nprofileRelays = listOf("wss://relay.damus.io"))
        val nprofile = ids.first { it.label == "nprofile" }.value
        val decoded = Nip19.decode(nprofile) as Nip19.Entity.Nprofile
        assertEquals(hex, decoded.pubkey)
        assertEquals(listOf("wss://relay.damus.io"), decoded.relays)
    }

    @Test
    fun `relay hints take nip65 write relays capped at two`() {
        val hints =
            nprofileRelayHints(
                listOf(
                    Nip65Relay("wss://a.example"),
                    Nip65Relay("wss://read-only.example", write = false),
                    Nip65Relay("wss://b.example"),
                    Nip65Relay("wss://c.example"),
                ),
            )
        assertEquals(listOf("wss://a.example", "wss://b.example"), hints)
    }

    @Test
    fun `relay hints fall back to defaults without nip65`() {
        assertEquals(RelayListManager.DEFAULT_FALLBACK_RELAYS.take(2), nprofileRelayHints(emptyList()))
    }
}
