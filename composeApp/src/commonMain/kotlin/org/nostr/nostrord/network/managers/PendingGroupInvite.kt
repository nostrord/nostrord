package org.nostr.nostrord.network.managers

import kotlinx.serialization.Serializable

/**
 * A membership granted externally (an admin's kind:9000 put-user, or a kind:39002 listing
 * us with no join request of ours) that the user has not accepted or declined yet. Relay-side
 * we are already a member; accepting adopts the group into the joined set + kind:10009,
 * declining leaves (kind:9022). [actorPubkey]/[eventId] are null when the add was inferred
 * from a 39002 listing instead of the put-user event itself.
 */
@Serializable
data class PendingGroupInvite(
    val groupId: String,
    val relayUrl: String,
    val actorPubkey: String? = null,
    val eventId: String? = null,
    val createdAtSeconds: Long = 0L,
)
