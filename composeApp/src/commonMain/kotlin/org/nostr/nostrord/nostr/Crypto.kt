package org.nostr.nostrord.nostr

expect object Crypto {
    fun generatePrivateKey(): ByteArray

    fun getPublicKey(privateKey: ByteArray): ByteArray

    fun getPublicKeyXOnly(privateKey: ByteArray): ByteArray

    fun getPublicKeyHex(privateKey: ByteArray): String

    fun signMessage(
        privateKey: ByteArray,
        messageHash: ByteArray,
    ): ByteArray

    fun verifySignature(
        signature: ByteArray,
        messageHash: ByteArray,
        publicKey: ByteArray,
    ): Boolean

    fun sha256(data: ByteArray): ByteArray

    fun sha256(data: String): ByteArray
}

// Common utility extensions (pure Kotlin)
fun ByteArray.toHexString(): String = buildString(size * 2) {
    for (b in this@toHexString) {
        val i = b.toInt() and 0xFF
        append("0123456789abcdef"[i ushr 4])
        append("0123456789abcdef"[i and 0x0F])
    }
}

fun String.hexToByteArray(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
