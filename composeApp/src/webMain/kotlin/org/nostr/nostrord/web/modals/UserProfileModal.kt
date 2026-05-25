package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.WebZapController
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.ClassName

external interface UserProfileModalProps : Props {
    var pubkey: String
    var onClose: () -> Unit
}

/**
 * User profile modal — real data port of the Compose UserProfileModal: banner + avatar,
 * display name + @handle, ABOUT, PUBLIC KEY (npub) with copy, and NIP-05, read from the
 * live `userMetadata` (fetched on open). Copy is stubbed.
 */
val UserProfileModal =
    FC<UserProfileModalProps> { props ->
        val pubkey = props.pubkey
        val meta = useStateFlow(AppModule.nostrRepository.userMetadata)[pubkey]
        val npub = Nip19.encodeNpub(pubkey)
        val name =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (npub.take(12) + "…")
        val handle = meta?.name?.takeIf { it.isNotBlank() }
        val canZap = !meta?.lud16.isNullOrBlank() || !meta?.lud06.isNullOrBlank()

        useEffect(pubkey) {
            launchApp { AppModule.nostrRepository.requestUserMetadata(setOf(pubkey)) }
        }
        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card profile-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("profile-banner")
                    if (!meta?.banner.isNullOrBlank()) {
                        img {
                            className = ClassName("cover-img")
                            src = meta?.banner ?: ""
                            alt = ""
                        }
                    }
                    button {
                        className = ClassName("info-cover-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                    WebAvatar {
                        url = meta?.picture
                        seed = pubkey
                        this.name = name
                        cls = "profile-avatar"
                    }
                }

                div {
                    className = ClassName("info-content profile-content")
                    div {
                        className = ClassName("profile-name-row")
                        div {
                            className = ClassName("profile-name-col")
                            div {
                                className = ClassName("info-name")
                                +name
                            }
                            if (handle != null && handle != name) {
                                div {
                                    className = ClassName("profile-handle")
                                    +"@$handle"
                                }
                            }
                        }
                        if (canZap) {
                            button {
                                className = ClassName("profile-zap")
                                onClick = {
                                    WebZapController.request(pubkey)
                                    props.onClose()
                                }
                                icon(Ic.Bolt)
                                +"Zap"
                            }
                        }
                    }

                    if (!meta?.about.isNullOrBlank()) {
                        div {
                            className = ClassName("settings-section-head")
                            +"ABOUT"
                        }
                        div {
                            className = ClassName("info-about")
                            +(meta?.about ?: "")
                        }
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
                            icon(Ic.ContentCopy)
                        }
                    }

                    if (!meta?.nip05.isNullOrBlank()) {
                        div {
                            className = ClassName("settings-section-head")
                            +"NIP-05"
                        }
                        div {
                            className = ClassName("info-about")
                            +(meta?.nip05 ?: "")
                        }
                    }
                }
            }
        }
    }
