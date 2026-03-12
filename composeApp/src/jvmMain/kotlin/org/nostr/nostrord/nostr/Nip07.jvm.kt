package org.nostr.nostrord.nostr

actual object Nip07 {
    actual fun isAvailable(): Boolean = false
    actual suspend fun getPublicKey(): String = throw UnsupportedOperationException("NIP-07 is only available in browser environments")
    actual suspend fun signEvent(eventJson: String): String = throw UnsupportedOperationException("NIP-07 is only available in browser environments")
}
