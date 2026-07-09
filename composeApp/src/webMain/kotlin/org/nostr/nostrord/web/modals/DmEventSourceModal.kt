package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.pre
import web.cssom.ClassName

external interface DmEventSourceModalProps : Props {
    var json: String
    var relays: Array<String>
    var onCopy: () -> Unit
    var onClose: () -> Unit
}

/**
 * "View source" for a DM: the decrypted kind:14 rumor as pretty JSON plus the relays its
 * gift wrap was seen on this session. Mirrors the Compose DmEventSourceDialog.
 */
val DmEventSourceModal =
    FC<DmEventSourceModalProps> { props ->
        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-title")
                    +"Message source"
                }
                pre {
                    className = ClassName("dm-source-json")
                    +props.json
                }
                if (props.relays.isNotEmpty()) {
                    div {
                        className = ClassName("dm-source-relays-label")
                        +"Seen on"
                    }
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
                        className = ClassName("btn-text")
                        onClick = { props.onClose() }
                        +"Close"
                    }
                    button {
                        className = ClassName("btn-primary")
                        onClick = {
                            props.onCopy()
                            props.onClose()
                        }
                        +"Copy JSON"
                    }
                }
            }
        }
    }
