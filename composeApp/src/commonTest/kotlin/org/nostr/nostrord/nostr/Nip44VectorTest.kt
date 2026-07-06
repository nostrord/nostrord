package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Official NIP-44 v2 test vectors (paulmillr/nip44 nip44.vectors.json).
 * Decrypting a known payload exercises the full pipeline (ECDH, HKDF,
 * HMAC verify, ChaCha20) against the reference implementation — a pure
 * roundtrip test would pass even with a wrong-but-symmetric cipher.
 */
class Nip44VectorTest {
    @Test
    fun decryptsOfficialVector1BothDirections() {
        val sec1 = "0000000000000000000000000000000000000000000000000000000000000001"
        val sec2 = "0000000000000000000000000000000000000000000000000000000000000002"
        val payload =
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"

        val pub1 = Crypto.getPublicKeyHex(sec1.hexToByteArray())
        val pub2 = Crypto.getPublicKeyHex(sec2.hexToByteArray())

        assertEquals("a", Nip44.decrypt(payload, sec1, pub2))
        assertEquals("a", Nip44.decrypt(payload, sec2, pub1))
    }

    @Test
    fun decryptsOfficialVectorWithMultibytePlaintext() {
        val sec1 = "5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a"
        val sec2 = "4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d"
        val plaintext = "表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀"
        val payload =
            "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="

        val pub2 = Crypto.getPublicKeyHex(sec2.hexToByteArray())
        assertEquals(plaintext, Nip44.decrypt(payload, sec1, pub2))
    }

    @Test
    fun encryptDecryptRoundtrip() {
        val alice = KeyPair.generate()
        val bob = KeyPair.generate()
        val message = "roundtrip check with a payload above one ChaCha20 block: " + "x".repeat(100)

        val encrypted = Nip44.encrypt(message, alice.privateKeyHex, bob.publicKeyHex)
        assertEquals(message, Nip44.decrypt(encrypted, bob.privateKeyHex, alice.publicKeyHex))
    }
}
