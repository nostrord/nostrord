package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

external interface AddRelayModalProps : Props {
    var onClose: () -> Unit

    /** Called with the relay URL to add (Add / Add Relay). Falls back to just closing. */
    var onAdded: ((String) -> Unit)?

    /** Tab to open on mount: 0 = Suggested, 1 = Custom URL. */
    var initialTab: Int?
}

private data class SuggestedRelay(val url: String, val name: String, val description: String)

private val suggestedRelays =
    listOf(
        SuggestedRelay("wss://groups.fiatjaf.com", "groups.fiatjaf.com", "A test relay for NIP-29 groups"),
        SuggestedRelay("wss://groups.0xchat.com", "0xchat Groups relay", "NIP-29 relay powering 0xchat group messaging"),
        SuggestedRelay("wss://relay.groups.nip29.com", "relay.groups.nip29.com", "Public NIP-29 groups relay"),
        SuggestedRelay("wss://groups.hzrd149.com", "groups.hzrd149.com", "NIP-29 group relay"),
        SuggestedRelay("wss://pyramid.fiatjaf.com", "pyramid.fiatjaf.com", "NIP-29 relay"),
        SuggestedRelay("wss://chat.wisp.talk", "chat.wisp.talk", "NIP-29 relay"),
    )

/**
 * Add-relay modal — layout-first React port of the Compose [AddRelayModal]: a Suggested
 * tab (list of popular NIP-29 relays with Add buttons) and a Custom URL tab. Already-
 * connected relays show "Added". Adding is stubbed (closes); wiring comes after layout.
 */
val AddRelayModal =
    FC<AddRelayModalProps> { props ->
        val (tab, setTab) = useState { props.initialTab ?: 0 }
        val (customUrl, setCustomUrl) = useState { "" }
        val repo = AppModule.nostrRepository
        val kind10009 = useStateFlow(repo.kind10009Relays)
        val groupTagRelays = useStateFlow(repo.groupTagRelays)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val connected = kind10009 + groupTagRelays
        val onAdded: (String) -> Unit = props.onAdded ?: { props.onClose() }

        useEscClose { props.onClose() }

        // Pre-fetch NIP-11 for each suggested relay so the cards show real icons
        // + names instead of just the hardcoded letter fallback. Mirrors native's
        // `LaunchedEffect(Unit) { AppModule.relayMetadataManager.fetchAll(...) }`
        // — RelayMetadataManager dedups in-flight + already-succeeded URLs.
        useEffectOnce {
            suggestedRelays.forEach { repo.fetchRelayMetadata(it.url) }
        }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card relay-modal")
                onClick = { it.stopPropagation() }

                // Header
                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Add a Relay"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"Pick a popular relay or enter a custom URL to connect."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                // Tabs
                div {
                    className = ClassName("modal-tabs")
                    button {
                        className = ClassName(if (tab == 0) "modal-tab selected" else "modal-tab")
                        onClick = { setTab(0) }
                        +"Suggested"
                    }
                    button {
                        className = ClassName(if (tab == 1) "modal-tab selected" else "modal-tab")
                        onClick = { setTab(1) }
                        +"Custom URL"
                    }
                }

                if (tab == 0) {
                    div {
                        className = ClassName("relay-list")
                        suggestedRelays.forEach { relay ->
                            val isConnected = relay.url in connected
                            // Prefer the live NIP-11 metadata once fetched; fall back to
                            // the hardcoded suggestion so the card never reads empty.
                            val meta = relayMetadata[relay.url]
                            val name = meta?.name?.takeIf { it.isNotBlank() } ?: relay.name
                            val desc = meta?.description?.takeIf { it.isNotBlank() } ?: relay.description
                            div {
                                key = relay.url
                                className = ClassName(if (isConnected) "relay-card added" else "relay-card")
                                // Whole card is clickable when not added (matches native:
                                // both the row and the Add button trigger onAdd).
                                if (!isConnected) onClick = { onAdded(relay.url) }
                                WebAvatar {
                                    url = meta?.icon
                                    seed = relay.url
                                    kind = AvatarKind.RELAY
                                    this.name = name
                                    cls = "relay-card-icon"
                                }
                                div {
                                    className = ClassName("relay-card-info")
                                    div {
                                        className = ClassName("relay-card-name")
                                        +name
                                    }
                                    div {
                                        className = ClassName("relay-card-url")
                                        +relay.url
                                    }
                                    div {
                                        className = ClassName("relay-card-desc")
                                        +desc
                                    }
                                }
                                button {
                                    className = ClassName(if (isConnected) "relay-add-btn added" else "relay-add-btn")
                                    disabled = isConnected
                                    onClick = {
                                        it.stopPropagation()
                                        onAdded(relay.url)
                                    }
                                    +(if (isConnected) "Added" else "Add")
                                }
                            }
                        }
                    }
                } else {
                    div {
                        className = ClassName("relay-custom")
                        div {
                            className = ClassName("access-section-title")
                            +"RELAY URL"
                        }
                        input {
                            className = ClassName("modal-input")
                            placeholder = "relay.example.com"
                            value = customUrl
                            onChange = { event -> setCustomUrl(event.currentTarget.value) }
                        }
                    }
                    div {
                        className = ClassName("modal-footer flush")
                        button {
                            className = ClassName("btn-text")
                            onClick = { props.onClose() }
                            +"Cancel"
                        }
                        button {
                            className = ClassName("btn-primary")
                            disabled = customUrl.isBlank()
                            onClick = { onAdded(customUrl.trim().toRelayUrl()) }
                            +"Add Relay"
                        }
                    }
                }
            }
        }
    }
