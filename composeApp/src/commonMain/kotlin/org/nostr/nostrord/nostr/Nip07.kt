package org.nostr.nostrord.nostr

/**
 * NIP-07 browser extension signer interface.
 * https://github.com/nostr-protocol/nips/blob/master/07.md
 */
expect object Nip07 {
    /** Returns true if a NIP-07 compatible browser extension is available (window.nostr). */
    fun isAvailable(): Boolean

    /** Calls window.nostr.getPublicKey() and returns the user's public key as hex. */
    suspend fun getPublicKey(): String

    /**
     * Calls window.nostr.signEvent() with the given event JSON and returns the
     * signed event as a JSON string.
     */
    suspend fun signEvent(eventJson: String): String

    /** Calls window.nostr.nip44.encrypt(peerPubkey, plaintext); used to seal NIP-17 DMs. */
    suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String

    /** Calls window.nostr.nip44.decrypt(peerPubkey, ciphertext); used to open NIP-17 DMs. */
    suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String
}
