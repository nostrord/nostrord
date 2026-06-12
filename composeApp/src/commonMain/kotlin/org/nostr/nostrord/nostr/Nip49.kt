package org.nostr.nostrord.nostr

/**
 * NIP-49 private key encryption (ncryptsec).
 *
 * Payload (91 bytes, bech32 "ncryptsec"):
 *   version 0x02 | log_n (1) | salt (16) | nonce (24) | key security byte (1) | ciphertext (48)
 *
 * symmetric key = scrypt(nfkc(password), salt, N = 2^log_n, r = 8, p = 1, dkLen = 32)
 * cipher        = XChaCha20-Poly1305, AAD = the key security byte
 *
 * Implemented in pure Kotlin (scrypt, Salsa20/8, ChaCha20, Poly1305) on top of the
 * platform-backed [Crypto.sha256], so all four targets share one implementation and
 * the commonTest vectors cover every platform. scrypt with the default log_n = 16
 * costs ~64 MB and noticeable CPU; call [decrypt]/[encrypt] off the UI thread where
 * the platform has one.
 */
object Nip49 {
    private const val HRP = "ncryptsec"
    private const val PAYLOAD_SIZE = 91
    private const val VERSION = 0x02.toByte()

    /** True when the input looks like an ncryptsec (the UIs use this to ask for a password). */
    fun isEncryptedKey(input: String): Boolean = input.trim().lowercase().startsWith("${HRP}1")

    /**
     * Decrypt an ncryptsec with the password. Returns the 32-byte private key, or null
     * when the string is malformed or the password is wrong (Poly1305 tag mismatch).
     */
    fun decrypt(
        ncryptsec: String,
        password: String,
    ): ByteArray? {
        val (hrp, payload) = Bech32.decode(ncryptsec.trim()) ?: return null
        if (hrp != HRP || payload.size != PAYLOAD_SIZE || payload[0] != VERSION) return null
        val logN = payload[1].toInt() and 0xFF
        if (logN !in 1..22) return null
        val salt = payload.copyOfRange(2, 18)
        val nonce = payload.copyOfRange(18, 42)
        val securityByte = payload[42]
        val ciphertext = payload.copyOfRange(43, PAYLOAD_SIZE)
        val key = Scrypt.derive(nfkcNormalize(password).encodeToByteArray(), salt, logN, r = 8, p = 1, dkLen = 32)
        return XChaCha20Poly1305.decrypt(key, nonce, ciphertext, aad = byteArrayOf(securityByte))
    }

    /**
     * Encrypt a 32-byte private key into an ncryptsec. [securityByte] follows the NIP-49
     * key security semantics (0x02 = client does not track whether the key has been
     * handled insecurely, the sensible default).
     */
    fun encrypt(
        privateKey: ByteArray,
        password: String,
        logN: Int = 16,
        securityByte: Byte = 0x02,
    ): String {
        require(privateKey.size == 32) { "private key must be 32 bytes" }
        require(logN in 1..22) { "log_n out of range" }
        // Crypto.generatePrivateKey is the platform CSPRNG (32 secure random bytes);
        // reused here as the random source for salt and nonce.
        val salt = Crypto.generatePrivateKey().copyOf(16)
        val nonce = (Crypto.generatePrivateKey() + Crypto.generatePrivateKey()).copyOf(24)
        val key = Scrypt.derive(nfkcNormalize(password).encodeToByteArray(), salt, logN, r = 8, p = 1, dkLen = 32)
        val ciphertext = XChaCha20Poly1305.encrypt(key, nonce, privateKey, aad = byteArrayOf(securityByte))
        val payload = ByteArray(PAYLOAD_SIZE)
        payload[0] = VERSION
        payload[1] = logN.toByte()
        salt.copyInto(payload, 2)
        nonce.copyInto(payload, 18)
        payload[42] = securityByte
        ciphertext.copyInto(payload, 43)
        return Bech32.encode(HRP, payload)
    }
}

/** NFKC normalization (NIP-49 requires it for passwords); platform-backed. */
internal expect fun nfkcNormalize(input: String): String

// ── scrypt (RFC 7914) ─────────────────────────────────────────────────────────

