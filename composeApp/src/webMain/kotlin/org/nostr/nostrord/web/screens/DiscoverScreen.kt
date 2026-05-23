package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.GroupRow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

/** Known public NIP-29 group relays offered as quick-connect chips. */
private val SUGGESTED_RELAYS =
    listOf(
        "wss://groups.0xchat.com",
        "wss://relay.groups.nip29.com",
        "wss://groups.fiatjaf.com",
    )

/**
 * Discover view — pick a NIP-29 relay and join its groups. The content pane shown by
 * [org.nostr.nostrord.web.AppShell] when no group is selected. Joined groups live in the
 * sidebar; this lists what's available to join on the connected relay.
 */
val DiscoverScreen =
    FC<Props> {
        val currentRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val groupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val joinedByRelay = useStateFlow(AppModule.nostrRepository.joinedGroupsByRelay)
        val loadingRelays = useStateFlow(AppModule.nostrRepository.loadingRelays)
        val kind10009Relays = useStateFlow(AppModule.nostrRepository.kind10009Relays)
        val (relayInput, setRelayInput) = useState { "" }
        val (showCreate, setShowCreate) = useState { false }
        val (newName, setNewName) = useState { "" }
        val (newAbout, setNewAbout) = useState { "" }
        val (isPrivate, setIsPrivate) = useState { false }
        val (isClosed, setIsClosed) = useState { false }
        val (creating, setCreating) = useState { false }
        val (createError, setCreateError) = useState<String?> { null }

        useEffect(currentRelay) {
            if (currentRelay.isNotBlank()) {
                AppModule.nostrRepository.requestFullGroupListForRelay(currentRelay)
            }
        }

        val joinedIds = joinedByRelay.values.flatten().toSet()
        val available =
            groupsByRelay.values
                .flatten()
                .distinctBy { it.id }
                .filter { it.id !in joinedIds }
                .sortedBy { (it.name ?: it.id).lowercase() }

        fun connectRelay(url: String) {
            val normalized = url.trim()
            if (normalized.isBlank()) return
            launchApp {
                AppModule.nostrRepository.addRelay(normalized)
                AppModule.nostrRepository.switchRelay(normalized)
            }
        }

        fun submitCreate() {
            val groupName = newName.trim()
            if (groupName.isBlank() || currentRelay.isBlank()) return
            setCreating(true)
            setCreateError(null)
            launchApp {
                val result =
                    AppModule.nostrRepository.createGroup(
                        name = groupName,
                        about = newAbout.trim().ifBlank { null },
                        relayUrl = currentRelay,
                        isPrivate = isPrivate,
                        isClosed = isClosed,
                    )
                setCreating(false)
                if (result is Result.Error) {
                    setCreateError("Failed to create group.")
                } else {
                    setShowCreate(false)
                    setNewName("")
                    setNewAbout("")
                    setIsPrivate(false)
                    setIsClosed(false)
                }
            }
        }

        div {
            className = ClassName("app-shell")
            h1 { +"Discover groups" }

            div {
                className = ClassName("relay-bar")
                if (kind10009Relays.isEmpty()) {
                    p {
                        className = ClassName("muted")
                        +"No relays yet — connect one below."
                    }
                } else {
                    div {
                        className = ClassName("relay-list")
                        kind10009Relays.forEach { relay ->
                            div {
                                key = relay
                                className = ClassName(if (relay == currentRelay) "relay-item relay-active" else "relay-item")
                                span {
                                    className = ClassName("relay-item-name")
                                    onClick = { launchApp { AppModule.nostrRepository.switchRelay(relay) } }
                                    +relay.removePrefix("wss://")
                                }
                                button {
                                    className = ClassName("relay-remove")
                                    onClick = { launchApp { AppModule.nostrRepository.removeRelay(relay) } }
                                    +"×"
                                }
                            }
                        }
                    }
                }
                div {
                    className = ClassName("relay-input-row")
                    input {
                        placeholder = "wss://groups…"
                        value = relayInput
                        onChange = { event -> setRelayInput(event.currentTarget.value) }
                    }
                    button {
                        disabled = relayInput.isBlank()
                        onClick = {
                            connectRelay(relayInput)
                            setRelayInput("")
                        }
                        +"Connect"
                    }
                }
                div {
                    className = ClassName("relay-suggestions")
                    SUGGESTED_RELAYS.forEach { relay ->
                        button {
                            key = relay
                            className = ClassName("chip")
                            onClick = { connectRelay(relay) }
                            +relay.removePrefix("wss://")
                        }
                    }
                }
            }

            if (currentRelay.isNotBlank()) {
                if (!showCreate) {
                    button {
                        className = ClassName("chip")
                        onClick = { setShowCreate(true) }
                        +"＋ Create group"
                    }
                } else {
                    div {
                        className = ClassName("create-form")
                        input {
                            placeholder = "Group name"
                            value = newName
                            disabled = creating
                            onChange = { event -> setNewName(event.currentTarget.value) }
                        }
                        input {
                            placeholder = "About (optional)"
                            value = newAbout
                            disabled = creating
                            onChange = { event -> setNewAbout(event.currentTarget.value) }
                        }
                        label {
                            className = ClassName("toggle-row")
                            input {
                                type = InputType.checkbox
                                checked = isPrivate
                                onChange = { event -> setIsPrivate(event.currentTarget.checked) }
                            }
                            span { +"Private" }
                        }
                        label {
                            className = ClassName("toggle-row")
                            input {
                                type = InputType.checkbox
                                checked = isClosed
                                onChange = { event -> setIsClosed(event.currentTarget.checked) }
                            }
                            span { +"Closed" }
                        }
                        createError?.let { message ->
                            p {
                                className = ClassName("error")
                                +message
                            }
                        }
                        div {
                            className = ClassName("row-actions")
                            button {
                                disabled = creating || newName.isBlank()
                                onClick = { submitCreate() }
                                +(if (creating) "Creating…" else "Create")
                            }
                            button {
                                className = ClassName("secondary")
                                disabled = creating
                                onClick = { setShowCreate(false) }
                                +"Cancel"
                            }
                        }
                    }
                }
            }

            when {
                currentRelay.isBlank() ->
                    p {
                        className = ClassName("muted")
                        +"Connect to a NIP-29 relay above to see its groups."
                    }
                available.isEmpty() && currentRelay in loadingRelays ->
                    p {
                        className = ClassName("muted")
                        +"Loading groups…"
                    }
                available.isEmpty() ->
                    p {
                        className = ClassName("muted")
                        +"No groups found on this relay yet."
                    }
                else ->
                    div {
                        className = ClassName("group-list")
                        available.forEach { group ->
                            GroupRow {
                                key = group.id
                                this.group = group
                                actionLabel = "Join"
                                onActivate = { launchApp { AppModule.nostrRepository.joinGroup(group.id) } }
                            }
                        }
                    }
            }
        }
    }
