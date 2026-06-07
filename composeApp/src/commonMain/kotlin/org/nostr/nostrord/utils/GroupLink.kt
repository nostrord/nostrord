package org.nostr.nostrord.utils

import org.nostr.nostrord.nostr.Nip19

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
 * Parse a join input into a [GroupJoinTarget]. Accepts:
 * - NIP-29 group address: `wss://relay.com'groupId` (or a bare `relay.com'groupId`)
 * - NIP-19 group naddr (as shared by ShareGroupModal): `nostr:naddr1...` / bare `naddr1...`
 * - nostrord invite link: `https://nostrord.com/open/?relay=X&group=Y&code=Z` / `nostrord://...`
 *
 * Returns null when no form yields a relay and group id.
 */
fun parseGroupJoinInput(input: String): GroupJoinTarget? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // NIP-19 group naddr: decode to its relay hint + group identifier. Requires a relay hint
    // (without it we don't know which relay hosts the group) and the group metadata kind.
    val bech = trimmed.removePrefix("nostr:")
    if (bech.startsWith("naddr1")) {
        val naddr = Nip19.decode(bech) as? Nip19.Entity.Naddr ?: return null
        if (naddr.kind != 39000) return null
        val relay = naddr.relays.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val groupId = naddr.identifier.takeIf { it.isNotBlank() } ?: return null
        return GroupJoinTarget(relay.toRelayUrl(), groupId)
    }

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
