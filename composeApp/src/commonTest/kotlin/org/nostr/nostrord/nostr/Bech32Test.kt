package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Bech32Test {

    // -------------------------------------------------------------------------
    // Encode → decode round-trips
    // -------------------------------------------------------------------------

    @Test
    fun `encode then decode returns original bytes`() {
        val original = ByteArray(32) { it.toByte() }
        val encoded = Bech32.encode("npub", original)
        val (hrp, decoded) = Bech32.decode(encoded)!!
        assertEquals("npub", hrp)
        assertEquals(original.toList(), decoded.toList())
    }

    @Test
    fun `hrp is preserved through round-trip`() {
        val data = ByteArray(32) { 0xAB.toByte() }
        val encoded = Bech32.encode("nsec", data)
        val (hrp, _) = Bech32.decode(encoded)!!
        assertEquals("nsec", hrp)
    }

    @Test
    fun `different hrps produce different encodings`() {
        val data = ByteArray(32) { 1 }
        val npub = Bech32.encode("npub", data)
        val nsec = Bech32.encode("nsec", data)
        assert(npub != nsec)
        assert(npub.startsWith("npub1"))
        assert(nsec.startsWith("nsec1"))
    }

    // -------------------------------------------------------------------------
    // Known test vector (NIP-19 spec example)
    // -------------------------------------------------------------------------

    @Test
    fun `decode known npub vector`() {
        // From NIP-19 spec
        val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        val result = Bech32.decode(npub)
        assertNotNull(result)
        val (hrp, bytes) = result
        assertEquals("npub", hrp)
        assertEquals(32, bytes.size)
        assertEquals("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", bytes.toHexString())
    }

    @Test
    fun `encode known pubkey hex produces correct npub`() {
        val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        val npub = Bech32.encode("npub", hex.hexToByteArray())
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", npub)
    }

    // -------------------------------------------------------------------------
    // Invalid inputs
    // -------------------------------------------------------------------------

    @Test
    fun `decode returns null for empty string`() {
        assertNull(Bech32.decode(""))
    }

    @Test
    fun `decode returns null for bad checksum`() {
        // Flip last char of a valid npub
        val bad = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w7"
        assertNull(Bech32.decode(bad))
    }

    @Test
    fun `decode returns null for mixed case`() {
        // BIP-173: mixed case is invalid
        val mixed = "Npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        assertNull(Bech32.decode(mixed))
    }

    @Test
    fun `decode returns null when no separator`() {
        assertNull(Bech32.decode("npubnoseparatorhere"))
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ByteArray.toHexString() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun String.hexToByteArray() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
