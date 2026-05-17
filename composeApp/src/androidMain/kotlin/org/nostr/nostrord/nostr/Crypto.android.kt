package org.nostr.nostrord.nostr

import fr.acinq.secp256k1.Secp256k1
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

actual object Crypto {
    private val secp256k1 = Secp256k1.get()
    private val secureRandom = SecureRandom()
    private val n = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

    actual fun generatePrivateKey(): ByteArray {
        while (true) {
            val priv = ByteArray(32)
            secureRandom.nextBytes(priv)
            if (secp256k1.secKeyVerify(priv)) return priv
        }
    }

    actual fun getPublicKey(privateKey: ByteArray): ByteArray {
        val pub = secp256k1.pubkeyCreate(privateKey)
        return secp256k1.pubKeyCompress(pub)
    }

    actual fun getPublicKeyXOnly(privateKey: ByteArray): ByteArray = getPublicKey(privateKey).copyOfRange(1, 33)

    actual fun getPublicKeyHex(privateKey: ByteArray): String = getPublicKeyXOnly(privateKey).toHexString()

    private fun ensureEvenYSecretKey(privateKey: ByteArray): ByteArray {
        val compressed = getPublicKey(privateKey)
        val prefix = compressed[0].toInt() and 0xFF
        return if (prefix == 0x03) {
            val d = BigInteger(1, privateKey)
            val dPrime = n.subtract(d).mod(n)
            dPrime.toFixedLengthByteArray(32)
        } else {
            privateKey
        }
    }

    actual fun signMessage(
        privateKey: ByteArray,
        messageHash: ByteArray,
    ): ByteArray {
        val secret = ensureEvenYSecretKey(privateKey)
        return secp256k1.signSchnorr(messageHash, secret, null)
    }

    actual fun verifySignature(
        signature: ByteArray,
        messageHash: ByteArray,
        publicKey: ByteArray,
    ): Boolean {
        val xOnly =
            when (publicKey.size) {
                32 -> publicKey
                33 -> publicKey.copyOfRange(1, 33)
                else -> throw IllegalArgumentException("Public key must be 32 or 33 bytes")
            }
        return secp256k1.verifySchnorr(signature, messageHash, xOnly)
    }

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun sha256(data: String): ByteArray = sha256(data.toByteArray())
}

fun BigInteger.toFixedLengthByteArray(length: Int): ByteArray {
    val arr = this.toByteArray()
    return when {
        arr.size == length -> arr
        arr.size > length -> arr.copyOfRange(arr.size - length, arr.size)
        else -> ByteArray(length - arr.size) + arr
    }
}
