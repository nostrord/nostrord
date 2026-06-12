package org.nostr.nostrord.nostr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip49Test {
    @Test
    fun decryptsTheNip49SpecTestVector() {
        // From the NIP-49 spec: password "nostr", log_n 16.
        val ncryptsec =
            "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
        val key = Nip49.decrypt(ncryptsec, "nostr")
        assertEquals(
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683",
            key?.toHexString(),
        )
    }

    @Test
    fun wrongPasswordReturnsNull() {
        val ncryptsec =
            "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
        assertNull(Nip49.decrypt(ncryptsec, "wrong"))
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(Nip49.decrypt("ncryptsec1qqqqqqqq", "nostr"))
        assertNull(Nip49.decrypt("nsec1abc", "nostr"))
        assertNull(Nip49.decrypt("", "nostr"))
    }

    @Test
    fun encryptThenDecryptRoundTrips() {
        // Low log_n keeps the test fast; the spec vector above covers the real cost path.
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val ncryptsec = Nip49.encrypt(key, "correct horse battery staple", logN = 4)
        assertTrue(ncryptsec.startsWith("ncryptsec1"))
        assertEquals(key.toHexString(), Nip49.decrypt(ncryptsec, "correct horse battery staple")?.toHexString())
        assertNull(Nip49.decrypt(ncryptsec, "Correct horse battery staple"))
    }
}
