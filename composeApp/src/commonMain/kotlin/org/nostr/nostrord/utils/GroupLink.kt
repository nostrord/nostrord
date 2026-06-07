package org.nostr.nostrord.utils

/** A parsed join target: which relay hosts the group, the group id, and an optional invite code. */
data class GroupJoinTarget(
    val relayUrl: String,
    val groupId: String,
    val inviteCode: String? = null,
)

/**
 * Build the NIP-29 group address `wss://host'groupId` from a relay url + group id. This is the
 * compact reference clients paste into chat; [parseGroupJoinInput] reads it back, and the chat
 * message renderer turns it into a tappable group card.
 */
fun buildGroupAddress(relayUrl: String, groupId: String): String {
    val normalized = relayUrl.toRelayUrl().trimEnd('/')
    return "$normalized'$groupId"
}

/**
 * Parse a join input into a [GroupJoinTarget]. Accepts both:
 * - NIP-29 group address: `wss://relay.com'groupId` (or a bare `relay.com'groupId`)
 * - nostrord invite link: `https://nostrord.com/open/?relay=X&group=Y&code=Z` / `nostrord://...`
 *
 * Returns null when neither form yields a relay and group id.
 */
fun parseGroupJoinInput(input: String): GroupJoinTarget? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // NIP-29 group address takes priority: an apostrophe separates relay from group id, and the
    // form carries no query string (so we don't mistake an invite link's `?code=a'b` for one).
    val apostrophe = trimmed.indexOf('\'')
    if (apostrophe > 0 && trimmed.indexOf('?') < 0) {
        val relayPart = trimmed.substring(0, apostrophe).trim()
        val groupId = trimmed.substring(apostrophe + 1).trim()
        if (groupId.isEmpty()) return null
        val relayUrl = relayPart.toRelayUrl()
        if (relayUrl.isEmpty()) return null
        return GroupJoinTarget(relayUrl, groupId)
    }

    // Invite-link form: pull the relay/group/code query params.
    val queryStart = trimmed.indexOf('?')
    if (queryStart < 0) return null
    val params =
        trimmed.substring(queryStart + 1).split("&").associate { param ->
            val idx = param.indexOf("=")
            if (idx >= 0) param.substring(0, idx) to param.substring(idx + 1) else param to ""
        }
    val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: return null
    val group = params["group"]?.takeIf { it.isNotBlank() } ?: return null
    return GroupJoinTarget(relay.toRelayUrl(), group, params["code"]?.takeIf { it.isNotBlank() })
}
