package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
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
}

/** Parse a nostrord.com/open or nostrord:// invite link into (relayUrl, groupId, code?). */
private fun parseInviteLink(input: String): Triple<String, String, String?>? {
    val trimmed = input.trim()
    val queryStart = trimmed.indexOf('?')
    if (queryStart < 0) return null
    val params =
        trimmed.substring(queryStart + 1).split("&").associate { param ->
            val idx = param.indexOf("=")
            if (idx >= 0) param.substring(0, idx) to param.substring(idx + 1) else param to ""
        }
    val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: return null
    val group = params["group"]?.takeIf { it.isNotBlank() } ?: return null
    return Triple(relay.toRelayUrl(), group, params["code"]?.takeIf { it.isNotBlank() })
}

/**
 * Join-group modal — real port of the Compose [JoinGroupModal]: paste an invite link, parse
 * it, switch to its relay and join. Closes on success, shows the parse/join error otherwise.
 */
val JoinGroupModal =
    FC<JoinGroupModalProps> { props ->
        val (link, setLink) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        fun submit() {
            val parsed = parseInviteLink(link)
            if (parsed == null) {
                setError("Invalid link. Use a nostrord.com/open/ or nostrord:// invite link.")
                return
            }
            val (relayUrl, groupId, code) = parsed
            setError(null)
            setBusy(true)
            launchApp {
                AppModule.nostrRepository.switchRelay(relayUrl)
                val result = AppModule.nostrRepository.joinGroup(groupId, code)
                setBusy(false)
                when (result) {
                    is Result.Success -> props.onClose()
                    is Result.Error -> setError(result.error.message.ifBlank { "Failed to join group." })
                }
            }
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
                        disabled = link.isBlank() || busy
                        onClick = { submit() }
                        +(if (busy) "Joining…" else "Join")
                    }
                }
            }
        }
    }
