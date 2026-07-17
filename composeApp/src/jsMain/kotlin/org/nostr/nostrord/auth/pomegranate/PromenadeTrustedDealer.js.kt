@file:JsModule("@fiatjaf/promenade-trusted-dealer")
@file:JsNonModule

package org.nostr.nostrord.auth.pomegranate

import org.khronos.webgl.Uint8Array

// `masterSk` and the return of `aggregateSecretKeyShards` are JS BigInt; shard values
// are the lib's opaque Shard objects. All crossings stay dynamic and are wrapped by
// PomegranateDealer.

internal external fun trustedKeyDeal(
    masterSk: dynamic,
    threshold: Int,
    count: Int,
): dynamic

internal external fun hexShard(shard: dynamic): String

internal external fun hexPubShard(pubShard: dynamic): String

internal external fun decodeShard(bytes: Uint8Array): dynamic

internal external fun aggregateSecretKeyShards(shards: Array<dynamic>): dynamic
