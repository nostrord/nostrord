package org.nostr.nostrord.ui.screens.group

import org.nostr.nostrord.network.NostrGroupClient

/**
 * Bound the rendered timeline to the loaded chat-message window, shared by both chat lists.
 *
 * Join/leave and moderation events (kinds 9000/9001/9021/9022) arrive in full from a dedicated
 * subscription with no page limit, so the oldest one would otherwise become a false "top" far
 * above the oldest paginated kind:9 message. Pagination would then fill messages BELOW it
 * instead of prepending at the real top, which looks wrong and stalls the scroll (the first row
 * never changes). Hiding system events older than the oldest loaded message keeps the frontier
 * aligned with the chat history; they reveal again as pagination lowers that frontier. With no
 * messages loaded yet (a fresh group is only join/leave noise), everything is shown.
 *
 * This is a hide, not a delete: nothing is dropped from storage, and an older page paging in
 * lowers `oldestMessageTs` and re-reveals the events that belong above it.
 *
 * @param sorted messages already sorted ascending by createdAt.
 */
fun clampSystemEventsToLoadedWindow(
    sorted: List<NostrGroupClient.NostrMessage>,
): List<NostrGroupClient.NostrMessage> {
    val oldestMessageTs = sorted.filter { it.kind == 9 }.minOfOrNull { it.createdAt } ?: return sorted
    return sorted.filter { it.kind == 9 || it.createdAt >= oldestMessageTs }
}

/**
 * Subject-verb agreement for a merged system row: the action strings are written for a
 * single subject ("was removed", "is now admin") but a multi-user merge renders "N members"
 * as the subject.
 */
fun pluralizeSystemAction(action: String): String = action
    .replaceFirst("was ", "were ")
    .replaceFirst("is now", "are now")
