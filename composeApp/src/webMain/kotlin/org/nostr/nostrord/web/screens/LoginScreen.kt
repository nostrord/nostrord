package org.nostr.nostrord.web.screens

import org.nostr.nostrord.nostr.Nip07
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

/**
 * Login screen — the brand header plus the shared [LoginMethods] tab picker (private key +
 * generate, NIP-46 bunker QR/URL, NIP-07 extension). A successful login flips
 * repo.isLoggedIn and the auth gate swaps to the shell, so onSuccess is a no-op here.
 */
val LoginScreen =
    FC<Props> {
        val extensionAvailable = Nip07.isAvailable()

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

                    LoginMethods {
                        submitLabel = "Login"
                        busyLabel = "Logging in…"
                        onSuccess = {}
                    }
                }
            }
        }
    }
