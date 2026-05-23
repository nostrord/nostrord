package org.nostr.nostrord.web.modals

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName

external interface AddMemberModalProps : Props {
    var onClose: () -> Unit
}

/**
 * Add-member modal — layout-first React port of the Compose AddMemberModal: an npub / hex
 * pubkey field + Add Member. Adding is stubbed.
 */
val AddMemberModal =
    FC<AddMemberModalProps> { props ->
        val (value, setValue) = useState { "" }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Add Member"
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
                    +"npub"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "npub1... or hex pubkey"
                    this.value = value
                    onChange = { event -> setValue(event.currentTarget.value) }
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
                        disabled = value.isBlank()
                        onClick = { props.onClose() }
                        +"Add Member"
                    }
                }
            }
        }
    }
