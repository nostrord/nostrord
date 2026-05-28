package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
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

external interface AddMemberModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

/**
 * Add-member modal — real port of the Compose AddMemberModal: an npub / hex pubkey field
 * that calls `addUser(groupId, pubkey)`. Closes on success. Validation lives in commonMain
 * (Nip19.parsePubkeyInput) so this and the native modal stay in sync.
 */
val AddMemberModal =
    FC<AddMemberModalProps> { props ->
        val (value, setValue) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        fun submit() {
            val pubkey =
                when (val parsed = Nip19.parsePubkeyInput(value)) {
                    is Nip19.PubkeyParse.Ok -> parsed.hex
                    Nip19.PubkeyParse.Empty -> {
                        setError("Enter an npub or hex pubkey.")
                        return
                    }
                    Nip19.PubkeyParse.IsPrivateKey -> {
                        setError("That looks like a private key (nsec). Use the user's npub instead.")
                        return
                    }
                    Nip19.PubkeyParse.NotAPubkey -> {
                        setError("That's not a user identity. Paste an npub, nprofile, or hex pubkey.")
                        return
                    }
                    Nip19.PubkeyParse.Malformed -> {
                        setError("Invalid npub or hex pubkey.")
                        return
                    }
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
                            +"Add Member"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
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
