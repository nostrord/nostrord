package org.nostr.nostrord.web.screens

import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.web.auth.WebAuth
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.components.GeneratedKeyCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.password
import web.html.text
import kotlin.random.Random

private enum class Tab { Key, Bunker, Extension }

private enum class BunkerMode { Qr, Url }

private fun ChildrenBuilder.tabButton(selected: Boolean, ic: Ic, label: String, onSelect: () -> Unit) {
    button {
        className = ClassName(if (selected) "login-tab selected" else "login-tab")
        onClick = { onSelect() }
        icon(ic)
        span { +label }
    }
}

private fun ChildrenBuilder.benefit(text: String) {
    div {
        className = ClassName("benefit")
        span {
            className = ClassName("benefit-check")
            icon(Ic.Check)
        }
        span { +text }
    }
}

// generatedKeyCard moved to web/components/GeneratedKeyCard.kt as an FC so it
// can hold its own copy-feedback state.

/**
 * Login screen — layout-first React port of the Compose NostrLoginScreen (all modes:
 * private key + generate, NIP-46 bunker QR/URL, NIP-07 extension). Login actions are
 * stubbed for now (frontend-only); "Generate" works locally. Wiring to AppModule comes
 * after the layout is validated.
 */
val LoginScreen =
    FC<Props> {
        val extensionAvailable = Nip07.isAvailable()
        val (tab, setTab) = useState { Tab.Key }
        val (privateKey, setPrivateKey) = useState { "" }
        val (showKey, setShowKey) = useState { false }
        val (generatedKey, setGeneratedKey) = useState<String?> { null }
        val (bunkerMode, setBunkerMode) = useState { BunkerMode.Qr }
        val (bunkerUrl, setBunkerUrl) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        // Run a login action; null result = success (the auth gate swaps to the shell).
        fun runLogin(block: suspend () -> String?) {
            setError(null)
            setBusy(true)
            launchApp {
                val err = block()
                setBusy(false)
                if (err != null) setError(err)
            }
        }

        fun generate() {
            val hex =
                Random.Default.nextBytes(32).joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            setPrivateKey(hex)
            setGeneratedKey(hex)
        }

        div {
            className = ClassName("login-page")
            div {
                className = ClassName("login-inner")

                img {
                    className = ClassName("login-logo")
                    src = "icon-192.png"
                    alt = "Nostrord"
                }
                h1 {
                    className = ClassName("login-title")
                    +"Nostrord"
                }
                p {
                    className = ClassName("login-subtitle")
                    +"Connect to the Nostr network"
                }

                div {
                    className = ClassName(if (extensionAvailable) "login-card wide" else "login-card")

                    div {
                        className = ClassName("login-tabs")
                        tabButton(tab == Tab.Key, Ic.Key, "Private Key") { setTab(Tab.Key) }
                        tabButton(tab == Tab.Bunker, Ic.Shield, "Bunker") { setTab(Tab.Bunker) }
                        if (extensionAvailable) {
                            tabButton(tab == Tab.Extension, Ic.Extension, "Extension") { setTab(Tab.Extension) }
                        }
                    }

                    div {
                        className = ClassName("login-tab-content")
                        error?.let {
                            p {
                                className = ClassName("login-error")
                                +it
                            }
                        }
                        when (tab) {
                            Tab.Key -> {
                                div {
                                    className = ClassName("field-with-icon")
                                    span {
                                        className = ClassName("field-icon")
                                        icon(Ic.Key)
                                    }
                                    input {
                                        className = ClassName("login-input")
                                        type = if (showKey) InputType.text else InputType.password
                                        placeholder = "Enter your private key (hex or nsec)"
                                        value = privateKey
                                        onChange = { event -> setPrivateKey(event.currentTarget.value) }
                                    }
                                    button {
                                        className = ClassName("field-eye")
                                        onClick = { setShowKey(!showKey) }
                                        if (showKey) icon(Ic.VisibilityOff) else icon(Ic.Visibility)
                                    }
                                }
                                button {
                                    className = ClassName("login-primary")
                                    disabled = privateKey.isBlank() || busy
                                    onClick = {
                                        runLogin {
                                            WebAuth.loginWithPrivateKey(
                                                privateKey,
                                                isNewIdentity = generatedKey != null && privateKey == generatedKey,
                                            )
                                        }
                                    }
                                    if (busy) {
                                        span { className = ClassName("btn-spinner") }
                                    } else {
                                        icon(Ic.Login)
                                    }
                                    +(if (busy) "Logging in…" else "Login")
                                }
                                div {
                                    className = ClassName("login-divider")
                                    span { +"or" }
                                }
                                button {
                                    className = ClassName("login-outline-success")
                                    onClick = { generate() }
                                    icon(Ic.AutoAwesome)
                                    +"Generate New Identity"
                                }
                                generatedKey?.let { hex -> GeneratedKeyCard { this.privateKey = hex } }
                            }

                            Tab.Bunker -> {
                                div {
                                    className = ClassName("bunker-desc")
                                    icon(Ic.Shield)
                                    span { +"Connect to a remote signer for secure key management" }
                                }
                                div {
                                    className = ClassName("bunker-toggle")
                                    button {
                                        className = ClassName(if (bunkerMode == BunkerMode.Qr) "login-tab selected" else "login-tab")
                                        onClick = { setBunkerMode(BunkerMode.Qr) }
                                        icon(Ic.QrCode)
                                        span { +"QR Code" }
                                    }
                                    button {
                                        className = ClassName(if (bunkerMode == BunkerMode.Url) "login-tab selected" else "login-tab")
                                        onClick = { setBunkerMode(BunkerMode.Url) }
                                        icon(Ic.Keyboard)
                                        span { +"Bunker URL" }
                                    }
                                }
                                when (bunkerMode) {
                                    BunkerMode.Qr -> BunkerQr { onSuccess = {} }
                                    BunkerMode.Url -> {
                                        div {
                                            className = ClassName("field-with-icon")
                                            span {
                                                className = ClassName("field-icon")
                                                icon(Ic.Link)
                                            }
                                            input {
                                                className = ClassName("login-input")
                                                placeholder = "bunker://<pubkey>?relay=wss://..."
                                                value = bunkerUrl
                                                onChange = { event -> setBunkerUrl(event.currentTarget.value) }
                                            }
                                        }
                                        p {
                                            className = ClassName("login-hint")
                                            +"Get your bunker URL from nsec.app, Amber, or other NIP-46 signers"
                                        }
                                        button {
                                            className = ClassName("login-primary")
                                            disabled = bunkerUrl.isBlank() || busy
                                            onClick = { runLogin { WebAuth.loginWithBunker(bunkerUrl) } }
                                            if (busy) {
                                                span { className = ClassName("btn-spinner") }
                                            }
                                            +(if (busy) "Connecting…" else "Connect to Bunker")
                                        }
                                    }
                                }
                                div {
                                    className = ClassName("bunker-benefits")
                                    div {
                                        className = ClassName("benefits-head")
                                        icon(Ic.Lock)
                                        span {
                                            className = ClassName("benefits-title")
                                            +"Why use a Bunker?"
                                        }
                                    }
                                    benefit("Your private key never leaves the signer")
                                    benefit("Approve each signing request")
                                    benefit("Works with hardware signers")
                                    benefit("Revoke access anytime")
                                }
                            }

                            Tab.Extension -> {
                                div {
                                    className = ClassName("ext-content")
                                    span {
                                        className = ClassName("ext-icon")
                                        icon(Ic.Extension)
                                    }
                                    div {
                                        className = ClassName("ext-title")
                                        +"Browser Extension Login"
                                    }
                                    p {
                                        className = ClassName("ext-desc")
                                        +"Connect using a NIP-07 compatible extension such as Alby, nos2x, or Nostrame."
                                    }
                                    button {
                                        className = ClassName("login-primary")
                                        disabled = busy
                                        onClick = { runLogin { WebAuth.loginWithExtension() } }
                                        // Native ExtensionLoginTab puts a small spinner
                                        // before the "Connecting..." label when busy
                                        // (BunkerLoginTab.kt:75-82). Mirror it here so
                                        // the click reads as "working", not idle.
                                        if (busy) {
                                            span { className = ClassName("btn-spinner") }
                                        }
                                        +(if (busy) "Connecting…" else "Connect Extension")
                                    }
                                }
                            }
                        }
                    }
                }

                p {
                    className = ClassName("login-footer")
                    +"New to Nostr? Generate a key to get started instantly."
                }
            }
        }
    }
