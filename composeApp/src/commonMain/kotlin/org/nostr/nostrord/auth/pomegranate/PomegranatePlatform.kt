package org.nostr.nostrord.auth.pomegranate

/**
 * Browser popup half of the pomegranate flow. Real only on JS: opens a window on the
 * central/operator origin and resolves the value it posts back via postMessage.
 * Other targets report unavailable and throw, so the UIs never reach them.
 */
internal expect object PomegranatePopups {
    val isAvailable: Boolean

    /** Opens the Google sign-in popup at [url] and resolves the auth token it posts back. */
    suspend fun awaitTokenFromPopup(
        url: String,
        expectedOrigin: String,
    ): String

    /** Opens an operator's recovery popup at [url] and resolves the shard hex it posts back. */
    suspend fun awaitShardFromPopup(
        url: String,
        expectedOrigin: String,
    ): String
}

/** FROST trusted dealer, backed by the JS-only npm lib `@fiatjaf/promenade-trusted-dealer`. */
internal expect object PomegranateDealer {
    /** Splits the key into [count] shards with signing threshold [threshold]. */
    fun deal(
        secretKeyHex: String,
        threshold: Int,
        count: Int,
    ): List<PomegranateShard>

    /** Reassembles the secret key hex from at least threshold-many shard hexes. */
    fun aggregate(shardHexes: List<String>): String
}
