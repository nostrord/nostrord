package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

external interface DmRelaysModalProps : Props {
    var relays: Array<String>
    var onClose: () -> Unit
}

/**
 * Where a peer's DMs route: their published kind:10050 relay list. Empty until the fetch lands,
 * or when the peer has published none (senders fall back to defaults). Mirrors the Compose dialog.
 */
val DmRelaysModal =
    FC<DmRelaysModalProps> { props ->
        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-title")
                    +"DM relays"
                }
                if (props.relays.isEmpty()) {
                    div {
                        className = ClassName("modal-subtitle tight")
                        +"No published DM relay list yet. Messages route to default relays."
                    }
                } else {
                    props.relays.forEach { relay ->
                        div {
                            className = ClassName("dm-source-relay")
                            +relay
                        }
                    }
                }
                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-primary")
                        onClick = { props.onClose() }
                        +"Close"
                    }
                }
            }
        }
    }
