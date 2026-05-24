package org.nostr.nostrord.web.modals

import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface GroupInfoModalProps : Props {
    var group: GroupMetadata
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
        val groupName = group.name?.takeIf { it.isNotBlank() } ?: "Group"
        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card info-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("info-cover")
                    if (!group.picture.isNullOrBlank()) {
                        img {
                            className = ClassName("cover-img")
                            src = group.picture ?: ""
                            alt = ""
                        }
                    }
                    button {
                        className = ClassName("info-cover-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                    WebAvatar {
                        url = group.picture
                        seed = group.id
                        name = groupName
                        cls = "info-cover-icon"
                    }
                }

                div {
                    className = ClassName("info-content")
                    div {
                        className = ClassName("info-name")
                        +groupName
                    }
                    div {
                        className = ClassName("info-badges")
                        span {
                            className = ClassName(if (group.isPublic) "info-badge success" else "info-badge")
                            if (group.isPublic) {
                                icon(Ic.Public)
                                +"Public"
                            } else {
                                icon(Ic.Lock)
                                +"Private"
                            }
                        }
                        span {
                            className = ClassName(if (group.isOpen) "info-badge primary" else "info-badge")
                            +(if (group.isOpen) "Open" else "Closed")
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
                            icon(Ic.ContentCopy)
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
