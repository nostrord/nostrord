package org.nostr.nostrord.ui

import androidx.compose.runtime.Immutable

/**
 * An unsent composer draft for one group: the text plus the resolved @user / %group
 * mention maps. The mention-map values are platform-encoded strings (the web stores the
 * `nostr:` ref; native stores an encoded GroupInfo), so this type stays platform-agnostic.
 */
@Immutable
data class MessageDraft(
    val text: String = "",
    val mentions: Map<String, String> = emptyMap(),
    val groupMentions: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = text.isBlank() && mentions.isEmpty() && groupMentions.isEmpty()
}

/**
 * Per-group composer drafts kept in memory for the session, so switching groups and
 * coming back preserves what you were typing.
 *
 * Writes are plain map mutations (no StateFlow, no recomposition / re-render), so callers
 * can persist on every keystroke for free — this never triggers a chat re-render and so
 * does not reintroduce the composer input lag the draft state was split out to fix.
 */
class MessageDraftStore {
    private val drafts = mutableMapOf<String, MessageDraft>()

    fun get(groupId: String): MessageDraft = drafts[groupId] ?: MessageDraft()

    fun setText(groupId: String, text: String) = update(groupId) { it.copy(text = text) }

    fun setMentions(groupId: String, mentions: Map<String, String>) = update(groupId) { it.copy(mentions = mentions) }

    fun setGroupMentions(groupId: String, groupMentions: Map<String, String>) = update(groupId) { it.copy(groupMentions = groupMentions) }

    fun clear(groupId: String) {
        drafts.remove(groupId)
    }

    private inline fun update(groupId: String, transform: (MessageDraft) -> MessageDraft) {
        val updated = transform(get(groupId))
        if (updated.isEmpty) drafts.remove(groupId) else drafts[groupId] = updated
    }
}
