package org.nostr.nostrord.auth.pomegranate

import io.github.nostrord.pomegranatedealer.FrostDealer
import org.nostr.nostrord.nostr.Crypto

// FROST trusted dealer on native, delegating to the standalone :pomegranate-dealer module
// (byte-compatible with the web's npm dealer). The polynomial's secret coefficients come from
// the platform CSPRNG via Crypto.generatePrivateKey.
internal actual object PomegranateDealer {
    actual fun deal(
        secretKeyHex: String,
        threshold: Int,
        count: Int,
    ): List<PomegranateShard> = FrostDealer
        .deal(secretKeyHex, threshold, count) { Crypto.generatePrivateKey() }
        .map { PomegranateShard(shardHex = it.shardHex, pubShardHex = it.pubShardHex) }

    actual fun aggregate(shardHexes: List<String>): String = FrostDealer.aggregate(shardHexes)
}
