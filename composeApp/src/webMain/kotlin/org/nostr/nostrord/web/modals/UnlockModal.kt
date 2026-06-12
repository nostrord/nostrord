package org.nostr.nostrord.web.modals

import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
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
import web.html.password

external interface UnlockModalProps : Props {
    var account: Account
}

/**
 * Startup unlock for a NIP-49 password-protected account (web counterpart of the
 * Compose UnlockAccountDialog): only the ncryptsec is on disk, so session restore
 * stops until the user enters the password or dismisses to the regular login.
 * Rendered by WebApp whenever `pendingUnlockAccount` is set; a successful unlock
 * logs in and clears it.
 */
val UnlockModal =
    FC<UnlockModalProps> { props ->
        val vm = useViewModel { LoginViewModel(AppModule.nostrRepository) }
        val (password, setPassword) = useState { "" }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        fun unlock() {
            if (password.isEmpty() || busy) return
            setBusy(true)
            setError(null)
            vm.unlockWithPassword(password) { result ->
                setBusy(false)
                result.exceptionOrNull()?.let { setError(it.message ?: "Wrong password") }
            }
        }

        div {
            className = ClassName("modal-overlay")
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }
                div {
                    className = ClassName("modal-title")
                    +"Unlock ${props.account.label}"
                }
                div {
                    className = ClassName("modal-subtitle tight")
                    +"This account's key is encrypted on this device (NIP-49). Enter the password to unlock it."
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
                        value = password
                        autoFocus = true
                        onChange = { event ->
                            setPassword(event.currentTarget.value)
                            setError(null)
                        }
                        onKeyDown = { event -> if (event.key == "Enter") unlock() }
                    }
                }
                error?.let {
                    p {
                        className = ClassName("login-error")
                        +it
                    }
                }
                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        disabled = busy
                        onClick = { vm.dismissUnlock() }
                        +"Not now"
                    }
                    button {
                        className = ClassName("btn-primary")
                        disabled = password.isEmpty() || busy
                        onClick = { unlock() }
                        if (busy) {
                            span { className = ClassName("btn-spinner") }
                        }
                        +(if (busy) "Unlocking…" else "Unlock")
                    }
                }
            }
        }
    }
