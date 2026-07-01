package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.screens.LoginMethods
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

external interface AddAccountModalProps : Props {
    var onClose: () -> Unit
}

/**
 * Add-account modal: the login page's tabbed credential picker ([LoginMethods]) inside a
 * standard modal card. A successful add warm-swaps to the new account (the active one stays
 * registered) and closes the modal. Mirrors the native AddAccountSheet, but presents the
 * same interface as the login screen rather than a step picker.
 */
val AddAccountModal =
    FC<AddAccountModalProps> { props ->
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val activePubkey = AppModule.nostrRepository.getPublicKey()
        val activeName =
            activePubkey?.let { userMetadata[it] }
                ?.let { it.displayName?.ifBlank { null } ?: it.name?.ifBlank { null } }
                ?: AppModule.accountStore.active?.label
                ?: "your account"

        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Add account"
                        }
                        p {
                            className = ClassName("modal-subtitle")
                            +"You'll keep $activeName signed in."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                LoginMethods {
                    submitLabel = "Add account"
                    busyLabel = "Adding…"
                    onSuccess = { props.onClose() }
                }
            }
        }
    }
