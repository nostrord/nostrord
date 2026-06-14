package org.nostr.nostrord.ui.navigation

import org.nostr.nostrord.nostr.Nip19

/**
 * A new-design page addressable by the web hash (and mirrored as native navigation
 * state). The first hash segment is the page-type namespace, prototype-style:
 * `#/g/…` for groups, `#/u/…` for user profiles.
 */
sealed interface HashRoute

/**
 * Canonical route for a group page, shared by the web hash router and the native
 * navigation state. Web form (GitHub Pages friendly; the prototype's /g/:groupId
 * extended with the relay, since Nostrord is multi-relay):
 *
 *   #/g/<relay>/<groupId>[?invite=<code>]
 *
 * <relay> is the relay URL without the wss:// scheme, percent-encoded as a single
 * path segment (a rare path-bearing relay encodes its '/' as %2F), so the group id
 * always sits in the last segment: #/g/groups.0xchat.com/chachi. An explicit invite
 * code travels as a ?invite= query inside the hash and auto-joins on arrival (same
 * semantics as the existing ?relay=&group=&invite= deep link). NIP-29 relays are
 * TLS-only in practice, so the scheme is fixed to wss://.
 */
data class GroupRoute(
    val relayUrl: String,
    val groupId: String,
    val inviteCode: String? = null,
) : HashRoute

/**
 * Route for a user profile page: #/u/<npub>. [pubkey] is hex internally; the hash
 * carries the npub (shareable, nostr-native) but hex is accepted on parse too.
 */
data class UserRoute(
    val pubkey: String,
) : HashRoute

/**
 * Route for the direct-messages section: #/dm (conversation list) or
 * #/dm/<npub> (one conversation). Same npub-out / npub-or-hex-in rule as [UserRoute].
 */
data class DmRoute(
    val pubkey: String? = null,
) : HashRoute

/**
 * Route for the notifications page: #/notifications (the singular #/notification is
 * accepted on parse too). A parameterless page route mirrored like the others, so a
 * refresh or a hand-typed/shared #/notifications reopens the page instead of falling
 * back to Home.
 */
data object NotificationsRoute : HashRoute

private const val GROUP_HASH_PREFIX = "#/g/"
private const val USER_HASH_PREFIX = "#/u/"
private const val DM_HASH_PREFIX = "#/dm"
private const val NOTIFICATIONS_HASH = "#/notifications"
private const val HEX = "0123456789ABCDEF"

fun HashRoute.toHash(): String = when (this) {
    is GroupRoute -> toHash()
    is UserRoute -> toHash()
    is DmRoute -> toHash()
    is NotificationsRoute -> NOTIFICATIONS_HASH
}

fun parseHashRoute(hash: String): HashRoute? = parseGroupHash(hash) ?: parseUserHash(hash) ?: parseDmHash(hash) ?: parseNotificationsHash(hash)

/** Parses `#/notifications` (or the singular `#/notification`); null for any other hash. */
fun parseNotificationsHash(hash: String): NotificationsRoute? {
    val path = hash.substringBefore('?')
    return if (path == NOTIFICATIONS_HASH || path == "#/notification") NotificationsRoute else null
}

fun UserRoute.toHash(): String = "$USER_HASH_PREFIX${encodePubkeySegment(pubkey)}"

/** Parses a `#/u/<npub or hex pubkey>` hash; null for any other hash. */
fun parseUserHash(hash: String): UserRoute? {
    if (!hash.startsWith(USER_HASH_PREFIX)) return null
    val hex = decodePubkeySegment(hash.removePrefix(USER_HASH_PREFIX).substringBefore('?')) ?: return null
    return UserRoute(hex)
}

fun DmRoute.toHash(): String {
    val pk = pubkey ?: return DM_HASH_PREFIX
    return "$DM_HASH_PREFIX/${encodePubkeySegment(pk)}"
}

/** Parses `#/dm` or `#/dm/<npub or hex pubkey>`; null for any other hash. */
fun parseDmHash(hash: String): DmRoute? {
    if (hash == DM_HASH_PREFIX || hash.substringBefore('?') == DM_HASH_PREFIX) return DmRoute(null)
    if (!hash.startsWith("$DM_HASH_PREFIX/")) return null
    val hex = decodePubkeySegment(hash.removePrefix("$DM_HASH_PREFIX/").substringBefore('?')) ?: return null
    return DmRoute(hex)
}

private fun encodePubkeySegment(pubkeyHex: String): String = encodeSegment(runCatching { Nip19.encodeNpub(pubkeyHex) }.getOrDefault(pubkeyHex))

/** Decodes an npub or 64-hex path segment to a hex pubkey; null when neither. */
private fun decodePubkeySegment(segment: String): String? {
    val raw = decodeSegment(segment)
    if (raw.isEmpty() || raw.contains('/')) return null
    return if (raw.startsWith("npub1")) {
        (runCatching { Nip19.decode(raw) }.getOrNull() as? Nip19.Entity.Npub)?.pubkey
    } else {
        raw.lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}

fun GroupRoute.toHash(): String {
    val relay = encodeSegment(relayUrl.removePrefix("wss://"))
    val id = encodeSegment(groupId)
    val invite = inviteCode?.let { "?invite=${encodeSegment(it)}" } ?: ""
    return "$GROUP_HASH_PREFIX$relay/$id$invite"
}

/** Parses a `#/g/<relay>/<groupId>[?invite=…]` hash; null for any other hash. */
fun parseGroupHash(hash: String): GroupRoute? {
    if (!hash.startsWith(GROUP_HASH_PREFIX)) return null
    val rest = hash.removePrefix(GROUP_HASH_PREFIX)
    val path = rest.substringBefore('?')
    val query = rest.substringAfter('?', "")
    val parts = path.split('/')
    if (parts.size != 2 || parts.any { it.isEmpty() }) return null
    val invite =
        query
            .split('&')
            .firstOrNull { it.startsWith("invite=") }
            ?.removePrefix("invite=")
            ?.takeIf { it.isNotEmpty() }
            ?.let(::decodeSegment)
    return GroupRoute(
        relayUrl = "wss://" + decodeSegment(parts[0]),
        groupId = decodeSegment(parts[1]),
        inviteCode = invite,
    )
}

/** RFC 3986 percent-encoding of everything outside the unreserved set (UTF-8). */
private fun encodeSegment(s: String): String = buildString {
    for (byte in s.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-._~") {
            append(ch)
        } else {
            append('%')
            append(HEX[c shr 4])
            append(HEX[c and 0xF])
        }
    }
}

private fun decodeSegment(s: String): String {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        val hi = if (ch == '%' && i + 2 < s.length) s[i + 1].digitToIntOrNull(16) else null
        val lo = if (hi != null) s[i + 2].digitToIntOrNull(16) else null
        if (hi != null && lo != null) {
            bytes.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            // Unencoded ASCII passes through; anything else round-trips via UTF-8.
            for (b in ch.toString().encodeToByteArray()) bytes.add(b)
            i += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}
