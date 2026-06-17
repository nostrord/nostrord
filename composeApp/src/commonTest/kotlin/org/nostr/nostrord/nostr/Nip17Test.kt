package org.nostr.nostrord.nostr

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.auth.NostrSigner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip17Test {
    private fun signer() = NostrSigner.Local(KeyPair.generate())

    @Test
    fun `wrap then unwrap round-trips the message`() = runTest {
        val alice = signer()
        val bob = signer()
        val rumor = Nip17.buildRumor(alice.pubkey, bob.pubkey, "hi bob")
        val wrap = Nip17.wrap(rumor, bob.pubkey, alice)

        assertEquals(Nip17.KIND_GIFT_WRAP, wrap.kind)
        assertEquals(bob.pubkey, wrap.getTag("p")?.getOrNull(1))
        assertTrue(wrap.pubkey != alice.pubkey, "gift wrap must use a throwaway key, not the sender's")
        assertTrue(wrap.verify(), "gift wrap must be validly signed by the throwaway key")

        val out = Nip17.unwrap(wrap, bob)
        assertNotNull(out)
        assertEquals("hi bob", out.rumor.content)
        assertEquals(alice.pubkey, out.senderPubkey)
        assertEquals(Nip17.KIND_CHAT, out.rumor.kind)
        assertEquals(bob.pubkey, out.rumor.getTag("p")?.getOrNull(1))
    }

    @Test
    fun `a third party cannot unwrap`() = runTest {
        val alice = signer()
        val bob = signer()
        val eve = signer()
        val wrap = Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "secret"), bob.pubkey, alice)
        assertNull(Nip17.unwrap(wrap, eve))
    }

    @Test
    fun `seal is identity-signed by the sender`() = runTest {
        val alice = signer()
        val bob = signer()
        val seal = Nip17.seal(Nip17.buildRumor(alice.pubkey, bob.pubkey, "x"), bob.pubkey, alice)
        assertEquals(Nip17.KIND_SEAL, seal.kind)
        assertEquals(alice.pubkey, seal.pubkey)
        assertTrue(seal.verify())
    }

    @Test
    fun `gift wrap timestamp is at or before now`() = runTest {
        val alice = signer()
        val bob = signer()
        val wrap = Nip17.wrap(Nip17.buildRumor(alice.pubkey, bob.pubkey, "x"), bob.pubkey, alice)
        assertTrue(wrap.createdAt <= org.nostr.nostrord.utils.epochSeconds())
    }

    @Test
    fun `a signer without NIP-44 support rejects encryption`() = runTest {
        val stub =
            object : NostrSigner {
                override val pubkey = "00".repeat(32)

                override suspend fun signEvent(event: Event): Event = event

                override fun dispose() {}
            }
        assertFailsWith<NostrSigner.SigningException> { stub.nip44Encrypt(stub.pubkey, "x") }
    }
}
