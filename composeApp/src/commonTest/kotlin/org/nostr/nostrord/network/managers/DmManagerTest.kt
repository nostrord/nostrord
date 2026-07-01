package org.nostr.nostrord.network.managers

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip17
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmManagerTest {
    private fun signer() = NostrSigner.Local(KeyPair.generate())

    @Test
    fun `received message lands under the sender as the conversation peer`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hey")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)

        dm.ingestGiftWrap(wrap, myPubkey = bob.pubkey, signer = bob)

        val msgs = dm.messagesByPeer.value[alice.pubkey]
        assertEquals(1, msgs?.size)
        assertEquals("hey", msgs?.first()?.content)
        assertTrue(msgs?.first()?.mine == false)
    }

    @Test
    fun `self-copy lands under the recipient and is marked mine`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "yo")
        // Self-copy: sealed and wrapped to myself, but the rumor's p-tag is the recipient.
        val selfWrap = Nip17.wrap(rumor, alice.pubkey, alice)

        dm.ingestGiftWrap(selfWrap, myPubkey = alice.pubkey, signer = alice)

        val msgs = dm.messagesByPeer.value[bob.pubkey]
        assertEquals(1, msgs?.size)
        assertEquals("yo", msgs?.first()?.content)
        assertTrue(msgs?.first()?.mine == true)
    }

    @Test
    fun `duplicate gift wraps are deduped by rumor id`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "once")
        // Same rumor wrapped twice (e.g. arriving from two relays) -> distinct gift wraps.
        val w1 = Nip17.wrap(rumor, bob.pubkey, alice)
        val w2 = Nip17.wrap(rumor, bob.pubkey, alice)

        dm.ingestGiftWrap(w1, bob.pubkey, bob)
        dm.ingestGiftWrap(w2, bob.pubkey, bob)

        assertEquals(1, dm.messagesByPeer.value[alice.pubkey]?.size)
    }

    @Test
    fun `kind 10050 is parsed into the peer's DM relay list`() = runTest {
        val dm = DmManager(backgroundScope)
        val event =
            Event(
                pubkey = "ab".repeat(32),
                createdAt = 1L,
                kind = 10050,
                tags = listOf(listOf("relay", "wss://dm.example.com"), listOf("relay", "wss://nos.lol")),
                content = "",
            )
        dm.ingestDmRelays(event)
        assertEquals(listOf("wss://dm.example.com", "wss://nos.lol"), dm.dmRelaysFor("ab".repeat(32)))
    }

    @Test
    fun `incoming message is unread until marked read`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "ping")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)

        dm.ingestGiftWrap(wrap, myPubkey = bob.pubkey, signer = bob)
        assertEquals(1, dm.unreadByPeer.first { it.containsKey(alice.pubkey) }[alice.pubkey])

        dm.markRead(alice.pubkey)
        assertTrue(dm.unreadByPeer.first { !it.containsKey(alice.pubkey) }.isEmpty())
    }

    @Test
    fun `my own messages never count as unread`() = runTest {
        val dm = DmManager(backgroundScope)
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "mine")
        val selfWrap = Nip17.wrap(rumor, alice.pubkey, alice)

        dm.ingestGiftWrap(selfWrap, myPubkey = alice.pubkey, signer = alice)
        // The conversation with bob appears, but a self-authored message is not unread.
        val convo = dm.conversations.first { list -> list.any { it.peerPubkey == bob.pubkey } }
        assertEquals(0, convo.first { it.peerPubkey == bob.pubkey }.unread)
    }

    @Test
    fun `hydrate restores messages and read state so old messages are not unread`() = runTest {
        val dm = DmManager(backgroundScope)
        val peer = "cd".repeat(32)
        val msg = DmMessage(id = "rumor1", peerPubkey = peer, senderPubkey = peer, content = "hi", createdAt = 10L, mine = false)

        dm.hydrate(listOf(msg), mapOf(peer to 10L))

        assertEquals(1, dm.messagesByPeer.value[peer]?.size)
        val convo = dm.conversations.first { it.isNotEmpty() }
        assertEquals(0, convo.first().unread)
    }
}
