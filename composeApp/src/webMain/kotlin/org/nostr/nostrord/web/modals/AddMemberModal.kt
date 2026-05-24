package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useState
import web.cssom.ClassName

external interface AddMemberModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

private const val HEX_CHARS = "0123456789abcdefABCDEF"

/** Accepts an npub or 64-char hex pubkey; returns the hex pubkey, or null if invalid. */
private fun parsePubkeyHex(input: String): String? {
    val s = input.trim()
    return when {
        s.startsWith("npub1") -> (Nip19.decode(s) as? Nip19.Entity.Npub)?.pubkey
        s.length == 64 && s.all { it in HEX_CHARS } -> s.lowercase()
        else -> null
    }
}

/**
 * Add-member modal — real port of the Compose AddMemberModal: an npub / hex pubkey field
 * that calls `addUser(groupId, pubkey)`. Closes on success.
 */
val AddMemberModal =
    FC<AddMemberModalProps> { props ->
        val (value, setValue) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        fun submit() {
            val pubkey = parsePubkeyHex(value)
            if (pubkey == null) {
                setError("Invalid npub or hex pubkey")
                return
            }
            setError(null)
            setBusy(true)
            launchApp {
                val result = AppModule.nostrRepository.addUser(props.groupId, pubkey)
                setBusy(false)
                when (result) {
                    is Result.Success -> props.onClose()
                    is Result.Error -> setError(result.error.message.ifBlank { "Failed to add member." })
                }
            }
        }

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
                    onChange = { event ->
                        setValue(event.currentTarget.value)
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
                        disabled = value.isBlank() || busy
                        onClick = { submit() }
                        +(if (busy) "Adding…" else "Add Member")
                    }
                }
            }
        }
    }