internal object Scrypt {
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        logN: Int,
        r: Int,
        p: Int,
        dkLen: Int,
    ): ByteArray {
        val n = 1 shl logN
        val mfLen = 128 * r
        val b = pbkdf2HmacSha256(password, salt, p * mfLen)
        val words = IntArray(b.size / 4)
        for (i in words.indices) words[i] = le32(b, i * 4)
        val blockWords = 32 * r
        val v = IntArray(n * blockWords)
        val y = IntArray(blockWords)
        val t = IntArray(16)
        val x16 = IntArray(16)
        for (block in 0 until p) {
            roMix(words, block * blockWords, n, r, v, y, x16, t)
        }
        for (i in words.indices) putLe32(b, i * 4, words[i])
        return pbkdf2HmacSha256(password, b, dkLen)
    }

    private fun roMix(
        words: IntArray,
        offset: Int,
        n: Int,
        r: Int,
        v: IntArray,
        y: IntArray,
        x16: IntArray,
        t: IntArray,
    ) {
        val len = 32 * r
        val x = IntArray(len)
        words.copyInto(x, 0, offset, offset + len)
        for (i in 0 until n) {
            x.copyInto(v, i * len)
            blockMix(x, y, r, x16, t)
        }
        for (i in 0 until n) {
            // integerify: first word of the last 64-byte block, mod N (N is a power of 2)
            val j = x[(2 * r - 1) * 16] and (n - 1)
            val base = j * len
            for (k in 0 until len) x[k] = x[k] xor v[base + k]
            blockMix(x, y, r, x16, t)
        }
        x.copyInto(words, offset)
    }

    /** scrypt BlockMix_salsa20/8; the result is written back into [b] (32r words). */
    private fun blockMix(
        b: IntArray,
        y: IntArray,
        r: Int,
        x: IntArray,
        t: IntArray,
    ) {
        b.copyInto(x, 0, (2 * r - 1) * 16, 2 * r * 16)
        for (i in 0 until 2 * r) {
            for (k in 0 until 16) x[k] = x[k] xor b[i * 16 + k]
            salsa208(x, t)
            x.copyInto(y, i * 16)
        }
        for (i in 0 until r) {
            y.copyInto(b, i * 16, (2 * i) * 16, (2 * i + 1) * 16)
            y.copyInto(b, (r + i) * 16, (2 * i + 1) * 16, (2 * i + 2) * 16)
        }
    }

    /** Salsa20/8 core with feed-forward, in place on the 16-word [x]; [t] is scratch. */
    private fun salsa208(
        x: IntArray,
        t: IntArray,
    ) {
        for (i in 0 until 16) t[i] = x[i]
        repeat(4) {
            // column round
            t[4] = t[4] xor (t[0] + t[12]).rotateLeft(7)
            t[8] = t[8] xor (t[4] + t[0]).rotateLeft(9)
            t[12] = t[12] xor (t[8] + t[4]).rotateLeft(13)
            t[0] = t[0] xor (t[12] + t[8]).rotateLeft(18)
            t[9] = t[9] xor (t[5] + t[1]).rotateLeft(7)
            t[13] = t[13] xor (t[9] + t[5]).rotateLeft(9)
            t[1] = t[1] xor (t[13] + t[9]).rotateLeft(13)
            t[5] = t[5] xor (t[1] + t[13]).rotateLeft(18)
            t[14] = t[14] xor (t[10] + t[6]).rotateLeft(7)
            t[2] = t[2] xor (t[14] + t[10]).rotateLeft(9)
            t[6] = t[6] xor (t[2] + t[14]).rotateLeft(13)
            t[10] = t[10] xor (t[6] + t[2]).rotateLeft(18)
            t[3] = t[3] xor (t[15] + t[11]).rotateLeft(7)
            t[7] = t[7] xor (t[3] + t[15]).rotateLeft(9)
            t[11] = t[11] xor (t[7] + t[3]).rotateLeft(13)
            t[15] = t[15] xor (t[11] + t[7]).rotateLeft(18)
            // row round
            t[1] = t[1] xor (t[0] + t[3]).rotateLeft(7)
            t[2] = t[2] xor (t[1] + t[0]).rotateLeft(9)
            t[3] = t[3] xor (t[2] + t[1]).rotateLeft(13)
            t[0] = t[0] xor (t[3] + t[2]).rotateLeft(18)
            t[6] = t[6] xor (t[5] + t[4]).rotateLeft(7)
            t[7] = t[7] xor (t[6] + t[5]).rotateLeft(9)
            t[4] = t[4] xor (t[7] + t[6]).rotateLeft(13)
            t[5] = t[5] xor (t[4] + t[7]).rotateLeft(18)
            t[11] = t[11] xor (t[10] + t[9]).rotateLeft(7)
            t[8] = t[8] xor (t[11] + t[10]).rotateLeft(9)
            t[9] = t[9] xor (t[8] + t[11]).rotateLeft(13)
            t[10] = t[10] xor (t[9] + t[8]).rotateLeft(18)
            t[12] = t[12] xor (t[15] + t[14]).rotateLeft(7)
            t[13] = t[13] xor (t[12] + t[15]).rotateLeft(9)
            t[14] = t[14] xor (t[13] + t[12]).rotateLeft(13)
            t[15] = t[15] xor (t[14] + t[13]).rotateLeft(18)
        }
        for (i in 0 until 16) x[i] += t[i]
    }

    private fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        dkLen: Int,
    ): ByteArray {
        // c = 1 always in scrypt, so each block is a single HMAC.
        val blocks = (dkLen + 31) / 32
        val out = ByteArray(blocks * 32)
        for (i in 1..blocks) {
            val counter =
                byteArrayOf(
                    (i ushr 24).toByte(),
                    (i ushr 16).toByte(),
                    (i ushr 8).toByte(),
                    i.toByte(),
                )
            hmacSha256(password, salt + counter).copyInto(out, (i - 1) * 32)
        }
        return out.copyOf(dkLen)
    }

    private fun hmacSha256(
        key: ByteArray,
        message: ByteArray,
    ): ByteArray {
        val k = if (key.size > 64) Crypto.sha256(key) else key
        val inner = ByteArray(64) { 0x36 }
        val outer = ByteArray(64) { 0x5C }
        for (i in k.indices) {
            inner[i] = (inner[i].toInt() xor k[i].toInt()).toByte()
            outer[i] = (outer[i].toInt() xor k[i].toInt()).toByte()
        }
        return Crypto.sha256(outer + Crypto.sha256(inner + message))
    }
}

