package org.nostr.nostrord.web.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.useEffect
import web.cssom.ClassName

private val viewerPubkey = MutableStateFlow<String?>(null)

/** Open the profile modal for [pubkey]. Call from anywhere (chat authors, members, …). */
fun viewProfile(pubkey: String) {
    viewerPubkey.value = pubkey
}

private val viewerPubkeyFlow: StateFlow<String?> = viewerPubkey.asStateFlow()

/**
 * Global "view another user's profile" modal — rendered once at the app root. Opened via
 * [viewProfile]; shows the user's kind:0 metadata (banner, avatar, name, nip05, npub,
 * about, website, lightning address), fetching it if not cached.
 */
val UserProfileModal =
    FC<Props> {
        val pubkey = useStateFlow(viewerPubkeyFlow)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)

        useEffect(pubkey) {
            pubkey?.let { AppModule.nostrRepository.requestUserMetadata(setOf(it)) }
        }

        if (pubkey == null) return@FC

        val meta = userMetadata[pubkey]
        val name =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: "Anonymous"
        val npub = Nip19.encodeNpub(pubkey)

        div {
            className = ClassName("modal-overlay")
            onClick = { viewerPubkey.value = null }
            div {
                className = ClassName("profile-modal")
                onClick = { it.stopPropagation() }

                button {
                    className = ClassName("modal-close profile-close")
                    onClick = { viewerPubkey.value = null }
                    +"×"
                }

                meta?.banner?.takeIf { it.isNotBlank() }?.let { banner ->
                    img {
                        className = ClassName("profile-banner")
                        src = banner
                        alt = ""
                    }
                }

                val picture = meta?.picture
                if (!picture.isNullOrBlank()) {
                    img {
                        className = ClassName("profile-avatar")
                        src = picture
                        alt = ""
                    }
                } else {
                    div {
                        className = ClassName("profile-avatar avatar-fallback")
                        +name.take(1).uppercase()
                    }
                }

                div {
                    className = ClassName("profile-name")
                    +name
                }
                meta?.nip05?.takeIf { it.isNotBlank() }?.let {
                    div {
                        className = ClassName("profile-nip05")
                        +it
                    }
                }
                code {
                    className = ClassName("profile-npub")
                    +(npub.take(24) + "…")
                }
                meta?.about?.takeIf { it.isNotBlank() }?.let {
                    p {
                        className = ClassName("profile-about")
                        +it
                    }
                }
                meta?.website?.takeIf { it.isNotBlank() }?.let { site ->
                    a {
                        className = ClassName("chat-link")
                        href = site
                        +site
                    }
                }
                meta?.lud16?.takeIf { it.isNotBlank() }?.let {
                    div {
                        className = ClassName("profile-lud16")
                        +("⚡ $it")
                    }
                }
            }
        }
    }
