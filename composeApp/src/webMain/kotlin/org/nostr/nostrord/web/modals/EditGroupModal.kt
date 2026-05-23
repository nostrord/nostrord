package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.mock.MockGroup
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName

external interface EditGroupModalProps : Props {
    var group: MockGroup
    var onClose: () -> Unit
}

/**
 * Edit-group modal — layout-first React port of the Compose EditGroupModal: name,
 * description, image URL, the Private / Closed access toggles, and the read-only group ID
 * with copy. Prefilled from the group; Save is stubbed.
 */
val EditGroupModal =
    FC<EditGroupModalProps> { props ->
        val group = props.group
        val (isPrivate, setIsPrivate) = useState { false }
        val (isClosed, setIsClosed) = useState { false }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Edit Group"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                }

                div {
                    className = ClassName("field-label")
                    +"Group Name"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "#example"
                    defaultValue = group.name
                }

                div {
                    className = ClassName("field-label")
                    +"Description"
                }
                textarea {
                    className = ClassName("modal-textarea")
                    placeholder = "What is this group about?"
                    rows = 3
                    if (!group.about.isNullOrBlank()) defaultValue = group.about
                }

                div {
                    className = ClassName("field-label")
                    +"Group Image URL"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "https://example.com/image.jpg"
                }

                div {
                    className = ClassName("access-section-title")
                    +"ACCESS SETTINGS"
                }
                accessToggle("🔒", "Private", "Only members can read group messages", isPrivate) { setIsPrivate(!isPrivate) }
                accessToggle("🚫", "Closed", "Join requests are ignored (invite-only)", isClosed) { setIsClosed(!isClosed) }

                div {
                    className = ClassName("access-section-title")
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

                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { props.onClose() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        onClick = { props.onClose() }
                        +"Save Changes"
                    }
                }
            }
        }
    }
