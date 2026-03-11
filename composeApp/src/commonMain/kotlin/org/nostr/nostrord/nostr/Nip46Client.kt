package org.nostr.nostrord.nostr

expect class Nip46Client(existingPrivateKey: String? = null) {
    var onAuthUrl: ((String) -> Unit)?
    val clientPubkey: String
    val clientPrivateKey: String

    suspend fun connect(
        remoteSignerPubkey: String,
        relays: List<String>,
        secret: String?
    ): String

    /**
     * Generate a nostrconnect:// URI for display as QR code.
     * The remote signer scans this to initiate the connection.
     */
    fun generateNostrConnectUri(relays: List<String>, name: String = "Nostrord"): String

    /**
     * Connect to relays and subscribe for incoming NIP-46 events.
     * Must be called BEFORE showing the QR code so events aren't missed.
     */
    suspend fun startListeningForConnection(relays: List<String>, secret: String?)

    /**
     * Wait for an incoming connection from a remote signer.
     * Must call startListeningForConnection() first.
     * Returns the signer's public key on success.
     */
    suspend fun awaitIncomingConnection(): String

    suspend fun getPublicKey(): String
    suspend fun signEvent(eventJson: String): String
    fun disconnect()
}
