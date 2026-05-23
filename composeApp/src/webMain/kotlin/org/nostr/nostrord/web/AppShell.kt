package org.nostr.nostrord.web

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.GroupRow
import org.nostr.nostrord.web.navigation.currentScreen
import org.nostr.nostrord.web.navigation.navigate
import org.nostr.nostrord.web.screens.BackupScreen
import org.nostr.nostrord.web.screens.DiscoverScreen
import org.nostr.nostrord.web.screens.GroupScreen
import org.nostr.nostrord.web.screens.NotificationsScreen
import org.nostr.nostrord.web.screens.ProfileScreen
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

/**
 * Persistent two-pane app shell (signed-in): a sidebar (brand, discover, joined groups,
 * account) that stays put while the content pane swaps between the discover view and a
 * group chat based on the in-memory route. Mirrors the Compose app's sidebar + content
 * layout instead of the earlier full-page screen swaps.
 */
val AppShell =
    FC<Props> {
        val screen = useStateFlow(currentScreen)
        val (drawerOpen, setDrawerOpen) = useState { false }
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
            Sidebar { onNavigate = { setDrawerOpen(false) } }
            div {
                className = ClassName("content")
                when (screen) {
                    is Screen.Group ->
                        GroupScreen {
                            groupId = screen.groupId
                            groupName = screen.groupName
                        }
                    Screen.Profile -> ProfileScreen()
                    Screen.BackupPrivateKey -> BackupScreen()
                    Screen.Notifications -> NotificationsScreen()
                    else -> DiscoverScreen()
                }
            }
        }
    }

private external interface SidebarProps : Props {
    var onNavigate: () -> Unit
}

private val Sidebar =
    FC<SidebarProps> { props ->
        val groupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val joinedByRelay = useStateFlow(AppModule.nostrRepository.joinedGroupsByRelay)
        val screen = useStateFlow(currentScreen)

        val joined: List<GroupMetadata> =
            buildList {
                joinedByRelay.forEach { (relay, ids) ->
                    val metas = groupsByRelay[relay].orEmpty().associateBy { it.id }
                    ids.forEach { id ->
                        add(metas[id] ?: GroupMetadata(id, null, null, null, false, false))
                    }
                }
            }
        val selectedGroupId = (screen as? Screen.Group)?.groupId

        div {
            className = ClassName("sidebar")
            div {
                className = ClassName("sidebar-brand")
                +"Nostrord"
            }
            button {
                className = ClassName(if (screen == Screen.Home) "sidebar-nav sidebar-nav-active" else "sidebar-nav")
                onClick = {
                    navigate(Screen.Home)
                    props.onNavigate()
                }
                +"＋  Discover groups"
            }
            button {
                className = ClassName(if (screen == Screen.Notifications) "sidebar-nav sidebar-nav-active" else "sidebar-nav")
                onClick = {
                    navigate(Screen.Notifications)
                    props.onNavigate()
                }
                +"🔔  Notifications"
            }
            div {
                className = ClassName("sidebar-groups")
                if (joined.isEmpty()) {
                    div {
                        className = ClassName("sidebar-empty")
                        +"No groups yet"
                    }
                } else {
                    joined.forEach { group ->
                        GroupRow {
                            key = group.id
                            this.group = group
                            actionLabel = null
                            selected = group.id == selectedGroupId
                            onActivate = {
                                navigate(Screen.Group(group.id, group.name))
                                props.onNavigate()
                            }
                        }
                    }
                }
            }
            AccountFooter()
        }
    }

private val AccountFooter =
    FC<Props> {
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val (menuOpen, setMenuOpen) = useState { false }
        val (showAdd, setShowAdd) = useState { false }
        val (addInput, setAddInput) = useState { "" }

        useEffect(activeId) {
            activeId?.let { AppModule.nostrRepository.requestUserMetadata(setOf(it)) }
        }

        val meta = activeId?.let { userMetadata[it] }
        val name =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: "Anonymous"

        fun labelFor(pubkey: String, fallback: String): String {
            val m = userMetadata[pubkey]
            return m?.displayName?.takeIf { it.isNotBlank() }
                ?: m?.name?.takeIf { it.isNotBlank() }
                ?: fallback
        }

        fun addAccount() {
            val entered = addInput.trim()
            val hex =
                if (entered.startsWith("nsec1")) {
                    (Nip19.decode(entered) as? Nip19.Entity.Nsec)?.privkey
                } else {
                    entered
                }
            if (hex == null || hex.length != 64) return
            val pubkey = KeyPair.fromPrivateKeyHex(hex).publicKeyHex
            AppModule.accountManager.addLocalAccount(hex, null)
            setAddInput("")
            setShowAdd(false)
            setMenuOpen(false)
            launchApp { AppModule.accountManager.switchAccount(pubkey) }
        }

        div {
            className = ClassName("account-footer-wrap")

            if (menuOpen) {
                div {
                    className = ClassName("account-menu")
                    accounts.forEach { account ->
                        div {
                            key = account.id
                            className = ClassName(if (account.id == activeId) "account-menu-item active" else "account-menu-item")
                            span {
                                className = ClassName("account-menu-name")
                                onClick = {
                                    setMenuOpen(false)
                                    launchApp { AppModule.accountManager.switchAccount(account.id) }
                                }
                                +labelFor(account.pubkey, account.label)
                            }
                            if (accounts.size > 1) {
                                button {
                                    className = ClassName("account-menu-remove")
                                    onClick = { launchApp { AppModule.accountManager.removeAccount(account.id) } }
                                    +"×"
                                }
                            }
                        }
                    }

                    if (showAdd) {
                        input {
                            className = ClassName("account-add-input")
                            placeholder = "nsec1… or hex"
                            value = addInput
                            onChange = { event -> setAddInput(event.currentTarget.value) }
                        }
                        button {
                            className = ClassName("account-menu-action")
                            onClick = { addAccount() }
                            +"Add"
                        }
                    } else {
                        button {
                            className = ClassName("account-menu-action")
                            onClick = { setShowAdd(true) }
                            +"＋ Add account"
                        }
                    }

                    button {
                        className = ClassName("account-menu-action")
                        onClick = {
                            setMenuOpen(false)
                            navigate(Screen.Profile)
                        }
                        +"Profile & settings"
                    }
                    button {
                        className = ClassName("account-menu-action")
                        onClick = { AppModule.authManager.logout() }
                        +"Log out"
                    }
                }
            }

            div {
                className = ClassName("account-footer")
                div {
                    className = ClassName("account-info")
                    onClick = { setMenuOpen(!menuOpen) }
                    val picture = meta?.picture
                    if (!picture.isNullOrBlank()) {
                        img {
                            className = ClassName("group-avatar")
                            src = picture
                            alt = name
                        }
                    } else {
                        div {
                            className = ClassName("group-avatar group-avatar-fallback")
                            +name.take(1).uppercase()
                        }
                    }
                    div {
                        className = ClassName("group-meta")
                        span {
                            className = ClassName("group-name")
                            +name
                        }
                        span {
                            className = ClassName("group-about")
                            +(if (accounts.size > 1) "${accounts.size} accounts" else "Account menu")
                        }
                    }
                }
                button {
                    onClick = { setMenuOpen(!menuOpen) }
                    +"⌄"
                }
            }
        }
    }
