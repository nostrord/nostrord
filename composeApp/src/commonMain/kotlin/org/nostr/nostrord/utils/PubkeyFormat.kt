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

/**
 * A display label for an account. A real profile name or custom label is returned as-is; a bare full
 * npub (the fallback when no name is known) is shortened to [shortNpub] so it doesn't blow up a
 * dialog title. Use wherever an account label is shown to the user as free text.
 */
fun accountDisplayLabel(label: String, pubkey: String, chars: Int = 16): String = if (label.startsWith("npub1") && label.length > chars) shortNpub(pubkey, chars) else label
