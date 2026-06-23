package org.nostr.nostrord.utils

import org.nostr.nostrord.nostr.Nip19

/**
 * A short, user-facing label for a pubkey when no profile name is known: the npub form truncated
 * to [chars] glyphs plus an ellipsis (e.g. "npub1abcdef…"). Falls back to the raw hex (truncated)
 * only if npub encoding fails. Use this everywhere a name would otherwise show a bare hex pubkey,
 * so the whole app speaks npub instead of hex. Not for avatar initials, where the first glyph of
 * the npub ("n") carries no identity.
 */
fun shortNpub(pubkey: String, chars: Int = 12): String {
    val npub =
        try {
            Nip19.encodeNpub(pubkey)
        } catch (_: Exception) {
            pubkey
        }
    return npub.take(chars) + "…"
}
