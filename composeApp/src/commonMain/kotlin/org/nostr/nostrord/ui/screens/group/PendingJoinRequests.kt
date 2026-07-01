package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.NostrGroupClient

/**
 * The join requests (kind:9021) still awaiting an admin decision for a closed group, newest first.
 * Shared by both the Compose and web Manage-group "Requests" tabs so the two never drift.
 *
 * A request is pending when the requester is not currently a member AND their 9021 is newer than
 * every later exit for that pubkey. Two kinds count as an exit:
 *  - kind:9022 (leave) is self-signed, so the leaver is `event.pubkey`.
 *  - kind:9001 (admin remove) is signed by the admin, so the removed user is the `p` tag, not the
 *    author. Without this, removing a member flips them back to a non-member with their original
 *    9021 still in history and no 9022, so the stale request resurfaces in the queue.
 *
 * A genuine re-request (a fresh 9021 sent after being removed/leaving) is newer than the last exit,
 * so it correctly reappears.
 */
fun pendingJoinRequests(
    messages: List<NostrGroupClient.NostrMessage>,
    members: Set<String>,
): List<NostrGroupClient.NostrMessage> {
    val lastExit = HashMap<String, Long>()
    for (m in messages) {
        when (m.kind) {
            9022 -> mergeLatest(lastExit, m.pubkey, m.createdAt)
            9001 ->
                m.tags
                    .filter { it.firstOrNull() == "p" }
                    .forEach { tag -> tag.getOrNull(1)?.let { mergeLatest(lastExit, it, m.createdAt) } }
        }
    }
    return messages
        .filter { it.kind == 9021 && it.pubkey !in members }
        .filter { req -> lastExit[req.pubkey].let { it == null || req.createdAt > it } }
        .distinctBy { it.pubkey }
        .sortedByDescending { it.createdAt }
}

private fun mergeLatest(
    into: HashMap<String, Long>,
    pubkey: String,
    createdAt: Long,
) {
    val current = into[pubkey]
    if (current == null || createdAt > current) into[pubkey] = createdAt
}
