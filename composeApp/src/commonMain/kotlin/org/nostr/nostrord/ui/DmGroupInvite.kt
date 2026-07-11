package org.nostr.nostrord.ui

import org.nostr.nostrord.nostr.Nip19

/**
 * A group reference carried by a DM, split out of the text so the DM screens can render
 * the prototype invite card (eyebrow + name + about + "View group" button) instead of the
 * generic inline preview. [remainingText] is the message body without the naddr line.
 */
data class DmGroupInvite(
    val groupId: String,
    val relayUrl: String?,
    val remainingText: String,
)

private val NADDR_LINE = Regex("^(?:nostr:)?(naddr1[0-9a-z]+)$", RegexOption.IGNORE_CASE)

/**
 * First kind:39000 naddr standing alone on its own line of [content], or null. Matches the
 * shape `sendGroupAddedDm` produces ("You've been added ...\nnostr:naddr1...") and any
 * other client that shares a group link as its own line.
 */
fun extractDmGroupInvite(content: String): DmGroupInvite? {
    val lines = content.lines()
    for ((index, line) in lines.withIndex()) {
        val bech = NADDR_LINE.matchEntire(line.trim())?.groupValues?.get(1) ?: continue
        val entity = try {
            Nip19.decode(bech)
        } catch (_: Exception) {
            null
        } as? Nip19.Entity.Naddr ?: continue
        if (entity.kind != 39000) continue
        val remaining = lines
            .filterIndexed { i, _ -> i != index }
            .joinToString("\n")
            .trim()
        return DmGroupInvite(
            groupId = entity.identifier,
            relayUrl = entity.relays.firstOrNull(),
            remainingText = remaining,
        )
    }
    return null
}
