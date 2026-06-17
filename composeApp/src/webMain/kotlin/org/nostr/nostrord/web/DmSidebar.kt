package org.nostr.nostrord.web

import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.DmRoute
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
                        autoFocus = true,
                    )
                    button {
                        className = ClassName("icon-btn")
                        title = "Close"
                        onClick = {
                            setSearchOpen(false)
                            setQuery("")
                        }
                        icon(Ic.Close)
                    }
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
                        +"$label (0)"
                    }
                }
            }

            // Conversation list arrives with the DM backend (NIP-17).
            div {
                className = ClassName("dm-empty")
                div {
                    className = ClassName("dm-empty-text")
                    +"No conversations yet"
                }
                button {
                    className = ClassName("dm-empty-cta")
                    onClick = { setSearchOpen(true) }
                    +"Start a conversation"
                }
            }
        }
    }
