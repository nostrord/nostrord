package org.nostr.nostrord.web.modals

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface UserProfileModalProps : Props {
    var name: String
    var onClose: () -> Unit
}

/**
 * User profile modal — layout-first React port of the Compose UserProfileModal: banner +
 * overlapping avatar, display name + @handle, ABOUT, PUBLIC KEY (npub) with copy, and the
 * NIP-05 identifier. Derives mock fields from the name; copy is stubbed.
 */
val UserProfileModal =
    FC<UserProfileModalProps> { props ->
        val name = props.name
        val handle = name.lowercase()
        val npub = "npub1${handle}q0xq8r5wz3m9v4k2h7t6y8u3w0e5r2t9p4a"
        val nip05 = "$handle@nostr.com"

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card profile-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("profile-banner")
                    button {
                        className = ClassName("info-cover-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                    div {
                        className = ClassName("avatar-tile profile-avatar avatar-fallback")
                        +name.take(1).uppercase()
                    }
                }

                div {
                    className = ClassName("info-content profile-content")
                    div {
                        className = ClassName("info-name")
                        +name
                    }
                    div {
                        className = ClassName("profile-handle")
                        +"@$handle"
                    }

                    div {
                        className = ClassName("settings-section-head")
                        +"ABOUT"
                    }
                    div {
                        className = ClassName("info-about")
                        +"Nostr enthusiast building on NIP-29 groups."
                    }

                    div {
                        className = ClassName("settings-section-head")
                        +"PUBLIC KEY"
                    }
                    div {
                        className = ClassName("info-id-row")
                        span {
                            className = ClassName("info-id")
                            +npub
                        }
                        button {
                            className = ClassName("info-copy")
                            +"⧉"
                        }
                    }

                    div {
                        className = ClassName("settings-section-head")
                        +"NIP-05"
                    }
                    div {
                        className = ClassName("info-about")
                        +nip05
                    }
                }
            }
        }
    }
