package org.nostr.nostrord.ui.util

private const val OPEN_BASE = "https://nostrord.com/open/"

private fun relayHost(relayUrl: String): String =
    relayUrl
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

fun buildShareRelayLink(relayUrl: String): String {
    val host = relayHost(relayUrl)
    return "${OPEN_BASE}?relay=$host"
}

fun buildShareGroupLink(relayUrl: String, groupId: String): String {
    val host = relayHost(relayUrl)
    return "${OPEN_BASE}?relay=$host&group=$groupId"
}

fun buildShareMessageLink(relayUrl: String, groupId: String, messageId: String): String {
    val host = relayHost(relayUrl)
    return "${OPEN_BASE}?relay=$host&group=$groupId&e=$messageId"
}
