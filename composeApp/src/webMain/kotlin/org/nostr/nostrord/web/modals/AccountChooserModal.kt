package org.nostr.nostrord.web.modals

import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface AccountChooserModalProps : Props {
    /** Active account being signed out — must be the one currently active. */
    var signOutAccountId: String

    /** Cancel pressed (or backdrop / Esc) — keeps the current account active. */
    var onDismiss: () -> Unit

    /** "Add a new login" — caller opens AddAccountSheet and defers the wipe. */
    var onNewLogin: (signOutAccountId: String) -> Unit
}

/**
 * Account chooser shown when signing out of the active account while other accounts
 * are still signed in. Lists remaining accounts (tap to continue) and a "Add a new
 * login" action. Validate-before-teardown is handled by
 * [org.nostr.nostrord.auth.AccountManager.removeAndSwitch], called only after the
 * user picks where to go — dismissing leaves the current account active. Real port
 * of the Compose [AccountChooserDialog].
 */
val AccountChooserModal =
    FC<AccountChooserModalProps> { props ->
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        val current = accounts.firstOrNull { it.id == props.signOutAccountId }
        val others = accounts.filter { it.id != props.signOutAccountId }

        // Backfill any missing kind:0 metadata so the rows show real names + avatars.
        useEffect(accounts.size) {
            val missing = accounts.map { it.pubkey }.filter { it !in userMetadata.keys }.toSet()
            if (missing.isNotEmpty()) launchApp { AppModule.nostrRepository.requestUserMetadata(missing) }
        }

        useEscClose { if (!busy) props.onDismiss() }

        // The account being signed out is gone (e.g. removed elsewhere) or no
        // alternatives remain — nothing left to choose, dismiss the chooser.
        useEffect(current?.id, others.size) {
            if (current == null || others.isEmpty()) props.onDismiss()
        }
        if (current == null || others.isEmpty()) return@FC

        val currentLabel = displayLabel(current, userMetadata[current.pubkey])

        div {
            className = ClassName("modal-overlay")
            onClick = { if (!busy) props.onDismiss() }
            div {
                className = ClassName("modal-card chooser-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Choose an account"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"Sign out of \"$currentLabel\" and continue as one of your other accounts, or add a new login."
                        }
                    }
                }

                if (error != null) {
                    div {
                        className = ClassName("chooser-error")
                        +error
                    }
                }

                div {
                    className = ClassName("chooser-list")
                    others.forEach { account ->
                        chooserAccountRow(account, userMetadata[account.pubkey], busy) {
                            if (busy) return@chooserAccountRow
                            setBusy(true)
                            setError(null)
                            launchApp {
                                val r =
                                    AppModule.accountManager
                                        .removeAndSwitch(props.signOutAccountId, account.id)
                                setBusy(false)
                                if (r.isFailure) {
                                    setError(r.exceptionOrNull()?.message ?: "Could not switch account")
                                } else {
                                    props.onDismiss()
                                }
                            }
                        }
                    }
                }

                div {
                    className = ClassName("chooser-divider")
                }

                div {
                    className = ClassName(if (busy) "chooser-action disabled" else "chooser-action")
                    onClick = {
                        if (!busy) props.onNewLogin(props.signOutAccountId)
                    }
                    span {
                        className = ClassName("chooser-action-icon")
                        icon(Ic.PersonAdd)
                    }
                    span { +"Add a new login" }
                }

                div { className = ClassName("chooser-divider") }

                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        disabled = busy
                        onClick = { props.onDismiss() }
                        +"Cancel"
                    }
                }
            }
        }
    }

private fun react.ChildrenBuilder.chooserAccountRow(
    account: Account,
    metadata: org.nostr.nostrord.network.UserMetadata?,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val name = displayLabel(account, metadata)
    div {
        key = account.id
        className = ClassName(if (busy) "chooser-row disabled" else "chooser-row")
        this.onClick = { onClick() }
        WebAvatar {
            url = metadata?.picture?.takeIf { it.isNotBlank() }
            seed = account.pubkey
            this.name = name
            cls = "chooser-avatar"
        }
        div {
            className = ClassName("chooser-meta")
            div {
                className = ClassName("chooser-name")
                +name
            }
            div {
                className = ClassName("chooser-method")
                +authMethodLabel(account.authMethod)
            }
        }
        span {
            className = ClassName("chooser-chevron")
            icon(Ic.ChevronRight)
        }
    }
}

private fun displayLabel(
    account: Account,
    metadata: org.nostr.nostrord.network.UserMetadata?,
): String = metadata?.displayName?.takeIf { it.isNotBlank() }
    ?: metadata?.name?.takeIf { it.isNotBlank() }
    ?: account.label

private fun authMethodLabel(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "Private key"
    AuthMethod.BUNKER -> "Bunker (NIP-46)"
    AuthMethod.NIP07 -> "Browser extension (NIP-07)"
}
