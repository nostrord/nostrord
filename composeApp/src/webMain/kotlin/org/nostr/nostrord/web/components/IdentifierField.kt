package org.nostr.nostrord.web.components

import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.nprofileRelayHints
import org.nostr.nostrord.ui.pubkeyIdentifiers
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useMemo
import react.useState
import web.cssom.ClassName

external interface IdentifierRowProps : Props {
    var ids: List<Identifier>
}

/**
 * Cycling identifier field (prototype IdentifierField, the .identifier-* OOCSS
 * object): the swap button rotates the [IdentifierRowProps.ids] formats and the
 * copy button copies the visible value (checkmark feedback). Mirrors the Compose
 * ui/components/IdentifierField.
 */
val IdentifierRow =
    FC<IdentifierRowProps> { props ->
        val ids = props.ids
        val (index, setIndex) = useState { 0 }
        val (copied, setCopied) = useState { false }
        if (ids.isEmpty()) return@FC
        val id = ids[index % ids.size]

        div {
            className = ClassName("identifier-field")
            span {
                className = ClassName("identifier-value")
                +id.value
            }
            if (ids.size > 1) {
                button {
                    className = ClassName("identifier-btn")
                    title = "Format: ${id.label} (switch)"
                    onClick = { setIndex((index + 1) % ids.size) }
                    icon(Ic.Cached)
                }
            }
            button {
                className = ClassName(if (copied) "identifier-btn copied" else "identifier-btn")
                title = "Copy"
                onClick = {
                    copyToClipboard(id.value)
                    setCopied(true)
                    window.setTimeout({ setCopied(false) }, 1200)
                }
                icon(if (copied) Ic.Check else Ic.ContentCopy)
            }
        }
        if (ids.size > 1) {
            div {
                className = ClassName("identifier-format")
                +"Format: "
                span { +id.label }
            }
        }
    }

external interface IdentifierFieldProps : Props {
    var pubkey: String
    var nip05: String?
}

/**
 * [IdentifierRow] over the pubkey formats (npub / nprofile / nostrord link /
 * hex / nip-05). Used by the profile modal and the profile page.
 */
val IdentifierField =
    FC<IdentifierFieldProps> { props ->
        val ids =
            useMemo(props.pubkey, props.nip05) {
                val hints = nprofileRelayHints(AppModule.nostrRepository.getRelayListForPubkey(props.pubkey).orEmpty())
                pubkeyIdentifiers(props.pubkey, props.nip05, hints)
            }
        IdentifierRow { this.ids = ids }
    }
