package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName

external interface JoinWithCodeModalProps : Props {
    var initialCode: String?
    var onJoin: (String) -> Unit
    var onClose: () -> Unit
}

/**
 * Join-with-invite-code modal — web port of the Compose InviteCodeJoinModal
 * (GroupHeader.kt). A single invite-code field; Join hands the trimmed code to
 * onJoin (the closed-group join flow). Replaces the old window.prompt so the web
 * matches the native modal.
 */
val JoinWithCodeModal =
    FC<JoinWithCodeModalProps> { props ->
        val (value, setValue) = useState { props.initialCode.orEmpty() }
        val code = value.trim()

        fun submit() {
            if (code.isBlank()) return
            props.onJoin(code)
            props.onClose()
        }

        useEscClose { props.onClose() }

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
                            +"Join with Invite Code"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                input {
                    className = ClassName("modal-input")
                    placeholder = "Enter invite code"
                    autoFocus = true
                    this.value = value
                    onChange = { event -> setValue(event.currentTarget.value) }
                    onKeyDown = { event -> if (event.key == "Enter") submit() }
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
                        disabled = code.isBlank()
                        onClick = { submit() }
                        +"Join"
                    }
                }
            }
        }
    }
