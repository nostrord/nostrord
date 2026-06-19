package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.web.DmConversationList
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.HTMLTextAreaElement

external interface DmPageProps : Props {
    /** Peer of the open conversation; null shows the section's empty hero. */
    var pubkey: String?
    var onOpenProfile: (UserRoute) -> Unit
    var onOpenConversation: (DmRoute) -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * Direct-message conversation page (prototype DirectMessage, NIP-17 style). The
 * message backend does not exist yet: the conversation intro and the composer are
 * in place, with sending disabled until NIP-17 lands. Mirrors the Compose
 * ui/screens/dm/DmPageScreen.
 */
val DmPage =
    FC<DmPageProps> { props ->
        val pubkey = props.pubkey
        if (pubkey == null) {
            div {
                className = ClassName("dm-page")
                div {
                    className = ClassName("page-header")
                    button {
                        className = ClassName("icon-btn frame-menu-btn")
                        onClick = { props.onOpenDrawer() }
                        icon(Ic.Menu)
                    }
                    icon(Ic.Mail)
                    span {
                        className = ClassName("page-header-title")
                        +"Direct messages"
                    }
                }
                // Desktop: the conversation list lives in the sidebar, so the main area is an
                // empty hero. Mobile has no visible sidebar, so it shows the list here instead
                // (the two are toggled by CSS).
                div {
                    className = ClassName("dm-hero")
                    div {
                        className = ClassName("dm-hero-tile")
                        +"✉️"
                    }
                    h2 { +"Your direct messages" }
                    p { +"Pick a conversation on the side or start a new one with someone you follow." }
                }
                div {
                    className = ClassName("dm-page-convos")
                    DmConversationList {
                        activePubkey = null
                        onOpenConversation = { props.onOpenConversation(it) }
                        onStartConversation = null
                    }
                }
            }
            return@FC
        }

        val vm = useViewModel("dm-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
        val metadata = useStateFlow(vm.metadata)
        val dmVm = useViewModel { DmViewModel(AppModule.nostrRepository) }
        val messages = useStateFlow(dmVm.messagesByPeer)[pubkey].orEmpty()
        // Mark the conversation read while it is open (and as new messages stream in).
        useEffect(pubkey, messages.size) {
            if (messages.isNotEmpty()) dmVm.markRead(pubkey)
        }
        val (text, setText) = useState { "" }
        val send = {
            if (text.isNotBlank()) {
                dmVm.send(pubkey, text)
                setText("")
            }
        }
        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        div {
            className = ClassName("dm-page")
            div {
                className = ClassName("page-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                button {
                    className = ClassName("dm-peer")
                    onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                    WebAvatar {
                        url = metadata?.picture
                        seed = pubkey
                        this.name = name
                        cls = "dm-peer-avatar"
                    }
                    span {
                        className = ClassName("page-header-title")
                        +name
                    }
                }
                span {
                    className = ClassName("dm-chip")
                    +"DM · encrypted"
                }
            }

            div {
                className = ClassName("dm-messages")
                div {
                    className = ClassName("dm-intro")
                    WebAvatar {
                        url = metadata?.picture
                        seed = pubkey
                        this.name = name
                        cls = "dm-intro-avatar"
                    }
                    div {
                        className = ClassName("dm-intro-name")
                        +name
                    }
                    div {
                        className = ClassName("dm-intro-text")
                        +"Beginning of your direct conversation with $name. Direct messages are encrypted (NIP-17)."
                    }
                }
                messages.forEach { m ->
                    div {
                        key = m.id
                        className = ClassName(if (m.mine) "dm-msg mine" else "dm-msg")
                        div {
                            className = ClassName("dm-bubble")
                            +m.content
                        }
                    }
                }
            }

            div {
                className = ClassName("dm-composer-wrap")
                div {
                    className = ClassName("dm-composer")
                    button {
                        className = ClassName("dm-composer-btn")
                        title = "Attach"
                        disabled = true
                        icon(Ic.AttachFile)
                    }
                    textarea {
                        rows = 1
                        value = text
                        placeholder = "Message $name"
                        onChange = { setText((it.target as HTMLTextAreaElement).value) }
                        onKeyDown = { e ->
                            if (e.key == "Enter" && !e.shiftKey) {
                                e.preventDefault()
                                send()
                            }
                        }
                    }
                    button {
                        className = ClassName("dm-composer-btn")
                        title = "Emoji"
                        disabled = true
                        icon(Ic.EmojiEmotions)
                    }
                    button {
                        className = ClassName("dm-composer-btn send")
                        title = "Send"
                        disabled = text.isBlank()
                        onClick = { send() }
                        icon(Ic.Send)
                    }
                }
            }
        }
    }
