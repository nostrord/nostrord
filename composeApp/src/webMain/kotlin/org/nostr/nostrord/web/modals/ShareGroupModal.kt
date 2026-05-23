package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.mock.MockGroup
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import web.cssom.ClassName

external interface ShareGroupModalProps : Props {
    var group: MockGroup
    var onClose: () -> Unit
}

/**
 * Share-group modal — layout-first React port of the Compose ShareGroupModal: a shareable
 * web Link and the Nostr Address (naddr), each with a Copy button. Copy is stubbed.
 */
val ShareGroupModal =
    FC<ShareGroupModalProps> { props ->
        val group = props.group
        val link = "https://nostrord.com/open/?relay=groups.0xchat.com&group=${group.id}"
        val naddr = "naddr1qq8x_${group.id}_w3z9k2v4h7t6y8u3w0e5r2t9p4a"

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
                            +"Share Group"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                }

                shareField("Link", link)
                shareField("Nostr Address (naddr)", naddr)
            }
        }
    }

private fun ChildrenBuilder.shareField(label: String, value: String) {
    div {
        className = ClassName("field-label")
        +label
    }
    div {
        className = ClassName("share-field")
        input {
            className = ClassName("modal-input flush")
            readOnly = true
            this.value = value
        }
        button {
            className = ClassName("btn-primary")
            +"Copy"
        }
    }
}
