package org.nostr.nostrord.web.screens

import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.qrDataUrl
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface BunkerQrProps : Props {
    /** Called when the remote signer connects and login completes. */
    var onSuccess: () -> Unit
}

/**
 * NIP-46 nostrconnect (QR) login — layout over the shared LoginViewModel, same session
 * logic as the native QrCodeLoginContent. Shows the VM's `nostrconnect://` URI as a QR
 * code AND as a read-only input with a copy button, and an
 * Advanced section that lets the user override the relays and regenerate the QR — same
 * affordances the JVM build ships.
 */
val BunkerQr =
    FC<BunkerQrProps> { props ->
        // QR session logic lives in the shared LoginViewModel (commonMain), same as the
        // Compose login. The VM owns the nostrconnect WebSocket session and the persisted
        // relay list; this component is only layout + local edit state.
        val vm = useViewModel { LoginViewModel(AppModule.nostrRepository) }
        val relays = useStateFlow(vm.nostrConnectRelays)
        val uri = useStateFlow(vm.qrUri)
        val (error, setError) = useState<String?> { null }
        val (copied, setCopied) = useState { false }
        val (expanded, setExpanded) = useState { false }
        // Working copy of the relays — diverges from `relays` while the user edits,
        // re-syncs after Apply.
        val (editable, setEditable) = useState { relays }
        val (newRelay, setNewRelay) = useState { "" }

        // Start (or restart) the QR session. The VM cancels any previous session and
        // reads the current relay list; on unmount useViewModel clears the VM, tearing
        // the WebSocket session down (LoginViewModel.onCleared disconnects the client).
        fun restartSession() {
            setError(null)
            vm.startQrSession(
                onConnected = { props.onSuccess() },
                onError = { message -> if (message != null) setError(message) },
            )
        }
        useEffect(vm) { restartSession() }

        fun copyUri(value: String) {
            copyToClipboard(value)
            setCopied(true)
            launchApp {
                delay(2_000)
                setCopied(false)
            }
        }

        fun applyRelays() {
            // Mirror native NostrConnectRelaysSection's apply: trim, keep only valid
            // wss:// URIs, drop duplicates. Empty list is a no-op so the QR keeps
            // generating against the previous valid set.
            val cleaned = editable.map { it.trim() }.filter { it.startsWith("wss://") }.distinct()
            if (cleaned.isEmpty()) return
            vm.setNostrConnectRelays(cleaned)
            setEditable(cleaned)
            restartSession()
        }

        val dirty = editable != relays

        div {
            className = ClassName("bunker-qr")
            p {
                className = ClassName("bunker-scan")
                +"Scan with your signer app"
            }
            p {
                className = ClassName("bunker-scan-sub")
                +"(Amber, nsec.app, etc.)"
            }
            when {
                error != null ->
                    div {
                        className = ClassName("qr-placeholder")
                        icon(Ic.Warning)
                    }
                uri == null ->
                    div {
                        className = ClassName("qr-placeholder")
                        // Circular spinner instead of the previous "…" — reads as
                        // "we're working on it" rather than a buffering hint.
                        div { className = ClassName("qr-spinner") }
                    }
                else ->
                    // The QR image itself is the deep link (matches native, where the
                    // QR is clickable and calls uriHandler.openUri). On the same phone
                    // you can't scan it, so tapping hands the nostrconnect:// URI to
                    // the installed signer (Amber, etc.).
                    a {
                        className = ClassName("qr-link")
                        href = uri
                        img {
                            className = ClassName("qr-img")
                            src = qrDataUrl(uri)
                            alt = "nostrconnect QR code"
                        }
                    }
            }

            // Tap-to-open caption below the QR (matches native's "Tap to open in
            // signer app", which it only shows on Android). Hidden on desktop and
            // revealed on coarse-pointer devices via CSS.
            if (uri != null) {
                p {
                    className = ClassName("bunker-tap-hint")
                    +"Tap to open in signer app"
                }
            }

            // URI + copy input (matches native OutlinedTextField with copy trailing icon).
            uri?.let { connectUri ->
                div {
                    className = ClassName("field-with-icon bunker-uri")
                    input {
                        className = ClassName("login-input")
                        value = connectUri
                        readOnly = true
                        onChange = { /* read-only */ }
                    }
                    button {
                        className = ClassName(if (copied) "field-action copied" else "field-action")
                        title = if (copied) "Copied" else "Copy URI"
                        onClick = { copyUri(connectUri) }
                        icon(if (copied) Ic.Check else Ic.ContentCopy)
                    }
                }
            }

            if (error != null) {
                p {
                    className = ClassName("login-error")
                    +error
                }
                button {
                    className = ClassName("login-primary")
                    onClick = { restartSession() }
                    icon(Ic.QrCode)
                    +"Try Again"
                }
            }

            // Advanced — signer relays. Collapsed by default; expands into a read-only
            // list of the current relays (with X removal), an input + add button for
            // new ones, and an Apply & regenerate QR button. Same UX as native
            // NostrConnectRelaysSection.
            div {
                className = ClassName("advanced-section")
                div {
                    className = ClassName("advanced-header")
                    onClick = { setExpanded(!expanded) }
                    span {
                        className =
                            ClassName(if (expanded) "advanced-chevron" else "advanced-chevron collapsed")
                        icon(Ic.ExpandMore)
                    }
                    span {
                        className = ClassName("advanced-title")
                        +"Advanced signer relays"
                    }
                }
                if (expanded) {
                    p {
                        className = ClassName("advanced-desc")
                        +"The signer connects through these relays. Change them if the QR won't connect."
                    }
                    editable.forEachIndexed { index, relay ->
                        div {
                            key = "$index-$relay"
                            className = ClassName("advanced-relay-row")
                            span {
                                className = ClassName("advanced-relay-url")
                                +relay
                            }
                            button {
                                className = ClassName("advanced-relay-remove")
                                title = "Remove relay"
                                onClick = {
                                    setEditable(editable.toMutableList().also { it.removeAt(index) })
                                }
                                icon(Ic.Close)
                            }
                        }
                    }
                    div {
                        className = ClassName("field-with-icon advanced-add")
                        input {
                            className = ClassName("login-input")
                            placeholder = "wss://relay.example.com"
                            value = newRelay
                            onChange = { event -> setNewRelay(event.currentTarget.value) }
                        }
                        button {
                            className = ClassName("field-action add")
                            title = "Add relay"
                            disabled = !newRelay.trim().startsWith("wss://") || newRelay.trim() in editable
                            onClick = {
                                val r = newRelay.trim()
                                if (r.startsWith("wss://") && r !in editable) {
                                    setEditable(editable + r)
                                    setNewRelay("")
                                }
                            }
                            icon(Ic.Add)
                        }
                    }
                    button {
                        className = ClassName("login-primary advanced-apply")
                        disabled = !dirty || editable.isEmpty()
                        onClick = { applyRelays() }
                        icon(Ic.QrCode)
                        +"Apply & regenerate QR"
                    }
                }
            }
        }
    }
