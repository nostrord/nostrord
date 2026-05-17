package org.nostr.nostrord.nostr

/**
 * Nostr key pair with signing capabilities
 */
data class KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    val privateKeyHex: String get() = privateKey.toHexString()
    val publicKeyHex: String get() = Crypto.getPublicKeyXOnly(privateKey).toHexString()

    companion object {
        /**
         * Generate new key pair
         */
        fun generate(): KeyPair {
            val privateKey = Crypto.generatePrivateKey()
            val publicKey = Crypto.getPublicKey(privateKey)
            return KeyPair(privateKey, publicKey)
        }

        /**
         * Create from private key hex
         */
        fun fromPrivateKeyHex(privateKeyHex: String): KeyPair {
            val privateKey = privateKeyHex.hexToByteArray()
            val publicKey = Crypto.getPublicKey(privateKey)
            return KeyPair(privateKey, publicKey)
        }
    }

    /**
     * Sign a message hash
     */
    fun signMessage(messageHash: ByteArray): ByteArray = Crypto.signMessage(privateKey, messageHash)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPair) return false
        return privateKey.contentEquals(other.privateKey)
    }

    override fun hashCode(): Int = privateKey.contentHashCode()
}
