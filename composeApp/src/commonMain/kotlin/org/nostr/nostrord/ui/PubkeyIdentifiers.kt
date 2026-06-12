package org.nostr.nostrord.ui

import org.nostr.nostrord.nostr.Nip19

/** One representation in the cycling identifier field (prototype IdentifierField). */
data class PubkeyIdentifier(
    val label: String,
    val value: String,
)

/**
 * The real formats behind the prototype IdentifierField's mock list: npub,
 * nprofile, the nostrord profile link (#/u/ route), raw hex, and the NIP-05 when
 * present. Shared by the profile modal and profile page on both UIs.
 */
fun pubkeyIdentifiers(
    pubkeyHex: String,
    nip05: String? = null,
): List<PubkeyIdentifier> = buildList {
    val npub = runCatching { Nip19.encodeNpub(pubkeyHex) }.getOrNull()
    if (npub != null) add(PubkeyIdentifier("npub", npub))
    runCatching { Nip19.encodeNprofile(pubkeyHex) }.getOrNull()?.let {
        add(PubkeyIdentifier("nprofile", it))
    }
    if (npub != null) add(PubkeyIdentifier("nostrord link", "https://nostrord.com/#/u/$npub"))
    add(PubkeyIdentifier("hex", pubkeyHex))
    nip05?.takeIf { it.isNotBlank() }?.let { add(PubkeyIdentifier("nip-05", it)) }
}
