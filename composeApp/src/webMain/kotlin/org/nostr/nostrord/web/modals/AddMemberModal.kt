package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.screens.group.filterFriendCandidates
import org.nostr.nostrord.ui.screens.group.groupHostRelay
import org.nostr.nostrord.ui.screens.group.pubkeyUsesRelay
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.formHint
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface AddMemberModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

/**
 * Add-member modal — real port of the Compose AddMemberModal: a searchable friend picker
 * over the follows list plus an npub / hex pubkey field, both calling
 * `addUser(groupId, pubkey, notifyViaDm)`. Closes on success. Validation and the
 * friend-candidate derivation live in commonMain so this and the native modal stay in sync.
 *
 * The "Send a DM invite" checkbox defaults per target: someone whose public kind:10009
 * pins a group on this relay already gets the in-app add notification there, so the DM
 * defaults off for them and on for everyone else. Toggling it is an explicit choice
 * that applies to whoever is added next.
 */
val AddMemberModal =
    FC<AddMemberModalProps> { props ->
        val (value, setValue) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        // Same keyed GroupViewModel instance as the hosting ChatScreen: its `friends` flow
        // carries the onStart metadata kick and the ordering the Compose picker gets.
        val vm = useViewModel(props.groupId) { GroupViewModel(AppModule.nostrRepository, props.groupId) }
        val friends = useStateFlow(vm.friends)
        val userGroupLists = useStateFlow(AppModule.nostrRepository.userGroupLists)
        val groupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val currentRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val (notifyViaDm, setNotifyViaDm) = useState { true }
        val (notifyTouched, setNotifyTouched) = useState { false }
        val (prefetchedKey, setPrefetchedKey) = useState<String?> { null }
        // Submit only unlocks for a key that actually parses (npub/nprofile/hex).
        val parsedHex = (Nip19.parsePubkeyInput(value) as? Nip19.PubkeyParse.Ok)?.hex
        val isValidKey = parsedHex != null
        val groupRelay = groupHostRelay(props.groupId, groupsByRelay, currentRelay)

        fun targetOnRelay(pubkey: String) = pubkeyUsesRelay(pubkey, groupRelay, userGroupLists)
        val typedOnRelay = parsedHex != null && targetOnRelay(parsedHex)

        // Fetch the typed key's kind:10009 so the on-relay check has data (friends' lists
        // are already fetched by the home discovery). Guarded per key, so the render-time
        // call fires once per typed identity.
        if (parsedHex != null && parsedHex != prefetchedKey) {
            setPrefetchedKey(parsedHex)
            launchApp { AppModule.nostrRepository.requestUserGroupList(parsedHex) }
        }
        // Smart default; an explicit toggle wins from then on.
        if (!notifyTouched && parsedHex != null && notifyViaDm != !typedOnRelay) {
            setNotifyViaDm(!typedOnRelay)
        }
        // Friend picker: follows filtered by the same input (until it turns into a
        // parseable key, at which point the button flow takes over).
        val shownFriends = if (isValidKey) emptyList() else filterFriendCandidates(friends, value)

        fun addPubkey(pubkey: String, notify: Boolean) {
            setError(null)
            setBusy(true)
            launchApp {
                val result = AppModule.nostrRepository.addUser(props.groupId, pubkey, notifyViaDm = notify)
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
            addPubkey(pubkey, notifyViaDm)
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
                                onClick = {
                                    // Untouched checkbox = per-target auto (the row's tag
                                    // shows why no DM goes out for on-relay friends).
                                    val notify = if (notifyTouched) notifyViaDm else !targetOnRelay(friend.pubkey)
                                    addPubkey(friend.pubkey, notify)
                                }
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
                                if (targetOnRelay(friend.pubkey)) {
                                    span {
                                        className = ClassName("mod-you")
                                        +"ON THIS RELAY"
                                    }
                                }
                            }
                        }
                    }
                }

                // DM courtesy toggle. The DM is the only signal that reaches a user whose
                // client does not connect to this relay.
                label {
                    className = ClassName("relay-check modal-dm-check")
                    input {
                        type = InputType.checkbox
                        checked = notifyViaDm
                        onChange = { event ->
                            setNotifyViaDm(event.currentTarget.checked)
                            setNotifyTouched(true)
                        }
                    }
                    +"Send a DM invite"
                }
                formHint(
                    if (typedOnRelay) {
                        "This user is already on this relay and gets the in-app notification."
                    } else {
                        "A DM is how someone outside this relay finds out."
                    },
                )

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
