@file:OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)

package org.nostr.nostrord.nostr

import fr.acinq.secp256k1.Secp256k1
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

actual object Nip04 {
    actual fun encrypt(
        plaintext: String,
        privateKeyHex: String,
        pubKeyHex: String,
    ): String {
        val sharedSecret = computeSharedSecret(privateKeyHex, pubKeyHex)
        val iv = secureRandomBytes(16)
        val encrypted = aesCbc(kCCEncrypt, sharedSecret, iv, plaintext.encodeToByteArray())
        return "${Base64.encode(encrypted)}?iv=${Base64.encode(iv)}"
    }

    actual fun decrypt(
        ciphertext: String,
        privateKeyHex: String,
        pubKeyHex: String,
    ): String {
        val parts = ciphertext.split("?iv=")
        if (parts.size != 2) {
            throw IllegalArgumentException("Invalid ciphertext format")
        }
        val encrypted = Base64.decode(parts[0])
        val iv = Base64.decode(parts[1])
        val sharedSecret = computeSharedSecret(privateKeyHex, pubKeyHex)
        return aesCbc(kCCDecrypt, sharedSecret, iv, encrypted).decodeToString()
    }

    private fun aesCbc(
        op: UInt,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val output = ByteArray(input.size + kCCBlockSizeAES128.toInt())
        memScoped {
            val moved = alloc<size_tVar>()
            val status = key.usePinned { k ->
                iv.usePinned { i ->
                    output.usePinned { out ->
                        // Empty input is valid for PKCS7 encrypt (yields one padding block).
                        if (input.isEmpty()) {
                            CCCrypt(
                                op, kCCAlgorithmAES, kCCOptionPKCS7Padding,
                                k.addressOf(0), key.size.convert(), i.addressOf(0),
                                null, 0u,
                                out.addressOf(0), output.size.convert(), moved.ptr,
                            )
                        } else {
                            input.usePinned { inp ->
                                CCCrypt(
                                    op, kCCAlgorithmAES, kCCOptionPKCS7Padding,
                                    k.addressOf(0), key.size.convert(), i.addressOf(0),
                                    inp.addressOf(0), input.size.convert(),
                                    out.addressOf(0), output.size.convert(), moved.ptr,
                                )
                            }
                        }
                    }
                }
            }
            if (status != kCCSuccess) {
                throw IllegalArgumentException("AES-CBC failed: CCCryptorStatus $status")
            }
            return output.copyOf(moved.value.toInt())
        }
    }

    private fun computeSharedSecret(
        privateKeyHex: String,
        pubKeyHex: String,
    ): ByteArray {
        val privateKeyBytes = privateKeyHex.hexToByteArray()

        // Convert 32-byte x-only pubkey to 33-byte compressed pubkey (add 02 prefix)
        val compressedPubKey = ("02" + pubKeyHex).hexToByteArray()

        // ECDH: multiply the public key point by our private key scalar.
        val sharedPoint = Secp256k1.pubKeyTweakMul(compressedPubKey, privateKeyBytes)

        // NIP-04 uses the X coordinate (skip the parity prefix byte).
        return sharedPoint.copyOfRange(1, 33)
    }
}
