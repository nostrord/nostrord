package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
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
        val kind10009 = useStateFlow(AppModule.nostrRepository.kind10009Relays)
        val groupTagRelays = useStateFlow(AppModule.nostrRepository.groupTagRelays)
        val connected = kind10009 + groupTagRelays
        val onAdded: (String) -> Unit = props.onAdded ?: { props.onClose() }

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
                        +"✕"
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
                            div {
                                key = relay.url
                                className = ClassName("relay-card")
                                div {
                                    className = ClassName("avatar-tile relay-card-icon avatar-fallback")
                                    +relay.name.take(1).uppercase()
                                }
                                div {
                                    className = ClassName("relay-card-info")
                                    div {
                                        className = ClassName("relay-card-name")
                                        +relay.name
                                    }
                                    div {
                                        className = ClassName("relay-card-url")
                                        +relay.url
                                    }
                                    div {
                                        className = ClassName("relay-card-desc")
                                        +relay.description
                                    }
                                }
                                button {
                                    className = ClassName(if (isConnected) "relay-add-btn added" else "relay-add-btn")
                                    disabled = isConnected
                                    onClick = { onAdded(relay.url) }
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
