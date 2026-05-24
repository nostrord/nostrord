package org.nostr.nostrord.web

import kotlinx.browser.window
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.web.auth.WebAuth
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.modals.AddRelayModal
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.JoinGroupModal
import org.nostr.nostrord.web.screens.AddAccountSheet
import org.nostr.nostrord.web.screens.ChatScreen
import org.nostr.nostrord.web.screens.NotificationsScreen
import org.nostr.nostrord.web.screens.OnboardingScreen
import org.nostr.nostrord.web.screens.SettingsScreen
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

private fun relayDisplayName(url: String, names: Map<String, String>): String = names[url]?.takeIf { it.isNotBlank() }
    ?: url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

private fun authMethodLabel(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "Private key"
    AuthMethod.BUNKER -> "Bunker (NIP-46)"
    AuthMethod.NIP07 -> "Browser extension (NIP-07)"
}

/**
 * Logged-in shell — real data: server rail (relays from kind:10009 + group-tag relays),
 * groups sidebar (joined groups for the active relay), content, and the account menu
 * (real accounts). Switching a relay / opening a group / adding a relay all hit the
 * repository. Chat body and modal submits are wired separately.
 */
val AppShell =
    FC<Props> {
        val repo = AppModule.nostrRepository

        val kind10009 = useStateFlow(repo.kind10009Relays)
        val groupTagRelays = useStateFlow(repo.groupTagRelays)
        val currentRelayUrl = useStateFlow(repo.currentRelayUrl)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val joinedByRelay = useStateFlow(repo.joinedGroupsByRelay)
        val unreadCounts = useStateFlow(repo.unreadCounts)
        val unreadByRelay = useStateFlow(repo.unreadByRelay)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val userMetadata = useStateFlow(repo.userMetadata)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val activeAccountId = useStateFlow(AppModule.accountStore.activeId)

        val relayNames = relayMetadata.mapValues { it.value.name ?: "" }
        val relayList =
            (kind10009.toList() + groupTagRelays.toList() + currentRelayUrl)
                .filter { it.isNotBlank() }
                .distinct()
        val hasRelays = relayList.isNotEmpty()
        val activeRelay = currentRelayUrl.takeIf { it in relayList } ?: relayList.firstOrNull() ?: ""

        val groups = groupsByRelay[activeRelay].orEmpty()
        val joinedIds = joinedByRelay[activeRelay].orEmpty()
        val myGroups = groups.filter { it.id in joinedIds }

        val activePubkey = repo.getPublicKey()
        val meMetadata = activePubkey?.let { userMetadata[it] }
        val meName =
            meMetadata?.displayName?.takeIf { it.isNotBlank() }
                ?: meMetadata?.name?.takeIf { it.isNotBlank() }
                ?: accounts.firstOrNull { it.pubkey == activeAccountId }?.label
                ?: "Account"
        val meNpub = activePubkey?.let { Nip19.encodeNpub(it) } ?: ""

        val (drawerOpen, setDrawerOpen) = useState { false }
        val (selectedGroupId, setSelectedGroupId) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        val (copied, setCopied) = useState { false }
        val (modal, setModal) = useState<String?> { null }
        val (relayTab, setRelayTab) = useState { 0 }
        val (settingsOpen, setSettingsOpen) = useState { false }
        val (notificationsOpen, setNotificationsOpen) = useState { false }
        val (addAccountOpen, setAddAccountOpen) = useState { false }

        val selectedGroup: GroupMetadata? = groups.firstOrNull { it.id == selectedGroupId }

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
                        relayList.forEach { relay ->
                            val isActive = relay == activeRelay && !notificationsOpen
                            val unread = unreadByRelay[relay] ?: 0
                            div {
                                key = relay
                                className = ClassName(if (isActive) "rail-item active" else "rail-item")
                                onClick = {
                                    setNotificationsOpen(false)
                                    setSelectedGroupId(null)
                                    if (relay != currentRelayUrl) launchApp { repo.switchRelay(relay) }
                                }
                                div {
                                    className = ClassName("avatar-tile rail-icon avatar-fallback")
                                    +relayDisplayName(relay, relayNames).take(1).uppercase()
                                }
                                if (unread > 0) {
                                    span {
                                        className = ClassName("rail-badge")
                                        +unread.toString()
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
                        className = ClassName(if (notificationsOpen) "rail-item active" else "rail-item")
                        onClick = { setNotificationsOpen(true) }
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
                            +meName.take(1).uppercase()
                        }
                    }
                }

                // Groups sidebar (hidden while the notifications screen is open)
                div {
                    className = ClassName(if (notificationsOpen) "groups-sidebar hidden" else "groups-sidebar")
                    div {
                        className = ClassName("sidebar-header")
                        +(if (hasRelays) relayDisplayName(activeRelay, relayNames) else "No Relay")
                    }
                    if (hasRelays) {
                        div {
                            className = ClassName("sidebar-scroll")
                            div {
                                className = ClassName("sidebar-section-title")
                                +"My groups"
                            }
                            myGroups.forEach { group ->
                                val unread = unreadCounts[group.id] ?: 0
                                div {
                                    key = group.id
                                    className = ClassName(if (selectedGroupId == group.id) "sidebar-group selected" else "sidebar-group")
                                    onClick = {
                                        setSelectedGroupId(group.id)
                                        setNotificationsOpen(false)
                                        setDrawerOpen(false)
                                    }
                                    div {
                                        className = ClassName("avatar-tile group-icon-sm avatar-fallback")
                                        +(group.name ?: group.id).take(1).uppercase()
                                    }
                                    span {
                                        className = ClassName("sidebar-group-name")
                                        +(group.name ?: group.id)
                                    }
                                    if (unread > 0) {
                                        span {
                                            className = ClassName("sidebar-unread")
                                            +unread.toString()
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
                when {
                    notificationsOpen -> NotificationsScreen()
                    !hasRelays ->
                        OnboardingScreen {
                            onAddRelay = { openRelay(0) }
                            onAddRelayCustomUrl = { openRelay(1) }
                        }
                    selectedGroup != null ->
                        ChatScreen {
                            group = selectedGroup
                            onLeave = { setSelectedGroupId(null) }
                        }
                    else ->
                        div {
                            className = ClassName("home-welcome")
                            div {
                                className = ClassName("home-welcome-inner")
                                h1 { +"Nostrord" }
                                p { +"Select a group from the sidebar to start chatting." }
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
                                +meName.take(1).uppercase()
                            }
                            div {
                                className = ClassName("me-header-meta")
                                div {
                                    className = ClassName("me-name")
                                    +meName
                                }
                                div {
                                    className = ClassName("me-npub")
                                    onClick = {
                                        val clip = window.navigator.asDynamic().clipboard
                                        if (clip != null && meNpub.isNotBlank()) clip.writeText(meNpub)
                                        setCopied(true)
                                    }
                                    span { +(if (meNpub.isNotBlank()) meNpub.take(16) + "…" else "") }
                                    span {
                                        className = ClassName("me-npub-copy")
                                        +(if (copied) "✓" else "⧉")
                                    }
                                }
                            }
                        }
                        div { className = ClassName("me-divider") }

                        accounts.forEach { account ->
                            val meta = userMetadata[account.pubkey]
                            val name =
                                meta?.displayName?.takeIf { it.isNotBlank() }
                                    ?: meta?.name?.takeIf { it.isNotBlank() }
                                    ?: account.label
                            val isActiveAccount = account.pubkey == activeAccountId
                            div {
                                key = account.pubkey
                                className = ClassName("me-account-row")
                                onClick = {
                                    if (!isActiveAccount) {
                                        setMenuOpen(false)
                                        launchApp { AppModule.accountManager.switchAccount(account.pubkey) }
                                    }
                                }
                                div {
                                    className = ClassName("avatar-tile me-avatar-sm avatar-fallback")
                                    +name.take(1).uppercase()
                                }
                                div {
                                    className = ClassName("me-account-meta")
                                    div {
                                        className = ClassName("me-account-name")
                                        +name
                                    }
                                    div {
                                        className = ClassName("me-account-method")
                                        +authMethodLabel(account.authMethod)
                                    }
                                }
                                if (isActiveAccount) {
                                    span {
                                        className = ClassName("me-check")
                                        +"✓"
                                    }
                                }
                                if (accounts.size > 1) {
                                    button {
                                        className = ClassName("me-delete")
                                        onClick = {
                                            it.stopPropagation()
                                            launchApp { AppModule.accountManager.removeAccount(account.pubkey) }
                                        }
                                        +"🗑"
                                    }
                                }
                            }
                        }
                        div { className = ClassName("me-divider") }

                        div {
                            className = ClassName("me-action")
                            onClick = {
                                setMenuOpen(false)
                                setAddAccountOpen(true)
                            }
                            span {
                                className = ClassName("me-action-icon")
                                +"＋"
                            }
                            span { +"Add account" }
                        }
                        div { className = ClassName("me-divider") }
                        div {
                            className = ClassName("me-action")
                            onClick = {
                                setMenuOpen(false)
                                setSettingsOpen(true)
                            }
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
                                launchApp { WebAuth.logout() }
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

            if (settingsOpen) {
                SettingsScreen { onClose = { setSettingsOpen(false) } }
            }

            if (addAccountOpen) {
                AddAccountSheet { onClose = { setAddAccountOpen(false) } }
            }

            when (modal) {
                "create" -> CreateGroupModal { onClose = { setModal(null) } }
                "join" -> JoinGroupModal { onClose = { setModal(null) } }
                "relay" ->
                    AddRelayModal {
                        initialTab = relayTab
                        onClose = { setModal(null) }
                        onAdded = { url ->
                            launchApp { repo.switchRelay(url) }
                            setModal(null)
                        }
                    }
            }
        }
    }
