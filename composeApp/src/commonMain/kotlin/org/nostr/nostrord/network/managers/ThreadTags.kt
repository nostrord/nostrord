package org.nostr.nostrord.network.managers

import org.nostr.nostrord.network.NostrGroupClient

/**
 * Pure builders for forum-thread event tags (NIP-29 kind:11 root + NIP-22 kind:1111 reply).
 * Kept out of [GroupManager] so the protocol-critical tag shape is unit-testable in isolation
 * (GroupManager needs the whole network stack to instantiate; these are pure functions).
 */
internal object ThreadTags {
    /**
     * Tags for a kind:11 thread root: the NIP-29 group `h` tag (with a relay hint when known),
     * the relay-wide `-` marker for the id-less `_` group, and an optional NIP-14 `subject`
     * (the thread title; omitted when blank).
     */
    fun root(groupId: String, relayHint: String?, subject: String?): List<List<String>> = buildList {
        add(listOfNotNull("h", groupId, relayHint))
        if (groupId == "_") add(listOf("-"))
        val s = subject?.trim().orEmpty()
        if (s.isNotEmpty()) add(listOf("subject", s))
    }

    /**
     * Tags for a kind:1111 reply. Uppercase E/K/P carry the root scope, kept identical across the
     * whole thread so nested replies stay attached to the original kind:11 root; lowercase e/k/p
     * point at the immediate [parent] (which equals [root] for a top-level reply). Plus the group
     * `h` (and `-` for `_`) tags so the relay scopes it to the group.
     */
    fun reply(
        groupId: String,
        relayHint: String?,
        root: NostrGroupClient.NostrMessage,
        parent: NostrGroupClient.NostrMessage,
    ): List<List<String>> = buildList {
        add(listOfNotNull("h", groupId, relayHint))
        if (groupId == "_") add(listOf("-"))
        val hint = relayHint ?: ""
        add(listOf("E", root.id, hint, root.pubkey))
        add(listOf("K", root.kind.toString()))
        add(listOf("P", root.pubkey))
        add(listOf("e", parent.id, hint, parent.pubkey))
        add(listOf("k", parent.kind.toString()))
        add(listOf("p", parent.pubkey))
    }
}
