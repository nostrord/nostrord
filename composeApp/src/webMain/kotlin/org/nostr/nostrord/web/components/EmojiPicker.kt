package org.nostr.nostrord.web.components

import org.nostr.nostrord.ui.components.emoji.EmojiData
import org.nostr.nostrord.ui.components.emoji.EmojiGroup
import org.nostr.nostrord.web.bridge.useStateFlow
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

external interface EmojiPickerProps : Props {
    /** Called with the chosen emoji. */
    var onPick: (String) -> Unit
}

private fun tabIcon(group: EmojiGroup): Ic =
    when (group) {
        EmojiGroup.RECENT -> Ic.Schedule
        EmojiGroup.SMILEYS -> Ic.EmojiEmotions
        EmojiGroup.PEOPLE -> Ic.EmojiPeople
        EmojiGroup.NATURE -> Ic.EmojiNature
        EmojiGroup.FOOD -> Ic.EmojiFood
        EmojiGroup.TRAVEL -> Ic.EmojiTravel
        EmojiGroup.ACTIVITIES -> Ic.EmojiEvents
        EmojiGroup.OBJECTS -> Ic.EmojiObjects
        EmojiGroup.SYMBOLS -> Ic.EmojiSymbols
        EmojiGroup.FLAGS -> Ic.Flag
    }

/**
 * Emoji picker popover — mirrors the native EmojiPicker: search, category tabs, a recents row,
 * and a scrollable grid grouped by category. Uses the shared [EmojiData] / [EmojiGroup] so the
 * set matches native exactly. The caller positions it and handles dismissal (outside click).
 */
val EmojiPicker =
    FC<EmojiPickerProps> { props ->
        val (query, setQuery) = useState { "" }
        val recents = useStateFlow(RecentEmojiStore.recents)

        fun pick(emoji: String) {
            RecentEmojiStore.record(emoji)
            props.onPick(emoji)
        }

        div {
            className = ClassName("emoji-picker")
            onClick = { it.stopPropagation() }

            div {
                className = ClassName("emoji-search")
                icon(Ic.Search, "ico emoji-search-ico")
                input {
                    className = ClassName("emoji-search-input")
                    placeholder = "Search emoji"
                    value = query
                    autoFocus = true
                    onChange = { event -> setQuery(event.currentTarget.value) }
                }
            }

            if (query.isBlank()) {
                div {
                    className = ClassName("emoji-tabs")
                    EmojiGroup.entries.forEach { group ->
                        if (group == EmojiGroup.RECENT && recents.isEmpty()) return@forEach
                        button {
                            className = ClassName("emoji-tab")
                            title = group.label
                            onClick = {
                                document.getElementById(ElementId("emoji-sec-${group.name}"))?.scrollIntoView()
                            }
                            icon(tabIcon(group))
                        }
                    }
                }
            }

            div {
                className = ClassName("emoji-grid-scroll")
                if (query.isNotBlank()) {
                    val q = query.lowercase()
                    val matches = EmojiData.byGroup.values.flatten().filter { it.name.lowercase().contains(q) }.map { it.emoji }
                    if (matches.isEmpty()) {
                        div {
                            className = ClassName("emoji-empty")
                            +"No emoji found"
                        }
                    } else {
                        emojiSection(null, "Results", matches) { pick(it) }
                    }
                } else {
                    if (recents.isNotEmpty()) {
                        emojiSection("emoji-sec-RECENT", "Recent", recents) { pick(it) }
                    }
                    EmojiGroup.entries.forEach { group ->
                        if (group == EmojiGroup.RECENT) return@forEach
                        val list = EmojiData.byGroup[group] ?: return@forEach
                        emojiSection("emoji-sec-${group.name}", group.label, list.map { it.emoji }) { pick(it) }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.emojiSection(domId: String?, title: String, emojis: List<String>, onPick: (String) -> Unit) {
    div {
        if (domId != null) id = ElementId(domId)
        className = ClassName("emoji-section")
        div {
            className = ClassName("emoji-section-title")
            +title
        }
        div {
            className = ClassName("emoji-grid")
            emojis.forEach { emoji ->
                button {
                    key = emoji
                    className = ClassName("emoji-cell")
                    onClick = { onPick(emoji) }
                    +emoji
                }
            }
        }
    }
}
