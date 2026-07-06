@file:OptIn(ExperimentalForeignApi::class)

package org.nostr.nostrord.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned {
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return bytes
}

internal fun ccSha256(data: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    digest.usePinned { out ->
        if (data.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0).reinterpret())
        } else {
            data.usePinned { input ->
                CC_SHA256(input.addressOf(0), data.size.convert(), out.addressOf(0).reinterpret())
            }
        }
    }
    return digest
}

actual object Crypto {
    private val secp256k1 = Secp256k1.get()

    actual fun generatePrivateKey(): ByteArray {
        while (true) {
            val priv = secureRandomBytes(32)
            if (secp256k1.secKeyVerify(priv)) return priv
        }
    }

    actual fun getPublicKey(privateKey: ByteArray): ByteArray {
        val pub = secp256k1.pubkeyCreate(privateKey)
        return secp256k1.pubKeyCompress(pub)
    }

    actual fun getPublicKeyXOnly(privateKey: ByteArray): ByteArray = getPublicKey(privateKey).copyOfRange(1, 33)

    actual fun getPublicKeyHex(privateKey: ByteArray): String = getPublicKeyXOnly(privateKey).toHexString()

    // BIP-340 signs with the secret whose point has even Y; negating mod n flips the
    // parity (equivalent to the jvm actual's n - d, without BigInteger).
    private fun ensureEvenYSecretKey(privateKey: ByteArray): ByteArray {
        val compressed = getPublicKey(privateKey)
        return if ((compressed[0].toInt() and 0xFF) == 0x03) {
            secp256k1.privKeyNegate(privateKey)
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

    actual fun sha256(data: ByteArray): ByteArray = ccSha256(data)

    actual fun sha256(data: String): ByteArray = ccSha256(data.encodeToByteArray())
}
