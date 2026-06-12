package org.nostr.nostrord.web.screens

import kotlinx.coroutines.delay
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.components.GeneratedKeyCard
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox
import web.html.password
import web.html.text

external interface KeyLoginFormProps : Props {
    var vm: LoginViewModel
    var busy: Boolean

    /** Button labels, e.g. "Login"/"Logging in…" or "Add account"/"Adding…". */
    var submitLabel: String
    var busyLabel: String

    /** Run the actual login: (input, ncryptsec password or null, isNewIdentity). */
    var onSubmit: (String, String?, Boolean) -> Unit
}

/**
 * The private-key credential form (prototype flow), shared by LoginScreen and the
 * add-account sheet so the two never drift:
 *  - nsec / hex / ncryptsec input (label + hint + eye toggle), strict validity gating;
 *  - pasting an ncryptsec reveals the key-password field;
 *  - a plain hex/nsec offers "Protect with password" producing an ncryptsec backup
 *    that is shown to copy before signing in;
 *  - "Generate New Key" runs the two-step wizard (backup npub/nsec, then optional
 *    password that wraps the new key as an ncryptsec backup).
 * Mirrors the Compose PrivateKeyLoginTab; logic lives in the shared LoginViewModel.
 */
val KeyLoginForm =
    FC<KeyLoginFormProps> { props ->
        val vm = props.vm
        val busy = props.busy

        val (privateKey, setPrivateKey) = useState { "" }
        val (keyPassword, setKeyPassword) = useState { "" }
        val (showKey, setShowKey) = useState { false }
        val (localError, setLocalError) = useState<String?> { null }

        // Protect-with-password (plain keys)
        val (protect, setProtect) = useState { false }
        val (protectPwd, setProtectPwd) = useState { "" }
        val (protectConfirm, setProtectConfirm) = useState { "" }

        // Generate wizard: 0 = form, 1 = backup step, 2 = password step
        val (wizardStep, setWizardStep) = useState { 0 }
        val (wizardKey, setWizardKey) = useState { "" }
        val (wizardPwd, setWizardPwd) = useState { "" }
        val (wizardConfirm, setWizardConfirm) = useState { "" }

        // Pending ncryptsec backup reveal: the string to show + the login to run on Continue
        val (backup, setBackup) = useState<Pair<String, Pair<String, Boolean>>?> { null }

        val isEncrypted = vm.isEncryptedKeyInput(privateKey)
        val isPlain = vm.isPlainKeyInput(privateKey)
        val protectActive = protect && isPlain
        val canSubmit =
            vm.isValidKeyInput(privateKey) &&
                (!isEncrypted || keyPassword.isNotEmpty()) &&
                (!protectActive || protectPwd.isNotEmpty()) &&
                !busy

        fun clearError() = setLocalError(null)

        /** Encrypt and show the ncryptsec backup; Continue then runs the login. */
        fun protectAndReveal(
            input: String,
            pwd: String,
            isNewIdentity: Boolean,
        ) {
            clearError()
            vm.encryptKeyToNcryptsec(input, pwd) { ncryptsec ->
                if (ncryptsec == null) {
                    setLocalError("Invalid private key")
                } else {
                    setBackup(ncryptsec to (input to isNewIdentity))
                }
            }
        }

        fun submit() {
            if (!canSubmit) return
            val input = privateKey.trim()
            when {
                isEncrypted -> props.onSubmit(input, keyPassword, false)
                protectActive -> {
                    if (protectPwd != protectConfirm) {
                        setLocalError("Passwords don't match")
                        return
                    }
                    protectAndReveal(input, protectPwd, isNewIdentity = false)
                }
                else -> props.onSubmit(input, null, false)
            }
        }

        localError?.let {
            p {
                className = ClassName("login-error")
                +it
            }
        }

        when {
            // ── ncryptsec backup reveal ──────────────────────────────────────
            backup != null -> {
                GeneratedKeyCard {
                    this.privateKey = backup.first
                    title = "SAVE YOUR ENCRYPTED KEY"
                    subtitle =
                        "This ncryptsec is your key encrypted with your password. " +
                        "Save it somewhere safe; log in with it and the password next time."
                }
                button {
                    className = ClassName("btn-primary btn-lg btn-full login-submit")
                    disabled = busy
                    onClick = {
                        val (input, isNew) = backup.second
                        props.onSubmit(input, null, isNew)
                    }
                    if (busy) {
                        span { className = ClassName("btn-spinner") }
                    }
                    +(if (busy) props.busyLabel else "Continue")
                }
            }

            // ── Generate wizard ──────────────────────────────────────────────
            wizardStep > 0 -> {
                div {
                    className = ClassName("wizard-dots")
                    span { className = ClassName(if (wizardStep == 1) "wizard-dot active" else "wizard-dot") }
                    span { className = ClassName(if (wizardStep == 2) "wizard-dot active" else "wizard-dot") }
                }
                if (wizardStep == 1) {
                    div {
                        className = ClassName("wizard-title")
                        +"Your new key"
                    }
                    p {
                        className = ClassName("wizard-sub")
                        +"Save the nsec somewhere safe. Whoever has it controls your account."
                    }
                    val bech = vm.deriveBech32Keys(wizardKey)
                    div {
                        className = ClassName("keyline-box")
                        KeyLineRow {
                            this.label = "npub"
                            this.value = bech?.first ?: ""
                            this.danger = false
                        }
                        KeyLineRow {
                            this.label = "nsec"
                            this.value = bech?.second ?: ""
                            this.danger = true
                        }
                    }
                    button {
                        className = ClassName("link-inline")
                        onClick = { setWizardKey(vm.generateNewKeyHex()) }
                        icon(Ic.Refresh)
                        +"Generate another key"
                    }
                    div {
                        className = ClassName("wizard-actions")
                        button {
                            className = ClassName("btn-ghost")
                            onClick = { setWizardStep(0) }
                            +"Back"
                        }
                        button {
                            className = ClassName("btn-primary grow")
                            onClick = { setWizardStep(2) }
                            +"Continue"
                        }
                    }
                } else {
                    div {
                        className = ClassName("wizard-title")
                        +"Protect your account"
                    }
                    p {
                        className = ClassName("wizard-sub")
                        +"Add an extra layer of protection with a password"
                    }
                    div {
                        className = ClassName("protect-info")
                        icon(Ic.Info)
                        div {
                            div {
                                className = ClassName("protect-info-title")
                                +"Password protection (recommended)"
                            }
                            div {
                                className = ClassName("protect-info-desc")
                                +"Wraps your new key as an encrypted ncryptsec backup. Optional, but strongly recommended."
                            }
                        }
                    }
                    div {
                        className = ClassName("login-field-label")
                        +"Password (optional)"
                    }
                    div {
                        className = ClassName("field-with-icon")
                        span {
                            className = ClassName("field-icon")
                            icon(Ic.Lock)
                        }
                        input {
                            className = ClassName("login-input")
                            type = InputType.password
                            placeholder = "Create a password (or skip)"
                            value = wizardPwd
                            onChange = { event ->
                                setWizardPwd(event.currentTarget.value)
                                clearError()
                            }
                        }
                    }
                    if (wizardPwd.isNotEmpty()) {
                        div {
                            className = ClassName("login-field-label spaced")
                            +"Confirm password"
                        }
                        div {
                            className = ClassName("field-with-icon")
                            span {
                                className = ClassName("field-icon")
                                icon(Ic.Lock)
                            }
                            input {
                                className = ClassName("login-input")
                                type = InputType.password
                                placeholder = "Repeat the password"
                                value = wizardConfirm
                                onChange = { event ->
                                    setWizardConfirm(event.currentTarget.value)
                                    clearError()
                                }
                            }
                        }
                    }
                    div {
                        className = ClassName("wizard-actions")
                        button {
                            className = ClassName("btn-ghost")
                            disabled = busy
                            onClick = { setWizardStep(1) }
                            +"Back"
                        }
                        button {
                            className = ClassName("btn-primary grow")
                            disabled = busy || (wizardPwd.isNotEmpty() && wizardConfirm.isEmpty())
                            onClick = {
                                when {
                                    wizardPwd.isEmpty() -> props.onSubmit(wizardKey, null, true)
                                    wizardPwd != wizardConfirm -> setLocalError("Passwords don't match")
                                    else -> protectAndReveal(wizardKey, wizardPwd, isNewIdentity = true)
                                }
                            }
                            if (busy) {
                                span { className = ClassName("btn-spinner") }
                            }
                            +(if (wizardPwd.isEmpty()) "Finish without password" else "Finish with password")
                        }
                    }
                }
            }

            // ── Login form ───────────────────────────────────────────────────
            else -> {
                div {
                    className = ClassName("login-field-label")
                    +"Private key (hex, nsec or ncryptsec)"
                }
                div {
                    className = ClassName("field-with-icon")
                    span {
                        className = ClassName("field-icon")
                        icon(Ic.Key)
                    }
                    input {
                        className = ClassName("login-input")
                        type = if (showKey) InputType.text else InputType.password
                        placeholder = "hex, nsec1, ncryptsec1"
                        value = privateKey
                        onChange = { event ->
                            setPrivateKey(event.currentTarget.value)
                            clearError()
                        }
                        onKeyDown = { event -> if (event.key == "Enter") submit() }
                    }
                    button {
                        className = ClassName("field-eye")
                        onClick = { setShowKey(!showKey) }
                        if (showKey) icon(Ic.VisibilityOff) else icon(Ic.Visibility)
                    }
                }
                p {
                    className = ClassName("login-hint")
                    +"Your key never leaves this device."
                }

                if (isEncrypted) {
                    div {
                        className = ClassName("login-field-label spaced")
                        +"Key password"
                    }
                    div {
                        className = ClassName("field-with-icon")
                        span {
                            className = ClassName("field-icon")
                            icon(Ic.Lock)
                        }
                        input {
                            className = ClassName("login-input")
                            type = InputType.password
                            placeholder = "Password"
                            value = keyPassword
                            onChange = { event ->
                                setKeyPassword(event.currentTarget.value)
                                clearError()
                            }
                            onKeyDown = { event -> if (event.key == "Enter") submit() }
                        }
                    }
                    p {
                        className = ClassName("login-hint")
                        +"This key is encrypted (NIP-49); enter its password to unlock it."
                    }
                }

                if (isPlain) {
                    label {
                        className = ClassName("protect-card")
                        input {
                            type = InputType.checkbox
                            checked = protect
                            onChange = { event ->
                                setProtect(event.currentTarget.checked)
                                clearError()
                            }
                        }
                        div {
                            div {
                                className = ClassName("protect-card-title")
                                icon(Ic.Shield)
                                +"Protect with password (recommended)"
                            }
                            div {
                                className = ClassName("protect-card-desc")
                                +"Wraps your key as an encrypted ncryptsec backup; log in with it and this password next time."
                            }
                        }
                    }
                    if (protect) {
                        div {
                            className = ClassName("login-field-label spaced")
                            +"Password"
                        }
                        div {
                            className = ClassName("field-with-icon")
                            span {
                                className = ClassName("field-icon")
                                icon(Ic.Lock)
                            }
                            input {
                                className = ClassName("login-input")
                                type = InputType.password
                                placeholder = "Create a password"
                                value = protectPwd
                                onChange = { event ->
                                    setProtectPwd(event.currentTarget.value)
                                    clearError()
                                }
                            }
                        }
                        if (protectPwd.isNotEmpty()) {
                            div {
                                className = ClassName("login-field-label spaced")
                                +"Confirm password"
                            }
                            div {
                                className = ClassName("field-with-icon")
                                span {
                                    className = ClassName("field-icon")
                                    icon(Ic.Lock)
                                }
                                input {
                                    className = ClassName("login-input")
                                    type = InputType.password
                                    placeholder = "Repeat the password"
                                    value = protectConfirm
                                    onChange = { event ->
                                        setProtectConfirm(event.currentTarget.value)
                                        clearError()
                                    }
                                    onKeyDown = { event -> if (event.key == "Enter") submit() }
                                }
                            }
                        }
                    }
                }

                button {
                    className = ClassName("btn-primary btn-lg btn-full login-submit")
                    disabled = !canSubmit
                    onClick = { submit() }
                    if (busy) {
                        span { className = ClassName("btn-spinner") }
                    } else {
                        icon(Ic.Login)
                    }
                    +(if (busy) props.busyLabel else props.submitLabel)
                }
                div {
                    className = ClassName("login-divider")
                    span { +"or" }
                }
                button {
                    className = ClassName("btn-secondary btn-lg btn-full")
                    disabled = busy
                    onClick = {
                        clearError()
                        setWizardKey(vm.generateNewKeyHex())
                        setWizardPwd("")
                        setWizardConfirm("")
                        setWizardStep(1)
                    }
                    icon(Ic.AutoAwesome)
                    +"Generate New Key"
                }
            }
        }
    }

private external interface KeyLineRowProps : Props {
    var label: String
    var value: String
    var danger: Boolean
}

/** One npub/nsec row in the wizard backup box: label, truncated mono value, copy. */
private val KeyLineRow =
    FC<KeyLineRowProps> { props ->
        val (copied, setCopied) = useState { false }
        div {
            className = ClassName("keyline")
            span {
                className = ClassName(if (props.danger) "keyline-label danger" else "keyline-label")
                +props.label
            }
            code {
                className = ClassName("keyline-value")
                +props.value
            }
            button {
                className = ClassName("keyline-copy")
                title = if (copied) "Copied" else "Copy ${props.label}"
                onClick = {
                    if (props.value.isNotBlank()) copyToClipboard(props.value)
                    setCopied(true)
                    launchApp {
                        delay(1_500)
                        setCopied(false)
                    }
                }
                if (copied) icon(Ic.Check) else icon(Ic.ContentCopy)
            }
        }
    }
