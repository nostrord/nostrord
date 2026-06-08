package org.nostr.nostrord.web.modals

import org.nostr.nostrord.utils.parseGroupJoinInput
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName

external interface JoinGroupModalProps : Props {
    var onClose: () -> Unit
    var onJoin: (relayUrl: String, groupId: String, inviteCode: String?) -> Unit
}

/**
 * Join-group modal — mirrors the Compose flow (App.kt onJoin): paste an invite link or a NIP-29
 * group address, parse it, then open the group. Joining is the user's decision inside the group
 * screen (its Join button), not the modal's; only an explicit invite code auto-joins. The
 * navigate + switch-relay + optional-join wiring lives in [onJoin] (AppShell).
 */
val JoinGroupModal =
    FC<JoinGroupModalProps> { props ->
        val (link, setLink) = useState { "" }
        val (error, setError) = useState<String?> { null }

        fun submit() {
            val parsed = parseGroupJoinInput(link)
            if (parsed == null) {
                setError("Invalid input. Use a wss://relay'groupId address or a nostrord invite link.")
                return
            }
            props.onJoin(parsed.relayUrl, parsed.groupId, parsed.inviteCode)
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
                    className = ClassName("modal-title")
                    +"Join Group"
                }
                div {
                    className = ClassName("modal-subtitle tight")
                    +"Paste a wss://relay'id, a naddr, or a nostrord invite link."
                }

                div {
                    className = ClassName("field-label")
                    +"Group address, naddr, or invite link"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "wss://relay.com'groupId"
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
                        onClick = { submit() }
                        +"Open"
                    }
                }
            }
        }
    }
