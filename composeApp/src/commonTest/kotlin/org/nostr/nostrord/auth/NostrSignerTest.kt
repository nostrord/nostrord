package org.nostr.nostrord.auth

import kotlinx.coroutines.test.runTest
import org.nostr.nostrord.nostr.Event
import org.nostr.nostrord.nostr.KeyPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NostrSignerTest {

    private val dummyEvent = Event(
        id = null,
        pubkey = "a".repeat(64),
        createdAt = 1_700_000_000L,
        kind = 1,
        tags = emptyList(),
        content = "hello",
        sig = null,
    )

    // -------------------------------------------------------------------------
    // Local signer
    // -------------------------------------------------------------------------

    @Test
    fun `Local signer signs event and returns signed event`() = runTest {
        val kp = KeyPair.generate()
        val signer = NostrSigner.Local(kp)
        val signed = signer.signEvent(dummyEvent.copy(pubkey = kp.publicKeyHex))
        assertEquals(kp.publicKeyHex, signed.pubkey)
    }

    @Test
    fun `Local signer throws after dispose`() = runTest {
        val signer = NostrSigner.Local(KeyPair.generate())
        signer.dispose()
        assertFailsWith<NostrSigner.SigningException> {
            signer.signEvent(dummyEvent)
        }
    }

    @Test
    fun `Local signer dispose is idempotent`() = runTest {
        val kp = KeyPair.generate()
        val signer = NostrSigner.Local(kp)
        signer.dispose()
        signer.dispose() // must not throw
    }

    @Test
    fun `Local signer zeros private key on dispose`() {
        val kp = KeyPair.generate()
        val signer = NostrSigner.Local(kp)
        signer.dispose()
        assertTrue(kp.privateKey.all { it == 0.toByte() }, "Private key bytes should be zeroed")
    }

    // -------------------------------------------------------------------------
    // Cross-signer isolation: signing with disposed signer is impossible
    // -------------------------------------------------------------------------

    @Test
    fun `disposed signer cannot be reused across account switch`() = runTest {
        val kp = KeyPair.generate()
        val signer = NostrSigner.Local(kp)
        signer.dispose()

        // Simulates an old coroutine from account A trying to sign after
        // the session was cancelled and the signer disposed.
        assertFailsWith<NostrSigner.SigningException> {
            signer.signEvent(dummyEvent)
        }
    }
}
