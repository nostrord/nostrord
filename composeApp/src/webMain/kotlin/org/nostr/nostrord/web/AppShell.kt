package org.nostr.nostrord.web

import kotlinx.browser.window
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.mock.Mock
import org.nostr.nostrord.web.mock.MockGroup
import org.nostr.nostrord.web.mock.mockAddRelay
import org.nostr.nostrord.web.mock.mockLogout
import org.nostr.nostrord.web.mock.mockRelaysState
import org.nostr.nostrord.web.modals.AddRelayModal
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.JoinGroupModal
import org.nostr.nostrord.web.screens.OnboardingScreen
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

/**
 * Logged-in shell — layout-first React port of the Compose desktop layout: server rail
 * (relays) + groups sidebar + content. Mock data for now; responsive (rail+sidebar
 * collapse into a drawer on narrow screens). Chat/content screens come next.
 */
val AppShell =
    FC<Props> {
        val (drawerOpen, setDrawerOpen) = useState { false }
        val (selectedGroup, setSelectedGroup) = useState<MockGroup?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        val (copied, setCopied) = useState { false }
        val (modal, setModal) = useState<String?> { null }
        val (relayTab, setRelayTab) = useState { 0 }
        val hasRelays = useStateFlow(mockRelaysState)

        // Open the Add-relay modal on a given tab (0 = Suggested, 1 = Custom URL).
        val openRelay: (Int) -> Unit = { tab ->
            setRelayTab(tab)
            setModal("relay")
        }

        div {
            className = ClassName(if (drawerOpen) "layout drawer-open" else "layout")

            button {
                className = ClassName("mobile-menu-btn")
                onClick = { setDrawerOpen(true) }
                +"☰"
            }
            div {
                className = ClassName("sidebar-backdrop")
                onClick = { setDrawerOpen(false) }
            }

            div {
                className = ClassName("nav-panels")

                // Server rail (relays)
                div {
                    className = ClassName("server-rail")
                    div {
                        className = ClassName("rail-scroll")
                        if (hasRelays) {
                            Mock.relays.forEachIndexed { index, relay ->
                                div {
                                    key = relay.url
                                    className = ClassName(if (index == 0) "rail-item active" else "rail-item")
                                    div {
                                        className = ClassName("avatar-tile rail-icon avatar-fallback")
                                        +relay.name.take(1).uppercase()
                                    }
                                    if (relay.unread > 0) {
                                        span {
                                            className = ClassName("rail-badge")
                                            +relay.unread.toString()
                                        }
                                    }
                                }
                            }
                        }
                        div {
                            className = ClassName("rail-item")
                            onClick = { openRelay(0) }
                            div {
                                className = ClassName("avatar-tile rail-icon rail-add")
                                +"+"
                            }
                        }
                    }
                    div {
                        className = ClassName("rail-item")
                        div {
                            className = ClassName("avatar-tile rail-icon rail-bell")
                            +"🔔"
                        }
                    }
                    div {
                        className = ClassName("rail-account")
                        onClick = { setMenuOpen(!menuOpen) }
                        div {
                            className = ClassName("avatar-tile rail-icon avatar-fallback")
                            +Mock.me.name.take(1).uppercase()
                        }
                    }
                }

                // Groups sidebar
                div {
                    className = ClassName("groups-sidebar")
                    div {
                        className = ClassName("sidebar-header")
                        +(if (hasRelays) Mock.activeRelay.name else "No Relay")
                    }
                    if (hasRelays) {
                        div {
                            className = ClassName("sidebar-scroll")
                            div {
                                className = ClassName("sidebar-section-title")
                                +"My groups"
                            }
                            Mock.groups.forEach { group ->
                                div {
                                    key = group.id
                                    className = ClassName(if (selectedGroup?.id == group.id) "sidebar-group selected" else "sidebar-group")
                                    onClick = {
                                        setSelectedGroup(group)
                                        setDrawerOpen(false)
                                    }
                                    div {
                                        className = ClassName("avatar-tile group-icon-sm avatar-fallback")
                                        +group.name.take(1).uppercase()
                                    }
                                    span {
                                        className = ClassName("sidebar-group-name")
                                        +group.name
                                    }
                                    if (group.unread > 0) {
                                        span {
                                            className = ClassName("sidebar-unread")
                                            +group.unread.toString()
                                        }
                                    }
                                }
                            }
                        }
                        div {
                            className = ClassName("sidebar-footer")
                            button {
                                className = ClassName("sidebar-btn-primary")
                                onClick = { setModal("create") }
                                +"Create group"
                            }
                            button {
                                className = ClassName("sidebar-btn-secondary")
                                onClick = { setModal("join") }
                                +"Join group"
                            }
                        }
                    } else {
                        // No relay yet — empty state mirroring the Compose GroupsNavSidebar
                        div {
                            className = ClassName("sidebar-empty")
                            div {
                                className = ClassName("sidebar-empty-hash")
                                +"#"
                            }
                            div {
                                className = ClassName("sidebar-empty-title")
                                +"No groups yet"
                            }
                            div {
                                className = ClassName("sidebar-empty-desc")
                                +"Add a relay first, then you can browse and join groups or create your own."
                            }
                            button {
                                className = ClassName("sidebar-empty-btn")
                                onClick = { openRelay(0) }
                                +"Add a Relay"
                            }
                        }
                    }
                }
            }

            // Content
            div {
                className = ClassName("content")
                if (!hasRelays) {
                    OnboardingScreen {
                        onAddRelay = { openRelay(0) }
                        onAddRelayCustomUrl = { openRelay(1) }
                    }
                } else {
                    div {
                        className = ClassName("home-welcome")
                        div {
                            className = ClassName("home-welcome-inner")
                            if (selectedGroup != null) {
                                h1 { +selectedGroup.name }
                                p { +"Chat view coming next." }
                            } else {
                                h1 { +"Nostrord" }
                                p { +"Select a group from the sidebar to start chatting." }
                            }
                        }
                    }
                }
            }

            if (menuOpen) {
                div {
                    className = ClassName("me-menu-overlay")
                    onClick = { setMenuOpen(false) }
                    div {
                        className = ClassName("me-menu")
                        onClick = { it.stopPropagation() }

                        div {
                            className = ClassName("me-header")
                            div {
                                className = ClassName("avatar-tile me-avatar-lg avatar-fallback")
                                +Mock.me.name.take(1).uppercase()
                            }
                            div {
                                className = ClassName("me-header-meta")
                                div {
                                    className = ClassName("me-name")
                                    +Mock.me.name
                                }
                                div {
                                    className = ClassName("me-npub")
                                    onClick = {
                                        val clip = window.navigator.asDynamic().clipboard
                                        if (clip != null) clip.writeText(Mock.me.npub)
                                        setCopied(true)
                                    }
                                    span { +(Mock.me.npub.take(16) + "…") }
                                    span {
                                        className = ClassName("me-npub-copy")
                                        +(if (copied) "✓" else "⧉")
                                    }
                                }
                            }
                        }
                        div { className = ClassName("me-divider") }

                        Mock.accounts.forEach { account ->
                            div {
                                key = account.pubkey
                                className = ClassName("me-account-row")
                                div {
                                    className = ClassName("avatar-tile me-avatar-sm avatar-fallback")
                                    +account.name.take(1).uppercase()
                                }
                                div {
                                    className = ClassName("me-account-meta")
                                    div {
                                        className = ClassName("me-account-name")
                                        +account.name
                                    }
                                    div {
                                        className = ClassName("me-account-method")
                                        +account.authMethod
                                    }
                                }
                                if (account.active) {
                                    span {
                                        className = ClassName("me-check")
                                        +"✓"
                                    }
                                }
                                button {
                                    className = ClassName("me-delete")
                                    +"🗑"
                                }
                            }
                        }
                        div { className = ClassName("me-divider") }

                        div {
                            className = ClassName("me-action")
                            span {
                                className = ClassName("me-action-icon")
                                +"＋"
                            }
                            span { +"Add account" }
                        }
                        div { className = ClassName("me-divider") }
                        div {
                            className = ClassName("me-action")
                            span {
                                className = ClassName("me-action-icon")
                                +"⚙"
                            }
                            span { +"Settings" }
                        }
                        div { className = ClassName("me-divider") }
                        div {
                            className = ClassName("me-action danger")
                            onClick = {
                                setMenuOpen(false)
                                mockLogout()
                            }
                            span {
                                className = ClassName("me-action-icon")
                                +"⤴"
                            }
                            span { +"Sign out" }
                        }
                    }
                }
            }

            when (modal) {
                "create" -> CreateGroupModal { onClose = { setModal(null) } }
                "join" -> JoinGroupModal { onClose = { setModal(null) } }
                "relay" ->
                    AddRelayModal {
                        initialTab = relayTab
                        onClose = { setModal(null) }
                        onAdded = {
                            mockAddRelay()
                            setModal(null)
                        }
                    }
            }
        }
    }
