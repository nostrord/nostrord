package org.nostr.nostrord.web.components

import kotlinx.coroutines.delay
import org.nostr.nostrord.web.bridge.launchApp
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface GeneratedKeyCardProps : Props {
    var privateKey: String
}

/**
 * Generated private key panel shown after "Generate New Identity". Displays the
 * key with a copy button — the click swaps the icon to a check for 2s as
 * affirmation (same pattern as the npub copy in MeMenu / AppShell).
 */
val GeneratedKeyCard =
    FC<GeneratedKeyCardProps> { props ->
        val (copied, setCopied) = useState { false }
        div {
            className = ClassName("genkey-card")
            div {
                className = ClassName("genkey-head")
                icon(Ic.Warning)
                span {
                    className = ClassName("genkey-title")
                    +"SAVE YOUR PRIVATE KEY"
                }
            }
            p {
                className = ClassName("genkey-sub")
                +"This is a copy of your key, not your only copy. Save it somewhere safe."
            }
            div {
                className = ClassName("genkey-value-row")
                code {
                    className = ClassName("genkey-value")
                    +props.privateKey
                }
                button {
                    className = ClassName("genkey-copy")
                    title = if (copied) "Copied" else "Copy private key"
                    onClick = {
                        if (props.privateKey.isNotBlank()) copyToClipboard(props.privateKey)
                        setCopied(true)
                        launchApp {
                            delay(2_000)
                            setCopied(false)
                        }
                    }
                    if (copied) icon(Ic.Check) else icon(Ic.ContentCopy)
                }
            }
        }
    }
