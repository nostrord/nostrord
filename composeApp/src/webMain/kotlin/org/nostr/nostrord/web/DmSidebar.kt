package org.nostr.nostrord.web

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.searchInput
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface DmSidebarProps : Props {
    var activePubkey: String?
    var onOpenConversation: (DmRoute) -> Unit
}

/**
 * Second column on the DM section (prototype DMSidebar, obelisk-style): header with
 * search, Follows / Others tabs and the conversation list. There is no DM backend
 * yet, so the lists are empty; searching a valid npub/hex already opens that
 * conversation. Mirrors the Compose ui/components/layout/DmSidebar.
 */
val DmSidebar =
    FC<DmSidebarProps> { props ->
        val (tab, setTab) = useState { 0 }
        val (searchOpen, setSearchOpen) = useState { false }
        val (query, setQuery) = useState { "" }
        val parsed = Nip19.parsePubkeyInput(query) as? Nip19.PubkeyParse.Ok

        div {
            className = ClassName("dm-side-header")
            span {
                className = ClassName("dm-side-title")
                +"Direct messages"
            }
            button {
                className = ClassName(if (searchOpen) "icon-btn active" else "icon-btn")
                title = "Search"
                onClick = {
                    setSearchOpen(!searchOpen)
                    setQuery("")
                }
                icon(Ic.Search)
            }
        }

        div {
            className = ClassName("dm-side-body")
            if (searchOpen) {
                div {
                    className = ClassName("dm-search")
                    searchInput(
                        placeholder = "Search by name, nip-05, npub or hex",
                        value = query,
                        onChange = { setQuery(it) },
                        compact = true,
                        autoFocus = true,
                        onClose = {
                            setSearchOpen(false)
                            setQuery("")
                        },
                    )
                }
                // A pasted npub/hex starts that conversation right away.
                if (parsed != null) {
                    div {
                        className = ClassName("dm-section-label")
                        +"Start a conversation"
                    }
                    button {
                        className = ClassName("dm-convo-row")
                        onClick = { props.onOpenConversation(DmRoute(parsed.hex)) }
                        WebAvatar {
                            seed = parsed.hex
                            name = parsed.hex.take(8)
                            cls = "dm-convo-avatar"
                        }
                        span {
                            className = ClassName("dm-convo-name")
                            +runCatching { Nip19.encodeNpub(parsed.hex) }.getOrDefault(parsed.hex)
                        }
                    }
                }
            }

            div {
                className = ClassName("dm-tabs")
                listOf("Follows", "Others").forEachIndexed { index, label ->
                    button {
                        className = ClassName(if (tab == index) "dm-tab selected" else "dm-tab")
                        onClick = { setTab(index) }
                        +label
                    }
                }
            }

            DmConversationList {
                activePubkey = props.activePubkey
                onOpenConversation = props.onOpenConversation
                onStartConversation = { setSearchOpen(true) }
            }
        }
    }

external interface DmConversationListProps : Props {
    var activePubkey: String?
    var onOpenConversation: (DmRoute) -> Unit

    /** Optional empty-state CTA (the sidebar opens search); omit to hide it. */
    var onStartConversation: (() -> Unit)?
}

/**
 * The DM conversation list (`.dm-convo-list`) plus its empty state, shared by the desktop
 * [DmSidebar] and the mobile DM page (which has no visible sidebar).
 */
val DmConversationList =
    FC<DmConversationListProps> { props ->
        val dmVm = useViewModel { DmViewModel(AppModule.nostrRepository) }
        val conversations = useStateFlow(dmVm.conversations)
        val userMetadata = useStateFlow(dmVm.userMetadata)
        val unreadByPeer = useStateFlow(dmVm.unreadByPeer)

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (runCatching { Nip19.encodeNpub(pubkey) }.getOrDefault(pubkey).take(12) + "…")
        }

        if (conversations.isEmpty()) {
            div {
                className = ClassName("dm-empty")
                div {
                    className = ClassName("dm-empty-tile")
                    +"✉️"
                }
                div {
                    className = ClassName("dm-empty-title")
                    +"No messages yet"
                }
                div {
                    className = ClassName("dm-empty-text")
                    +"Your direct messages are end-to-end encrypted. Start one with someone you follow."
                }
                props.onStartConversation?.let { start ->
                    button {
                        className = ClassName("dm-empty-cta")
                        onClick = { start() }
                        +"Start a conversation"
                    }
                }
            }
        } else {
            div {
                className = ClassName("dm-convo-list")
                conversations.forEach { c ->
                    val peer = c.peerPubkey
                    button {
                        key = peer
                        className = ClassName(if (props.activePubkey == peer) "dm-convo-row selected" else "dm-convo-row")
                        onClick = { props.onOpenConversation(DmRoute(peer)) }
                        WebAvatar {
                            url = userMetadata[peer]?.picture
                            seed = peer
                            name = nameOf(peer)
                            cls = "dm-convo-avatar"
                        }
                        span {
                            className = ClassName("dm-convo-meta")
                            span {
                                className = ClassName("dm-convo-name")
                                +nameOf(peer)
                            }
                            span {
                                className = ClassName("dm-convo-last")
                                +c.lastMessage
                            }
                        }
                        val unread = unreadByPeer[peer] ?: 0
                        if (unread > 0) {
                            span {
                                className = ClassName("dm-convo-unread")
                                +unread.toString()
                            }
                        }
                    }
                }
            }
        }
    }
