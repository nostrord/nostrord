package org.nostr.nostrord.ui.util

import org.nostr.nostrord.ui.navigation.platformAppOrigin

private fun relayHost(relayUrl: String): String =
    relayUrl
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

/**
 * Builds a shareable relay link.
 * On web uses the current origin, on native uses https://nostrord.com/open/.
 */
fun buildShareRelayLink(relayUrl: String): String {
    val host = relayHost(relayUrl)
    val origin = platformAppOrigin()
    return if (origin != null) "$origin/?relay=$host"
    else "https://nostrord.com/open/?relay=$host"
}

/**
 * Builds a shareable group link.
 * On web uses the current origin, on native uses https://nostrord.com/open/.
 */
fun buildShareGroupLink(relayUrl: String, groupId: String): String {
    val host = relayHost(relayUrl)
    val origin = platformAppOrigin()
    return if (origin != null) "$origin/?relay=$host&group=$groupId"
    else "https://nostrord.com/open/?relay=$host&group=$groupId"
}