// ── XChaCha20-Poly1305 (RFC 8439 + draft-irtf-cfrg-xchacha) ──────────────────

internal object XChaCha20Poly1305 {
    fun encrypt(
        key: ByteArray,
        nonce24: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val subKey = subKey(key, nonce24)
        val nonce = nonce12(nonce24)
        val ciphertext = chacha20Xor(subKey, nonce, 1, plaintext)
        return ciphertext + tag(subKey, nonce, ciphertext, aad)
    }

    fun decrypt(
        key: ByteArray,
        nonce24: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        if (ciphertextWithTag.size < 16) return null
        val subKey = subKey(key, nonce24)
        val nonce = nonce12(nonce24)
        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - 16)
        val expected = tag(subKey, nonce, ciphertext, aad)
        var diff = 0
        for (i in 0 until 16) {
            diff = diff or (expected[i].toInt() xor ciphertextWithTag[ciphertext.size + i].toInt())
        }
        if (diff != 0) return null
        return chacha20Xor(subKey, nonce, 1, ciphertext)
    }

    /** HChaCha20: derive the XChaCha subkey from the first 16 nonce bytes. */
    private fun subKey(
        key: ByteArray,
        nonce24: ByteArray,
    ): IntArray {
        val x =
            intArrayOf(
                0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                le32(key, 0), le32(key, 4), le32(key, 8), le32(key, 12),
                le32(key, 16), le32(key, 20), le32(key, 24), le32(key, 28),
                le32(nonce24, 0), le32(nonce24, 4), le32(nonce24, 8), le32(nonce24, 12),
            )
        repeat(10) { doubleRound(x) }
        // No feed-forward: subkey = words 0..3 and 12..15
        return intArrayOf(x[0], x[1], x[2], x[3], x[12], x[13], x[14], x[15])
    }

    /** XChaCha nonce: 4 zero bytes + the last 8 bytes of the 24-byte nonce. */
    private fun nonce12(nonce24: ByteArray): IntArray = intArrayOf(0, le32(nonce24, 16), le32(nonce24, 20))

    private fun tag(
        subKey: IntArray,
        nonce: IntArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        // Poly1305 one-time key = first 32 bytes of the counter-0 keystream block.
        val block = chacha20Block(subKey, 0, nonce)
        val polyKey = ByteArray(32)
        for (i in 0 until 8) putLe32(polyKey, i * 4, block[i])
        val padAad = (16 - aad.size % 16) % 16
        val padCt = (16 - ciphertext.size % 16) % 16
        val macData = ByteArray(aad.size + padAad + ciphertext.size + padCt + 16)
        aad.copyInto(macData, 0)
        ciphertext.copyInto(macData, aad.size + padAad)
        val lenOffset = aad.size + padAad + ciphertext.size + padCt
        putLe64(macData, lenOffset, aad.size.toLong())
        putLe64(macData, lenOffset + 8, ciphertext.size.toLong())
        return Poly1305.mac(polyKey, macData)
    }

    private fun chacha20Xor(
        keyWords: IntArray,
        nonceWords: IntArray,
        counterStart: Int,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArray(data.size)
        val keystream = ByteArray(64)
        var offset = 0
        var counter = counterStart
        while (offset < data.size) {
            val block = chacha20Block(keyWords, counter, nonceWords)
            for (i in 0 until 16) putLe32(keystream, i * 4, block[i])
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) {
                out[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += n
            counter++
        }
        return out
    }

    private fun chacha20Block(
        keyWords: IntArray,
        counter: Int,
        nonceWords: IntArray,
    ): IntArray {
        val state =
            intArrayOf(
                0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
                keyWords[0], keyWords[1], keyWords[2], keyWords[3],
                keyWords[4], keyWords[5], keyWords[6], keyWords[7],
                counter, nonceWords[0], nonceWords[1], nonceWords[2],
            )
        val x = state.copyOf()
        repeat(10) { doubleRound(x) }
        for (i in 0 until 16) x[i] += state[i]
        return x
    }

    private fun doubleRound(x: IntArray) {
        qr(x, 0, 4, 8, 12)
        qr(x, 1, 5, 9, 13)
        qr(x, 2, 6, 10, 14)
        qr(x, 3, 7, 11, 15)
        qr(x, 0, 5, 10, 15)
        qr(x, 1, 6, 11, 12)
        qr(x, 2, 7, 8, 13)
        qr(x, 3, 4, 9, 14)
    }

    private fun qr(
        x: IntArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) {
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }
}

