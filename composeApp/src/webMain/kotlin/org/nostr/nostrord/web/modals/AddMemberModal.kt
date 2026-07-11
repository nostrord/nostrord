package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.screens.group.buildFriendCandidates
import org.nostr.nostrord.ui.screens.group.filterFriendCandidates
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface AddMemberModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

/**
 * Add-member modal — real port of the Compose AddMemberModal: a searchable friend picker
 * over the follows list plus an npub / hex pubkey field, both calling
 * `addUser(groupId, pubkey, notifyViaDm = true)`. Closes on success. Validation and the
 * friend-candidate derivation live in commonMain so this and the native modal stay in sync.
 */
val AddMemberModal =
    FC<AddMemberModalProps> { props ->
        val (value, setValue) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        val following = useStateFlow(AppModule.nostrRepository.following)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        // Submit only unlocks for a key that actually parses (npub/nprofile/hex).
        val isValidKey = Nip19.parsePubkeyInput(value) is Nip19.PubkeyParse.Ok
        // Friend picker: follows filtered by the same input (until it turns into a
        // parseable key, at which point the button flow takes over).
        val shownFriends =
            if (isValidKey) emptyList() else filterFriendCandidates(buildFriendCandidates(following, userMetadata), value)

        fun addPubkey(pubkey: String) {
            setError(null)
            setBusy(true)
            launchApp {
                val result = AppModule.nostrRepository.addUser(props.groupId, pubkey, notifyViaDm = true)
                setBusy(false)
                when (result) {
                    is Result.Success -> props.onClose()
                    is Result.Error -> setError(result.error.message.ifBlank { "Failed to add member." })
                }
            }
        }

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
            addPubkey(pubkey)
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
                        className = ClassName("modal-header-lead")
                        span {
                            className = ClassName("modal-title-icon")
                            icon(Ic.PersonAdd)
                        }
                        div {
                            className = ClassName("modal-header-text")
                            div {
                                className = ClassName("modal-title")
                                +"Add Member"
                            }
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("modal-subtitle tight")
                    +"Pick a friend below, or enter the user's npub or hex public key."
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "Search friends, or npub1... / hex pubkey"
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

                if (shownFriends.isNotEmpty()) {
                    div {
                        className = ClassName("modal-friend-list")
                        shownFriends.forEach { friend ->
                            button {
                                key = friend.pubkey
                                className = ClassName("member-row-btn")
                                disabled = busy
                                onClick = { addPubkey(friend.pubkey) }
                                WebAvatar {
                                    url = friend.picture
                                    seed = friend.pubkey
                                    name = friend.name ?: friend.pubkey
                                    cls = "mod-avatar"
                                }
                                span {
                                    className = ClassName("mod-name")
                                    +(friend.name ?: (friend.pubkey.take(8) + "…"))
                                }
                            }
                        }
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
                        disabled = !isValidKey || busy
                        onClick = { submit() }
                        +(if (busy) "Adding…" else "Add Member")
                    }
                }
            }
        }
    }
