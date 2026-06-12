package org.nostr.nostrord.web.components

import kotlinx.browser.window
import org.nostr.nostrord.ui.pubkeyIdentifiers
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useMemo
import react.useState
import web.cssom.ClassName

external interface IdentifierFieldProps : Props {
    var pubkey: String
    var nip05: String?
}

/**
 * Cycling pubkey identifier field (prototype IdentifierField): the swap button
 * rotates npub / nprofile / nostrord link / hex / nip-05 and the copy button
 * copies the visible value (checkmark feedback). Used by the profile modal and
 * the profile page; mirrors the Compose ui/components/IdentifierField.
 */
val IdentifierField =
    FC<IdentifierFieldProps> { props ->
        val ids = useMemo(props.pubkey, props.nip05) { pubkeyIdentifiers(props.pubkey, props.nip05) }
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
            button {
                className = ClassName("identifier-btn")
                title = "Format: ${id.label} (switch)"
                onClick = { setIndex((index + 1) % ids.size) }
                icon(Ic.Cached)
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
        div {
            className = ClassName("identifier-format")
            +"Format: "
            span { +id.label }
        }
    }
