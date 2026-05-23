package org.nostr.nostrord.web.modals

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName

external interface JoinGroupModalProps : Props {
    var onClose: () -> Unit
}

/**
 * Join-group modal — layout-first React port of the Compose [JoinGroupModal] AlertDialog:
 * paste an invite link, validate, join. Validation/join is stubbed (closes on success).
 */
val JoinGroupModal =
    FC<JoinGroupModalProps> { props ->
        val (link, setLink) = useState { "" }
        val (error, setError) = useState<String?> { null }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-title")
                    +"Join Group"
                }
                div {
                    className = ClassName("modal-subtitle tight")
                    +"Paste an invite link to join a group."
                }

                div {
                    className = ClassName("field-label")
                    +"Invite Link"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "https://nostrord.com/open/?relay=...&group=..."
                    value = link
                    onChange = { event ->
                        setLink(event.currentTarget.value)
                        setError(null)
                    }
                }

                if (error != null) {
                    div {
                        className = ClassName("modal-error")
                        +error
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
                        disabled = link.isBlank()
                        onClick = {
                            if (!link.contains("?")) {
                                setError("Invalid link. Use a nostrord.com/open/ or nostrord:// invite link.")
                            } else {
                                props.onClose()
                            }
                        }
                        +"Join"
                    }
                }
            }
        }
    }
