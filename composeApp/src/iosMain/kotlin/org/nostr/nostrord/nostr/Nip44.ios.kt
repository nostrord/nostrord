@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package org.nostr.nostrord.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual object Nip44 {
    private const val VERSION: Byte = 2

    actual fun encrypt(
        plaintext: String,
        privateKeyHex: String,
        pubKeyHex: String,
    ): String {
        val conversationKey = getConversationKey(privateKeyHex, pubKeyHex)
        val nonce = secureRandomBytes(32)
        val messageKeys = getMessageKeys(conversationKey, nonce)

        val padded = pad(plaintext)
        val ciphertext = chacha20(messageKeys.chachaKey, messageKeys.chachaNonce, padded)

        // MAC = HMAC-SHA256(hmac_key, nonce || ciphertext)
        val mac = hmacSha256(messageKeys.hmacKey, nonce + ciphertext)

        // Final result: version (1) + nonce (32) + ciphertext + mac (32)
        val result = ByteArray(1 + 32 + ciphertext.size + 32)
        result[0] = VERSION
        nonce.copyInto(result, 1)
        ciphertext.copyInto(result, 33)
        mac.copyInto(result, 33 + ciphertext.size)

        return Base64.encode(result)
    }

    actual fun decrypt(
        ciphertext: String,
        privateKeyHex: String,
        pubKeyHex: String,
    ): String {
        val data = Base64.decode(ciphertext)

        if (data.size < 99) {
            throw IllegalArgumentException("Ciphertext too short: ${data.size} bytes")
        }

        val version = data[0]
        if (version.toInt() != 2) {
            throw IllegalArgumentException("Unsupported NIP-44 version: $version")
        }

        val nonce = data.copyOfRange(1, 33)
        val ciphertextBytes = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)

        val conversationKey = getConversationKey(privateKeyHex, pubKeyHex)
        val messageKeys = getMessageKeys(conversationKey, nonce)

        // Verify MAC: HMAC-SHA256(hmac_key, nonce || ciphertext)
        val expectedMac = hmacSha256(messageKeys.hmacKey, nonce + ciphertextBytes)
        if (!mac.contentEquals(expectedMac)) {
            throw IllegalArgumentException("Invalid MAC")
        }

        val padded = chacha20(messageKeys.chachaKey, messageKeys.chachaNonce, ciphertextBytes)
        return unpad(padded)
    }

    private fun getConversationKey(
        privateKeyHex: String,
        pubKeyHex: String,
    ): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()

        // Try both Y coordinate parities (02 = even, 03 = odd)
        for (prefix in listOf("02", "03")) {
            try {
                val compressedPubKey = (prefix + pubKeyHex).hexToByteArray()
                val sharedPoint = Secp256k1.pubKeyTweakMul(compressedPubKey, privateKeyBytes)
                val sharedX = sharedPoint.copyOfRange(1, 33)

                // NIP-44: conversation_key = HKDF-Extract(salt="nip44-v2", ikm=shared_x)
                return hmacSha256("nip44-v2".encodeToByteArray(), sharedX)
            } catch (e: Exception) {
                continue
            }
        }
        throw IllegalArgumentException("Invalid public key: $pubKeyHex")
    }

    private class MessageKeys(
        val chachaKey: ByteArray,
        val chachaNonce: ByteArray,
        val hmacKey: ByteArray,
    )

    private fun getMessageKeys(
        conversationKey: ByteArray,
        nonce: ByteArray,
    ): MessageKeys {
        // HKDF-Expand(prk=conversation_key, info=nonce, L=76)
        val keys = hkdfExpand(conversationKey, nonce, 76)
        return MessageKeys(
            chachaKey = keys.copyOfRange(0, 32),
            chachaNonce = keys.copyOfRange(32, 44),
            hmacKey = keys.copyOfRange(44, 76),
        )
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { k ->
            mac.usePinned { out ->
                if (data.isEmpty()) {
                    CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), null, 0u, out.addressOf(0))
                } else {
                    data.usePinned { d ->
                        CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.convert(), d.addressOf(0), data.size.convert(), out.addressOf(0))
                    }
                }
            }
        }
        return mac
    }

    private fun hkdfExpand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val result = ByteArray(length)
        var prev = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {
            prev = hmacSha256(prk, prev + info + counter)
            val toCopy = minOf(prev.size, length - offset)
            prev.copyInto(result, offset, 0, toCopy)
            offset += toCopy
            counter++
        }

        return result
    }

    // ChaCha20 (RFC 8439): 12-byte nonce, 32-bit block counter starting at 0.
    // CommonCrypto exposes no raw ChaCha20 (CryptoKit only has the Poly1305 AEAD),
    // so the stream cipher is implemented here; NIP-44 authenticates via HMAC.
    private fun chacha20(
        key: ByteArray,
        nonce: ByteArray,
        data: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }

        val output = ByteArray(data.size)
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = key.leInt(i * 4)
        // state[12] = counter, set per block
        for (i in 0 until 3) state[13 + i] = nonce.leInt(i * 4)

        val working = IntArray(16)
        val keystream = ByteArray(64)
        var counter = 0
        var offset = 0
        while (offset < data.size) {
            state[12] = counter
            state.copyInto(working)
            repeat(10) {
                // Column rounds
                quarterRound(working, 0, 4, 8, 12)
                quarterRound(working, 1, 5, 9, 13)
                quarterRound(working, 2, 6, 10, 14)
                quarterRound(working, 3, 7, 11, 15)
                // Diagonal rounds
                quarterRound(working, 0, 5, 10, 15)
                quarterRound(working, 1, 6, 11, 12)
                quarterRound(working, 2, 7, 8, 13)
                quarterRound(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val word = working[i] + state[i]
                keystream[i * 4] = (word and 0xFF).toByte()
                keystream[i * 4 + 1] = ((word ushr 8) and 0xFF).toByte()
                keystream[i * 4 + 2] = ((word ushr 16) and 0xFF).toByte()
                keystream[i * 4 + 3] = ((word ushr 24) and 0xFF).toByte()
            }
            val blockLen = minOf(64, data.size - offset)
            for (i in 0 until blockLen) {
                output[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += blockLen
            counter++
        }
        return output
    }

    private fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]
        s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]
        s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]
        s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]
        s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    private fun ByteArray.leInt(offset: Int): Int = (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun pad(plaintext: String): ByteArray {
        val unpadded = plaintext.encodeToByteArray()
        if (unpadded.isEmpty() || unpadded.size > 65535) {
            throw IllegalArgumentException("Invalid plaintext length: ${unpadded.size}")
        }

        val paddedLength = calcPaddedLen(unpadded.size)
        val result = ByteArray(2 + paddedLength)

        // Big-endian length prefix
        result[0] = ((unpadded.size shr 8) and 0xFF).toByte()
        result[1] = (unpadded.size and 0xFF).toByte()
        unpadded.copyInto(result, 2)

        return result
    }

    private fun unpad(padded: ByteArray): String {
        if (padded.size < 2) {
            throw IllegalArgumentException("Padded data too short")
        }

        val length = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)

        if (length < 1 || length > padded.size - 2) {
            throw IllegalArgumentException("Invalid padding length: $length, padded size: ${padded.size}")
        }

        return padded.decodeToString(2, 2 + length)
    }

    private fun calcPaddedLen(unpaddedLen: Int): Int {
        if (unpaddedLen <= 32) return 32

        val nextPower = (unpaddedLen - 1).takeHighestOneBit() shl 1
        val chunk = maxOf(32, nextPower / 8)
        return ((unpaddedLen - 1) / chunk + 1) * chunk
    }

    private fun String.hexToByteArray(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
