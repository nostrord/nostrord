package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.mock.MockGroup
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface GroupInfoModalProps : Props {
    var group: MockGroup
    var onClose: () -> Unit
}

/**
 * Group info modal — layout-first React port of the Compose GroupInfoModal: cover +
 * overlapping group icon, name, public/open status badges, ABOUT, per-group NOTIFICATIONS
 * level, and the GROUP ID with a copy button. Mock data; copy/level changes are stubbed.
 */
val GroupInfoModal =
    FC<GroupInfoModalProps> { props ->
        val group = props.group
        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card info-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("info-cover")
                    button {
                        className = ClassName("info-cover-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                    div {
                        className = ClassName("avatar-tile info-cover-icon avatar-fallback")
                        +group.name.take(1).uppercase()
                    }
                }

                div {
                    className = ClassName("info-content")
                    div {
                        className = ClassName("info-name")
                        +group.name
                    }
                    div {
                        className = ClassName("info-badges")
                        span {
                            className = ClassName("info-badge success")
                            +"🌐 Public"
                        }
                        span {
                            className = ClassName("info-badge primary")
                            +"Open"
                        }
                    }

                    if (!group.about.isNullOrBlank()) {
                        div {
                            className = ClassName("settings-section-head")
                            +"ABOUT"
                        }
                        div {
                            className = ClassName("info-about")
                            +group.about
                        }
                    }

                    div {
                        className = ClassName("settings-section-head")
                        +"NOTIFICATIONS"
                    }
                    infoRadio("All messages", "Notify for every message in this group.", selected = true)
                    infoRadio("Mentions & replies only", "Notify on replies, @mentions, and reactions to your messages.", selected = false)
                    infoRadio("Muted", "Silence everything, including replies, mentions and reactions.", selected = false)

                    div {
                        className = ClassName("settings-section-head")
                        +"GROUP ID"
                    }
                    div {
                        className = ClassName("info-id-row")
                        span {
                            className = ClassName("info-id")
                            +group.id
                        }
                        button {
                            className = ClassName("info-copy")
                            +"⧉"
                        }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.infoRadio(label: String, description: String, selected: Boolean) {
    div {
        className = ClassName("settings-radio-row")
        div {
            className = ClassName(if (selected) "settings-radio on" else "settings-radio")
            div { className = ClassName("settings-radio-dot") }
        }
        div {
            className = ClassName("settings-toggle-text")
            div {
                className = ClassName("settings-toggle-label")
                +label
            }
            div {
                className = ClassName("settings-toggle-desc")
                +description
            }
        }
    }
}
