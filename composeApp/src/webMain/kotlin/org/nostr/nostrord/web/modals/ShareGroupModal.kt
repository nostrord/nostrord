package org.nostr.nostrord.web.modals

import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import web.cssom.ClassName

external interface ShareGroupModalProps : Props {
    var group: GroupMetadata
    var onClose: () -> Unit
}

/**
 * Share-group modal — real port of the Compose ShareGroupModal: a shareable web Link
 * (built from the active relay + group id) and the naddr, each with a working Copy button.
 */
val ShareGroupModal =
    FC<ShareGroupModalProps> { props ->
        val group = props.group
        val relayUrl = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://")
        val link = "https://nostrord.com/open/?relay=$relayHost&group=${group.id}"
        // Author = the relay's own pubkey (NIP-11), like native; falls back to zero bytes inside encodeNaddr.
        val relayPubkey = relayMetadata[relayUrl]?.pubkey ?: relayMetadata[relayUrl.trimEnd('/')]?.pubkey
        val naddr = "nostr:" + Nip19.encodeNaddr(identifier = group.id, relay = relayUrl, kind = 39000, pubkeyHex = relayPubkey)

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
                            +"Share Group"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                shareField("Link", link)
                shareField("Nostr Address (naddr)", naddr)
            }
        }
    }

private fun copyToClipboard(text: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
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
            onClick = { copyToClipboard(value) }
            +"Copy"
        }
    }
}
