package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.web.auth.WebAuth
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.GeneratedKeyCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.password
import web.html.text
import kotlin.random.Random

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
        val (step, setStep) = useState { AddStep.Pick }
        val (privateKey, setPrivateKey) = useState { "" }
        val (showKey, setShowKey) = useState { false }
        val (generatedKey, setGeneratedKey) = useState<String?> { null }
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

        // Run an add-account action; on success (null) the warm-swap activates the new
        // account and the sheet closes, otherwise the error is shown.
        fun runAdd(block: suspend () -> String?) {
            setError(null)
            setBusy(true)
            launchApp {
                val err = block()
                setBusy(false)
                if (err == null) props.onClose() else setError(err)
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
                    error?.let {
                        p {
                            className = ClassName("login-error")
                            +it
                        }
                    }
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
                                    runAdd {
                                        WebAuth.loginWithPrivateKey(
                                            privateKey,
                                            isNewIdentity = generatedKey != null && privateKey == generatedKey,
                                        )
                                    }
                                }
                                +(if (busy) "Adding…" else "Add account")
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

                        AddStep.Bunker -> {
                            div {
                                className = ClassName("bunker-desc")
                                icon(Ic.Shield)
                                span { +"Connect to a remote signer for secure key management" }
                            }
                            div {
                                className = ClassName("bunker-toggle")
                                button {
                                    className = ClassName(if (bunkerMode == AddBunkerMode.Qr) "login-tab selected" else "login-tab")
                                    onClick = { setBunkerMode(AddBunkerMode.Qr) }
                                    icon(Ic.QrCode)
                                    span { +"QR Code" }
                                }
                                button {
                                    className = ClassName(if (bunkerMode == AddBunkerMode.Url) "login-tab selected" else "login-tab")
                                    onClick = { setBunkerMode(AddBunkerMode.Url) }
                                    icon(Ic.Keyboard)
                                    span { +"Bunker URL" }
                                }
                            }
                            when (bunkerMode) {
                                AddBunkerMode.Qr -> BunkerQr { onSuccess = { props.onClose() } }

                                AddBunkerMode.Url -> {
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
                                    button {
                                        className = ClassName("login-primary")
                                        disabled = bunkerUrl.isBlank() || busy
                                        onClick = { runAdd { WebAuth.loginWithBunker(bunkerUrl) } }
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
                                    className = ClassName("login-primary")
                                    disabled = busy
                                    onClick = { runAdd { WebAuth.loginWithExtension() } }
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

