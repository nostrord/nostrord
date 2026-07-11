package org.nostr.nostrord.ui

import org.nostr.nostrord.nostr.Nip19
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DmGroupInviteTest {
    private val relay = "wss://groups.example.com"
    private val naddr = Nip19.encodeNaddr(identifier = "mygroup", relay = relay, kind = 39000)

    @Test
    fun `extracts the naddr line and keeps the rest as text`() {
        val invite = extractDmGroupInvite("You've been added to the group \"X\".\nnostr:$naddr")
        assertEquals("mygroup", invite?.groupId)
        assertEquals(relay, invite?.relayUrl)
        assertEquals("You've been added to the group \"X\".", invite?.remainingText)
    }

    @Test
    fun `bare naddr as the whole message works too`() {
        val invite = extractDmGroupInvite(naddr)
        assertEquals("mygroup", invite?.groupId)
        assertEquals("", invite?.remainingText)
    }

    @Test
    fun `inline naddr or non-39000 naddr is not an invite`() {
        assertNull(extractDmGroupInvite("check nostr:$naddr out"))
        val article = Nip19.encodeNaddr(identifier = "post", relay = relay, kind = 30023)
        assertNull(extractDmGroupInvite("nostr:$article"))
        assertNull(extractDmGroupInvite("plain text only"))
    }
}
