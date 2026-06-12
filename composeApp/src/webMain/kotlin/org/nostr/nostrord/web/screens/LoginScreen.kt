package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.useViewModel
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

/**
 * Login screen — layout-first React port of the Compose NostrLoginScreen (all modes:
 * private key + generate, NIP-46 bunker QR/URL, NIP-07 extension). Login actions are
 * stubbed for now (frontend-only); "Generate" works locally. Wiring to AppModule comes
 * after the layout is validated.
 */
val LoginScreen =
    FC<Props> {
        val vm = useViewModel { LoginViewModel(AppModule.nostrRepository) }
        val extensionAvailable = Nip07.isAvailable()
        val (tab, setTab) = useState { Tab.Key }
        val (bunkerMode, setBunkerMode) = useState { BunkerMode.Qr }
        val (bunkerUrl, setBunkerUrl) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        // Run a VM login action. Success flips repo.isLoggedIn and the auth gate swaps to
        // the shell; failure surfaces the error string. The VM launches on its own scope,
        // so we just react to the callback.
        fun runLogin(start: ((Result<Unit>) -> Unit) -> Unit) {
            setError(null)
            setBusy(true)
            start { result ->
                setBusy(false)
                result.exceptionOrNull()?.let { setError(it.message ?: "Login failed") }
            }
        }

        div {
            className = ClassName("login-page")
            div {
                className = ClassName("login-inner")

                div {
                    className = ClassName(if (extensionAvailable) "login-card wide" else "login-card")

                    // Header lives inside the card (prototype layout): logo on the
                    // brand tile, app name, tagline.
                    div {
                        className = ClassName("login-head")
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
                            +"decentralized groups on nostr"
                        }
                    }

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
                                KeyLoginForm {
                                    this.vm = vm
                                    this.busy = busy
                                    submitLabel = "Login"
                                    busyLabel = "Logging in…"
                                    onSubmit = { input, password, isNewIdentity ->
                                        runLogin { cb ->
                                            vm.loginWithPrivateKeyInput(
                                                input,
                                                password = password,
                                                isNewIdentity = isNewIdentity,
                                                onResult = cb,
                                            )
                                        }
                                    }
                                }
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
                                            className = ClassName("btn-primary btn-lg btn-full login-submit")
                                            disabled = bunkerUrl.isBlank() || busy
                                            onClick = { runLogin { cb -> vm.loginWithBunker(bunkerUrl, onResult = cb) } }
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
                                        +"Connect using a NIP-07 compatible extension such as nos2x or Nostrame."
                                    }
                                    button {
                                        className = ClassName("btn-primary btn-lg btn-full")
                                        disabled = busy
                                        onClick = { runLogin { cb -> vm.loginWithNip07Extension(onResult = cb) } }
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
            }
        }
    }