// ── Poly1305 (RFC 8439), 26-bit limbs over Long ───────────────────────────────

internal object Poly1305 {
    fun mac(
        key: ByteArray,
        msg: ByteArray,
    ): ByteArray {
        val t0 = le32(key, 0).toLong() and 0xFFFFFFFFL
        val t1 = le32(key, 4).toLong() and 0xFFFFFFFFL
        val t2 = le32(key, 8).toLong() and 0xFFFFFFFFL
        val t3 = le32(key, 12).toLong() and 0xFFFFFFFFL

        val r0 = t0 and 0x3FFFFFFL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3FFFF03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3FFC0FFL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3F03FFFL
        val r4 = (t3 ushr 8) and 0x00FFFFFL

        val s1 = r1 * 5
        val s2 = r2 * 5
        val s3 = r3 * 5
        val s4 = r4 * 5

        var h0 = 0L
        var h1 = 0L
        var h2 = 0L
        var h3 = 0L
        var h4 = 0L

        val block = ByteArray(17)
        var pos = 0
        while (pos < msg.size) {
            val n = minOf(16, msg.size - pos)
            block.fill(0)
            msg.copyInto(block, 0, pos, pos + n)
            block[n] = 1
            val b0 = le32(block, 0).toLong() and 0xFFFFFFFFL
            val b1 = le32(block, 4).toLong() and 0xFFFFFFFFL
            val b2 = le32(block, 8).toLong() and 0xFFFFFFFFL
            val b3 = le32(block, 12).toLong() and 0xFFFFFFFFL
            val b4 = block[16].toLong() and 0xFFL

            h0 += b0 and 0x3FFFFFFL
            h1 += ((b0 ushr 26) or (b1 shl 6)) and 0x3FFFFFFL
            h2 += ((b1 ushr 20) or (b2 shl 12)) and 0x3FFFFFFL
            h3 += ((b2 ushr 14) or (b3 shl 18)) and 0x3FFFFFFL
            h4 += (b3 ushr 8) or (b4 shl 24)

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            val d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            val d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            val d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            val d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0

            var c = d0 ushr 26
            h0 = d0 and 0x3FFFFFFL
            var d = d1 + c
            c = d ushr 26
            h1 = d and 0x3FFFFFFL
            d = d2 + c
            c = d ushr 26
            h2 = d and 0x3FFFFFFL
            d = d3 + c
            c = d ushr 26
            h3 = d and 0x3FFFFFFL
            d = d4 + c
            c = d ushr 26
            h4 = d and 0x3FFFFFFL
            h0 += c * 5
            c = h0 ushr 26
            h0 = h0 and 0x3FFFFFFL
            h1 += c

            pos += n
        }

        // Final carry propagation
        var c = h1 ushr 26
        h1 = h1 and 0x3FFFFFFL
        h2 += c
        c = h2 ushr 26
        h2 = h2 and 0x3FFFFFFL
        h3 += c
        c = h3 ushr 26
        h3 = h3 and 0x3FFFFFFL
        h4 += c
        c = h4 ushr 26
        h4 = h4 and 0x3FFFFFFL
        h0 += c * 5
        c = h0 ushr 26
        h0 = h0 and 0x3FFFFFFL
        h1 += c

        // Select h if h < p, h - p otherwise (constant time)
        var g0 = h0 + 5
        c = g0 ushr 26
        g0 = g0 and 0x3FFFFFFL
        var g1 = h1 + c
        c = g1 ushr 26
        g1 = g1 and 0x3FFFFFFL
        var g2 = h2 + c
        c = g2 ushr 26
        g2 = g2 and 0x3FFFFFFL
        var g3 = h3 + c
        c = g3 ushr 26
        g3 = g3 and 0x3FFFFFFL
        val g4 = h4 + c - (1L shl 26)

        val mask = (g4 ushr 63) - 1L // all ones when h >= p (g4 non-negative)
        val notMask = mask.inv()
        h0 = (h0 and notMask) or (g0 and mask)
        h1 = (h1 and notMask) or (g1 and mask)
        h2 = (h2 and notMask) or (g2 and mask)
        h3 = (h3 and notMask) or (g3 and mask)
        h4 = (h4 and notMask) or (g4 and mask)

        // h += s, serialized little-endian
        val f0 = ((h0 or (h1 shl 26)) and 0xFFFFFFFFL) + (le32(key, 16).toLong() and 0xFFFFFFFFL)
        val f1 = (((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL) + (le32(key, 20).toLong() and 0xFFFFFFFFL)
        val f2 = (((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL) + (le32(key, 24).toLong() and 0xFFFFFFFFL)
        val f3 = (((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL) + (le32(key, 28).toLong() and 0xFFFFFFFFL)

        val out = ByteArray(16)
        putLe32(out, 0, f0.toInt())
        val f1c = f1 + (f0 ushr 32)
        putLe32(out, 4, f1c.toInt())
        val f2c = f2 + (f1c ushr 32)
        putLe32(out, 8, f2c.toInt())
        val f3c = f3 + (f2c ushr 32)
        putLe32(out, 12, f3c.toInt())
        return out
    }
}

private fun le32(
    b: ByteArray,
    i: Int,
): Int = (b[i].toInt() and 0xFF) or
    ((b[i + 1].toInt() and 0xFF) shl 8) or
    ((b[i + 2].toInt() and 0xFF) shl 16) or
    ((b[i + 3].toInt() and 0xFF) shl 24)

private fun putLe32(
    b: ByteArray,
    i: Int,
    v: Int,
) {
    b[i] = v.toByte()
    b[i + 1] = (v ushr 8).toByte()
    b[i + 2] = (v ushr 16).toByte()
    b[i + 3] = (v ushr 24).toByte()
}

private fun putLe64(
    b: ByteArray,
    i: Int,
    v: Long,
) {
    for (k in 0 until 8) b[i + k] = (v ushr (8 * k)).toByte()
}
