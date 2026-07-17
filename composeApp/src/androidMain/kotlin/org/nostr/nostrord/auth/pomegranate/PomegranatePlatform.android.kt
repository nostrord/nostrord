package org.nostr.nostrord.auth.pomegranate

// Pomegranate (Login with Google) is browser-only: it rides Google sign-in popups and
// a JS-only npm dealer. isAvailable=false keeps every UI entry point hidden here.

internal actual object PomegranatePopups {
    actual val isAvailable: Boolean = false

    actual suspend fun awaitTokenFromPopup(
        url: String,
        expectedOrigin: String,
    ): String = throw UnsupportedOperationException("Pomegranate login is web-only")

    actual suspend fun awaitShardFromPopup(
        url: String,
        expectedOrigin: String,
    ): String = throw UnsupportedOperationException("Pomegranate login is web-only")
}

internal actual object PomegranateDealer {
    actual fun deal(
        secretKeyHex: String,
        threshold: Int,
        count: Int,
    ): List<PomegranateShard> = throw UnsupportedOperationException("Pomegranate login is web-only")

    actual fun aggregate(shardHexes: List<String>): String = throw UnsupportedOperationException("Pomegranate login is web-only")
}
