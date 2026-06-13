package org.nostr.nostrord.ui

import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.toHash

/** One representation in a cycling identifier field (prototype IdentifierField). */
data class Identifier(
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
    nprofileRelays: List<String> = emptyList(),
): List<Identifier> = buildList {
    val npub = runCatching { Nip19.encodeNpub(pubkeyHex) }.getOrNull()
    if (npub != null) add(Identifier("npub", npub))
    runCatching { Nip19.encodeNprofile(pubkeyHex, nprofileRelays) }.getOrNull()?.let {
        add(Identifier("nprofile", it))
    }
    if (npub != null) add(Identifier("nostrord link", "https://nostrord.com/#/u/$npub"))
    add(Identifier("hex", pubkeyHex))
    nip05?.takeIf { isValidNip05(it) }?.let { add(Identifier("nip-05", it)) }
}

/**
 * Relay hints for a user's nprofile: the user's NIP-65 write relays when known,
 * otherwise [fallback] (content-rich defaults where the profile is still likely
 * findable). Capped at 2 so the bech32 stays short. Callers feed the cached
 * NIP-65 list (repo.getRelayListForPubkey), so this never hits the network.
 */
fun nprofileRelayHints(
    nip65Relays: List<Nip65Relay>,
    fallback: List<String> = RelayListManager.DEFAULT_FALLBACK_RELAYS,
): List<String> {
    val writes = nip65Relays.filter { it.write }.map { it.url }
    return writes.ifEmpty { fallback }.take(NPROFILE_MAX_RELAY_HINTS)
}

private const val NPROFILE_MAX_RELAY_HINTS = 2

/**
 * NIP-05 shape check: `local@domain.tld` with the spec's local-part charset
 * (a-z0-9, dash, underscore, dot). Junk metadata (URLs, plain names) is dropped
 * from the identifier list instead of being offered as a copyable "nip-05".
 */
private val NIP05_REGEX = Regex("^[a-z0-9._-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+$", RegexOption.IGNORE_CASE)

fun isValidNip05(nip05: String): Boolean = NIP05_REGEX.matches(nip05.trim())

/**
 * The group-address formats cycled in the group info modal (prototype ADDR_FORMATS):
 * relay'groupId, the real naddr, and the nostrord group link (#/g/ route). Shared by
 * both UIs. Empty when the relay or group id is blank.
 *
 * With an [inviteCode], every format carries the Nostrord `?invite=` suffix extension:
 * everything before the `?` stays a plain address any client understands, and
 * `parseGroupJoinInput` reads the code back for auto-join.
 */
fun groupIdentifiers(
    relayUrl: String,
    groupId: String,
    relayPubkey: String? = null,
    inviteCode: String? = null,
): List<Identifier> = buildList {
    val host = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    if (host.isBlank() || groupId.isBlank()) return@buildList
    val invite = inviteCode?.takeIf { it.isNotBlank() }?.let { "?invite=$it" } ?: ""
    add(Identifier("relay'groupId", "$host'$groupId$invite"))
    runCatching { Nip19.encodeNaddr(identifier = groupId, relay = relayUrl, kind = 39000, pubkeyHex = relayPubkey) }
        .getOrNull()
        ?.let { add(Identifier("naddr", "$it$invite")) }
    // The link form carries the invite inside the hash route itself (?invite= after the id).
    val route = GroupRoute(relayUrl = relayUrl, groupId = groupId, inviteCode = inviteCode?.takeIf { it.isNotBlank() })
    add(Identifier("nostrord link", "https://nostrord.com/" + route.toHash()))
}
