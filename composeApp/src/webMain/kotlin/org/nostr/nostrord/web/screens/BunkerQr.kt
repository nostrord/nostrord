package org.nostr.nostrord.web.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.getNostrConnectRelays
import org.nostr.nostrord.storage.saveNostrConnectRelays
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.qrDataUrl
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

external interface BunkerQrProps : Props {
    /** Called when the remote signer connects and login completes. */
    var onSuccess: () -> Unit
}

/**
 * NIP-46 nostrconnect (QR) login — real port of the native [QrCodeLoginContent]. Creates a
 * session for the user's nostrconnect relays (persisted via [SecureStorage]), shows the
 * `nostrconnect://` URI as a QR code AND as a read-only input with a copy button, and an
 * Advanced section that lets the user override the relays and regenerate the QR — same
 * affordances the JVM build ships.
 */
val BunkerQr =
    FC<BunkerQrProps> { props ->
        val repo = AppModule.nostrRepository

        // Applied (committed) relays — drives session creation. Loaded once from
        // SecureStorage, falls back to the repo's defaults. Updating via setRelays
        // is what `Apply & regenerate QR` does after persisting the new list.
        val (relays, setRelays) = useState {
            SecureStorage.getNostrConnectRelays() ?: repo.defaultNostrConnectRelays
        }
        val (sessionKey, setSessionKey) = useState { 0 }
        val (uri, setUri) = useState<String?> { null }
        val (error, setError) = useState<String?> { null }
        val (copied, setCopied) = useState { false }
        val (expanded, setExpanded) = useState { false }
        // Working copy of the relays — diverges from `relays` while the user edits,
        // re-syncs after Apply.
        val (editable, setEditable) = useState { relays }
        val (newRelay, setNewRelay) = useState { "" }

        // Cancel + restart the session whenever sessionKey or relays change. Using a
        // jobRef instead of suspending the useEffect itself so the launchApp scope
        // (already attached to the app lifecycle) keeps owning the coroutine.
        val jobRef = useRef<Job>(null)
        useEffect(sessionKey) {
            jobRef.current?.cancel()
            setUri(null)
            setError(null)
            jobRef.current =
                launchApp {
                    try {
                        val (connectUri, client) = repo.createNostrConnectSession(relays)
                        setUri(connectUri)
                        repo.completeNostrConnectLogin(client)
                        props.onSuccess()
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Throwable) {
                        setError("Connection failed: ${e.message}")
                    }
                }
        }

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
            SecureStorage.saveNostrConnectRelays(cleaned)
            setRelays(cleaned)
            setEditable(cleaned)
            setSessionKey(sessionKey + 1)
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
                    img {
                        className = ClassName("qr-img")
                        src = qrDataUrl(uri)
                        alt = "nostrconnect QR code"
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
                    onClick = { setSessionKey(sessionKey + 1) }
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
