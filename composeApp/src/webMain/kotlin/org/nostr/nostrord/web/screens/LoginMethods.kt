package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.formHint
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import org.nostr.nostrord.web.components.tabItem
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

private enum class Tab { Key, Bunker, Extension }

private enum class BunkerMode { Qr, Url }

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

external interface LoginMethodsProps : Props {
    /** Submit button label (e.g. "Login" / "Add account"). */
    var submitLabel: String

    /** Busy-state button label (e.g. "Logging in…" / "Adding…"). */
    var busyLabel: String

    /**
     * Called after a successful auth action. On the login page this is a no-op (the auth
     * gate swaps the screen when repo.isLoggedIn flips); in the add-account modal it
     * closes the modal. The new account is warm-swapped active either way.
     */
    var onSuccess: () -> Unit
}

/**
 * The tabbed credential picker shared by the login page and the add-account modal:
 * Private Key (hex / nsec / generate), Bunker (NIP-46 QR or URL) and the NIP-07 browser
 * extension. Owns the tab + busy + error state and reuses [KeyLoginForm] / [BunkerQr] so
 * the two entry points stay identical. Login and add-account call the same LoginViewModel
 * methods; the difference is only [onSuccess].
 */
val LoginMethods =
    FC<LoginMethodsProps> { props ->
        val vm = useViewModel { LoginViewModel(AppModule.nostrRepository) }
        val extensionAvailable = Nip07.isAvailable()
        val (tab, setTab) = useState { Tab.Key }
        val (bunkerMode, setBunkerMode) = useState { BunkerMode.Qr }
        val (bunkerUrl, setBunkerUrl) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        // Run a VM auth action. The VM launches on its own scope, so we just react to the
        // callback: success calls onSuccess, failure surfaces the error string.
        fun run(start: ((Result<Unit>) -> Unit) -> Unit) {
            setError(null)
            setBusy(true)
            start { result ->
                setBusy(false)
                val err = result.exceptionOrNull()
                if (err == null) props.onSuccess() else setError(err.message ?: "Login failed")
            }
        }

        div {
            className = ClassName("tab-strip")
            tabItem(tab == Tab.Key, Ic.Key, "Private Key") { setTab(Tab.Key) }
            tabItem(tab == Tab.Bunker, Ic.Shield, "Bunker") { setTab(Tab.Bunker) }
            if (extensionAvailable) {
                tabItem(tab == Tab.Extension, Ic.Extension, "Extension") { setTab(Tab.Extension) }
            }
        }

        div {
            className = ClassName("login-tab-content")
            formError(error)
            when (tab) {
                Tab.Key -> {
                    KeyLoginForm {
                        this.vm = vm
                        this.busy = busy
                        submitLabel = props.submitLabel
                        busyLabel = props.busyLabel
                        onSubmit = { input, password, isNewIdentity ->
                            run { cb ->
                                vm.loginWithPrivateKeyInput(
                                    input,
                                    password = password,
                                    isNewIdentity = isNewIdentity,
                                    onResult = cb,
                                )
                            }
                        }
                        onSubmitProtected = { input, password, isNewIdentity ->
                            run { cb ->
                                vm.loginProtected(
                                    input,
                                    password,
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
                        tabItem(bunkerMode == BunkerMode.Qr, Ic.QrCode, "QR Code") { setBunkerMode(BunkerMode.Qr) }
                        tabItem(bunkerMode == BunkerMode.Url, Ic.Keyboard, "Bunker URL") { setBunkerMode(BunkerMode.Url) }
                    }
                    when (bunkerMode) {
                        BunkerMode.Qr -> BunkerQr { onSuccess = { props.onSuccess() } }
                        BunkerMode.Url -> {
                            val submitBunker = {
                                if (bunkerUrl.isNotBlank() && !busy) {
                                    run { cb -> vm.loginWithBunker(bunkerUrl, onResult = cb) }
                                }
                            }
                            iconInput(
                                ic = Ic.Link,
                                type = InputType.text,
                                placeholder = "bunker://<pubkey>?relay=wss://...",
                                value = bunkerUrl,
                                onChange = { setBunkerUrl(it) },
                                onEnter = { submitBunker() },
                            )
                            formHint("Get your bunker URL from nsec.app, Amber, or other NIP-46 signers")
                            button {
                                className = ClassName("btn-primary btn-lg btn-full login-submit")
                                disabled = bunkerUrl.isBlank() || busy
                                onClick = { submitBunker() }
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
                            onClick = { run { cb -> vm.loginWithNip07Extension(onResult = cb) } }
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
