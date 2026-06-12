package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import org.nostr.nostrord.web.components.tabItem
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

external interface AddAccountSheetProps : Props {
    var onClose: () -> Unit
}

private enum class AddStep { Pick, Key, Bunker, Extension }

private enum class AddBunkerMode { Qr, Url }

/**
 * Add-account sheet — layout-first React port of the Compose AddAccountSheet. Step 1 picks
 * a method (Private key / Bunker / Browser extension), then shows the matching credential
 * form (reusing the login visuals). Desktop = popover above the rail avatar; narrow = bottom
 * sheet. Adding is stubbed — a successful action just closes the sheet.
 */
val AddAccountSheet =
    FC<AddAccountSheetProps> { props ->
        val vm = useViewModel { LoginViewModel(AppModule.nostrRepository) }
        val (step, setStep) = useState { AddStep.Pick }
        val (bunkerMode, setBunkerMode) = useState { AddBunkerMode.Qr }
        val (bunkerUrl, setBunkerUrl) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        val extensionAvailable = Nip07.isAvailable()

        val activePubkey = AppModule.nostrRepository.getPublicKey()
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val activeName =
            activePubkey?.let { userMetadata[it] }?.let { it.displayName?.ifBlank { null } ?: it.name?.ifBlank { null } }
                ?: AppModule.accountStore.active?.label
                ?: "your account"

        // Run an add-account action via the shared LoginViewModel; on success the warm-swap
        // activates the new account and the sheet closes, otherwise the error is shown.
        // [fallback] is the method-specific message used when the error carries none (mirrors
        // the old WebAuth per-method strings, e.g. "Failed to connect to bunker").
        fun runAdd(fallback: String, start: ((Result<Unit>) -> Unit) -> Unit) {
            setError(null)
            setBusy(true)
            start { result ->
                setBusy(false)
                val err = result.exceptionOrNull()
                if (err == null) props.onClose() else setError(err.message?.takeIf { it.isNotBlank() } ?: fallback)
            }
        }

        div {
            className = ClassName("me-menu-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("add-account-sheet")
                onClick = { it.stopPropagation() }

                // Header
                div {
                    className = ClassName("add-sheet-header")
                    if (step == AddStep.Pick) {
                        span {
                            className = ClassName("add-sheet-icon")
                            icon(Ic.Person)
                        }
                    } else {
                        button {
                            className = ClassName("add-sheet-back")
                            onClick = { setStep(AddStep.Pick) }
                            icon(Ic.ArrowBack)
                        }
                    }
                    span {
                        className = ClassName("add-sheet-title")
                        +when (step) {
                            AddStep.Pick -> "Add account"
                            AddStep.Key -> "Private key"
                            AddStep.Bunker -> "Bunker"
                            AddStep.Extension -> "Browser extension"
                        }
                    }
                    button {
                        className = ClassName("add-sheet-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }
                div { className = ClassName("add-sheet-divider") }

                div {
                    className = ClassName("add-sheet-body")
                    formError(error)
                    when (step) {
                        AddStep.Pick -> {
                            p {
                                className = ClassName("add-sheet-note")
                                +"You'll keep $activeName signed in."
                            }
                            methodRow(Ic.Key, "Private key") { setStep(AddStep.Key) }
                            methodRow(Ic.Shield, "Bunker (NIP-46)") { setStep(AddStep.Bunker) }
                            if (extensionAvailable) {
                                methodRow(Ic.Extension, "Browser extension") { setStep(AddStep.Extension) }
                            }
                        }

                        AddStep.Key -> {
                            KeyLoginForm {
                                this.vm = vm
                                this.busy = busy
                                submitLabel = "Add account"
                                busyLabel = "Adding…"
                                onSubmit = { input, password, isNewIdentity ->
                                    runAdd("Could not add account") { cb ->
                                        vm.loginWithPrivateKeyInput(
                                            input,
                                            password = password,
                                            isNewIdentity = isNewIdentity,
                                            onResult = cb,
                                        )
                                    }
                                }
                                onSubmitProtected = { input, password, isNewIdentity ->
                                    runAdd("Could not add account") { cb ->
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

                        AddStep.Bunker -> {
                            div {
                                className = ClassName("bunker-desc")
                                icon(Ic.Shield)
                                span { +"Connect to a remote signer for secure key management" }
                            }
                            div {
                                className = ClassName("bunker-toggle")
                                tabItem(bunkerMode == AddBunkerMode.Qr, Ic.QrCode, "QR Code") { setBunkerMode(AddBunkerMode.Qr) }
                                tabItem(bunkerMode == AddBunkerMode.Url, Ic.Keyboard, "Bunker URL") { setBunkerMode(AddBunkerMode.Url) }
                            }
                            when (bunkerMode) {
                                AddBunkerMode.Qr -> BunkerQr { onSuccess = { props.onClose() } }

                                AddBunkerMode.Url -> {
                                    val submitBunker = {
                                        if (bunkerUrl.isNotBlank() && !busy) {
                                            runAdd("Failed to connect to bunker") { cb -> vm.loginWithBunker(bunkerUrl, onResult = cb) }
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
                                    button {
                                        className = ClassName("btn-primary btn-lg btn-full login-submit")
                                        disabled = bunkerUrl.isBlank() || busy
                                        onClick = { submitBunker() }
                                        +(if (busy) "Connecting…" else "Connect to Bunker")
                                    }
                                }
                            }
                        }

                        AddStep.Extension ->
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
                                    className = ClassName("btn-primary btn-lg btn-full login-submit")
                                    disabled = busy
                                    onClick = { runAdd("Extension login failed") { cb -> vm.loginWithNip07Extension(onResult = cb) } }
                                    +(if (busy) "Connecting…" else "Connect Extension")
                                }
                            }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.methodRow(ic: Ic, label: String, onSelect: () -> Unit) {
    div {
        className = ClassName("add-method-row")
        onClick = { onSelect() }
        span {
            className = ClassName("add-method-icon")
            icon(ic)
        }
        span {
            className = ClassName("add-method-label")
            +label
        }
        span {
            className = ClassName("add-method-chevron")
            icon(Ic.ChevronRight)
        }
    }
}
