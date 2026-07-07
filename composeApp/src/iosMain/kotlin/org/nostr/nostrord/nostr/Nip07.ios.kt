package org.nostr.nostrord.nostr

// NIP-07 is a browser-extension signer (window.nostr); it does not exist on iOS.
actual object Nip07 {
    actual fun isAvailable(): Boolean = false

    actual suspend fun getPublicKey(): String = unavailable()

    actual suspend fun signEvent(eventJson: String): String = unavailable()

    actual suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = unavailable()

    actual suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = unavailable()

    private fun unavailable(): Nothing = throw UnsupportedOperationException("NIP-07 is only available in browser environments")
}
