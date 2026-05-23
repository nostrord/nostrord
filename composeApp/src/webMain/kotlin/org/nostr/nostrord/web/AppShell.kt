package org.nostr.nostrord.web

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.UserProfileModal
import org.nostr.nostrord.web.navigation.currentScreen
import org.nostr.nostrord.web.navigation.navigate
import org.nostr.nostrord.web.screens.BackupScreen
import org.nostr.nostrord.web.screens.DiscoverScreen
import org.nostr.nostrord.web.screens.GroupScreen
import org.nostr.nostrord.web.screens.NotificationsScreen
import org.nostr.nostrord.web.screens.ProfileScreen
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

private fun relayHost(url: String): String = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

private fun joinedGroupsFor(
    groupsByRelay: Map<String, List<GroupMetadata>>,
    joinedByRelay: Map<String, Set<String>>,
): List<GroupMetadata> = buildList {
    joinedByRelay.forEach { (relay, ids) ->
        val metas = groupsByRelay[relay].orEmpty().associateBy { it.id }
        ids.forEach { id -> add(metas[id] ?: GroupMetadata(id, null, null, null, false, false)) }
    }
}

/** Centered modal overlay; clicking the backdrop closes it. */
private fun ChildrenBuilder.modal(
    title: String,
    onClose: () -> Unit,
    body: ChildrenBuilder.() -> Unit,
) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onClose() }
        div {
            className = ClassName("modal-box")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-header")
                span { +title }
                button {
                    className = ClassName("modal-close")
                    onClick = { onClose() }
                    +"×"
                }
            }
            body()
        }
    }
}

/** Avatar tile (image or initial fallback) used by the rail and group rows. */
private fun ChildrenBuilder.avatarTile(
    pictureUrl: String?,
    label: String,
    extraClass: String,
) {
    if (!pictureUrl.isNullOrBlank()) {
        img {
            className = ClassName("avatar-tile $extraClass")
            src = pictureUrl
            alt = label
        }
    } else {
        div {
            className = ClassName("avatar-tile avatar-fallback $extraClass")
            +label.take(1).uppercase()
        }
    }
}

/**
 * Persistent desktop shell matching the Compose layout: a 3-column frame of
 * [ServerRail] (relays) + [GroupsSidebar] (groups) + content. On narrow screens the
 * rail+sidebar collapse into a slide-in drawer. Content swaps on the in-memory route.
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
            div {
                className = ClassName("nav-panels")
                ServerRail { onNavigate = { setDrawerOpen(false) } }
                GroupsSidebar { onNavigate = { setDrawerOpen(false) } }
            }

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

            UserProfileModal()
        }
    }

private external interface NavProps : Props {
    var onNavigate: () -> Unit
}

private val SUGGESTED_RELAYS =
    listOf("wss://groups.0xchat.com", "wss://relay.groups.nip29.com", "wss://groups.fiatjaf.com")

private val ServerRail =
    FC<NavProps> { props ->
        val relays = useStateFlow(AppModule.nostrRepository.kind10009Relays)
        val currentRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val unreadByRelay = useStateFlow(AppModule.nostrRepository.unreadByRelay)
        val totalUnread = useStateFlow(AppModule.nostrRepository.totalUnread)
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val screen = useStateFlow(currentScreen)

        val (addRelayOpen, setAddRelayOpen) = useState { false }
        val (relayInput, setRelayInput) = useState { "" }
        val (menuOpen, setMenuOpen) = useState { false }
        val (showAdd, setShowAdd) = useState { false }
        val (addAccountInput, setAddAccountInput) = useState { "" }

        useEffect(activeId) { activeId?.let { AppModule.nostrRepository.requestUserMetadata(setOf(it)) } }

        val meta = activeId?.let { userMetadata[it] }
        val ownName =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: "Anonymous"

        fun connectRelay(url: String) {
            val u = url.trim()
            if (u.isBlank()) return
            launchApp {
                AppModule.nostrRepository.addRelay(u)
                AppModule.nostrRepository.switchRelay(u)
            }
            setAddRelayOpen(false)
            setRelayInput("")
        }

        fun addAccount() {
            val entered = addAccountInput.trim()
            val hex = if (entered.startsWith("nsec1")) (Nip19.decode(entered) as? Nip19.Entity.Nsec)?.privkey else entered
            if (hex == null || hex.length != 64) return
            val pubkey = KeyPair.fromPrivateKeyHex(hex).publicKeyHex
            AppModule.accountManager.addLocalAccount(hex, null)
            setAddAccountInput("")
            setShowAdd(false)
            setMenuOpen(false)
            launchApp { AppModule.accountManager.switchAccount(pubkey) }
        }

        div {
            className = ClassName("server-rail")

            div {
                className = ClassName("rail-scroll")
                relays.forEach { relay ->
                    val unread = unreadByRelay[relay] ?: 0
                    div {
                        key = relay
                        className = ClassName(if (relay == currentRelay) "rail-item active" else "rail-item")
                        onClick = {
                            launchApp { AppModule.nostrRepository.switchRelay(relay) }
                            navigate(Screen.Home)
                            props.onNavigate()
                        }
                        avatarTile(relayMetadata[relay]?.icon, relayHost(relay), "rail-icon")
                        if (unread > 0) {
                            span {
                                className = ClassName("rail-badge")
                                +(if (unread > 99) "99+" else unread.toString())
                            }
                        }
                    }
                }
                div {
                    className = ClassName("rail-item")
                    onClick = { setAddRelayOpen(true) }
                    div {
                        className = ClassName("avatar-tile rail-icon rail-add")
                        +"+"
                    }
                }
            }

            div {
                className = ClassName("rail-item")
                onClick = {
                    navigate(Screen.Notifications)
                    props.onNavigate()
                }
                div {
                    className = ClassName(if (screen == Screen.Notifications) "avatar-tile rail-icon rail-bell active" else "avatar-tile rail-icon rail-bell")
                    +"🔔"
                }
                if (totalUnread > 0) {
                    span {
                        className = ClassName("rail-badge")
                        +(if (totalUnread > 99) "99+" else totalUnread.toString())
                    }
                }
            }

            div {
                className = ClassName("rail-account")
                onClick = { setMenuOpen(!menuOpen) }
                avatarTile(meta?.picture, ownName, "rail-icon")
            }

            if (menuOpen) {
                modal("Account", { setMenuOpen(false) }) {
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
                                val m = userMetadata[account.pubkey]
                                +(m?.displayName?.takeIf { it.isNotBlank() } ?: m?.name?.takeIf { it.isNotBlank() } ?: account.label)
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
                            className = ClassName("modal-input")
                            placeholder = "nsec1… or hex"
                            value = addAccountInput
                            onChange = { event -> setAddAccountInput(event.currentTarget.value) }
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
                            props.onNavigate()
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

            if (addRelayOpen) {
                modal("Connect to a relay", { setAddRelayOpen(false) }) {
                    input {
                        className = ClassName("modal-input")
                        placeholder = "wss://groups…"
                        value = relayInput
                        onChange = { event -> setRelayInput(event.currentTarget.value) }
                    }
                    div {
                        className = ClassName("modal-suggestions")
                        SUGGESTED_RELAYS.forEach { relay ->
                            button {
                                key = relay
                                className = ClassName("chip")
                                onClick = { connectRelay(relay) }
                                +relayHost(relay)
                            }
                        }
                    }
                    button {
                        className = ClassName("modal-primary")
                        disabled = relayInput.isBlank()
                        onClick = { connectRelay(relayInput) }
                        +"Connect"
                    }
                }
            }
        }
    }

private val GroupsSidebar =
    FC<NavProps> { props ->
        val currentRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val groupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val joinedByRelay = useStateFlow(AppModule.nostrRepository.joinedGroupsByRelay)
        val unreadCounts = useStateFlow(AppModule.nostrRepository.unreadCounts)
        val screen = useStateFlow(currentScreen)

        val (createOpen, setCreateOpen) = useState { false }
        val (joinOpen, setJoinOpen) = useState { false }
        val (newName, setNewName) = useState { "" }
        val (newAbout, setNewAbout) = useState { "" }
        val (isPrivate, setIsPrivate) = useState { false }
        val (isClosed, setIsClosed) = useState { false }
        val (joinId, setJoinId) = useState { "" }

        useEffect(currentRelay) {
            if (currentRelay.isNotBlank()) AppModule.nostrRepository.requestFullGroupListForRelay(currentRelay)
        }

        val joinedIds = joinedByRelay.values.flatten().toSet()
        val joined = joinedGroupsFor(groupsByRelay, joinedByRelay)
            .sortedBy { (it.name ?: it.id).lowercase() }
        val others = groupsByRelay.values.flatten()
            .distinctBy { it.id }
            .filter { it.id !in joinedIds }
            .sortedBy { (it.name ?: it.id).lowercase() }
        val selectedGroupId = (screen as? Screen.Group)?.groupId

        fun submitCreate() {
            val name = newName.trim()
            if (name.isBlank() || currentRelay.isBlank()) return
            launchApp {
                AppModule.nostrRepository.createGroup(name, newAbout.trim().ifBlank { null }, currentRelay, isPrivate, isClosed)
            }
            setCreateOpen(false)
            setNewName("")
            setNewAbout("")
            setIsPrivate(false)
            setIsClosed(false)
        }

        fun submitJoin() {
            val id = joinId.trim()
            if (id.isBlank()) return
            launchApp { AppModule.nostrRepository.joinGroup(id) }
            setJoinOpen(false)
            setJoinId("")
        }

        div {
            className = ClassName("groups-sidebar")

            div {
                className = ClassName("sidebar-header")
                +(if (currentRelay.isBlank()) "No relay" else relayHost(currentRelay))
            }

            div {
                className = ClassName("sidebar-scroll")

                if (joined.isNotEmpty()) {
                    div {
                        className = ClassName("sidebar-section-title")
                        +"My groups"
                    }
                    joined.forEach { group ->
                        sidebarGroupRow(group, group.id == selectedGroupId, unreadCounts[group.id] ?: 0) {
                            navigate(Screen.Group(group.id, group.name))
                            props.onNavigate()
                        }
                    }
                }

                if (others.isNotEmpty()) {
                    div {
                        className = ClassName("sidebar-section-title")
                        +"Other groups"
                    }
                    others.take(50).forEach { group ->
                        sidebarGroupRow(group, false, 0) {
                            launchApp { AppModule.nostrRepository.joinGroup(group.id) }
                        }
                    }
                }

                if (joined.isEmpty() && others.isEmpty()) {
                    div {
                        className = ClassName("sidebar-empty")
                        +(if (currentRelay.isBlank()) "Connect to a relay (+) to see groups." else "No groups on this relay yet.")
                    }
                }
            }

            div {
                className = ClassName("sidebar-footer")
                button {
                    className = ClassName("sidebar-btn-primary")
                    disabled = currentRelay.isBlank()
                    onClick = { setCreateOpen(true) }
                    +"Create group"
                }
                button {
                    className = ClassName("sidebar-btn-secondary")
                    onClick = { setJoinOpen(true) }
                    +"Join group"
                }
            }

            if (createOpen) {
                modal("Create group", { setCreateOpen(false) }) {
                    input {
                        className = ClassName("modal-input")
                        placeholder = "Group name"
                        value = newName
                        onChange = { event -> setNewName(event.currentTarget.value) }
                    }
                    input {
                        className = ClassName("modal-input")
                        placeholder = "About (optional)"
                        value = newAbout
                        onChange = { event -> setNewAbout(event.currentTarget.value) }
                    }
                    label {
                        className = ClassName("toggle-row")
                        input {
                            type = InputType.checkbox
                            checked = isPrivate
                            onChange = { event -> setIsPrivate(event.currentTarget.checked) }
                        }
                        span { +"Private" }
                    }
                    label {
                        className = ClassName("toggle-row")
                        input {
                            type = InputType.checkbox
                            checked = isClosed
                            onChange = { event -> setIsClosed(event.currentTarget.checked) }
                        }
                        span { +"Closed" }
                    }
                    button {
                        className = ClassName("modal-primary")
                        disabled = newName.isBlank()
                        onClick = { submitCreate() }
                        +"Create"
                    }
                }
            }

            if (joinOpen) {
                modal("Join group", { setJoinOpen(false) }) {
                    p {
                        className = ClassName("muted")
                        +"Paste a group id to join on the current relay."
                    }
                    input {
                        className = ClassName("modal-input")
                        placeholder = "group id"
                        value = joinId
                        onChange = { event -> setJoinId(event.currentTarget.value) }
                    }
                    button {
                        className = ClassName("modal-primary")
                        disabled = joinId.isBlank()
                        onClick = { submitJoin() }
                        +"Join"
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.sidebarGroupRow(
    group: GroupMetadata,
    selected: Boolean,
    unread: Int,
    onClick: () -> Unit,
) {
    div {
        key = group.id
        className = ClassName(if (selected) "sidebar-group selected" else "sidebar-group")
        this.onClick = { onClick() }
        avatarTile(group.picture, group.name ?: group.id, "group-icon-sm")
        span {
            className = ClassName("sidebar-group-name")
            +(group.name ?: group.id.take(12))
        }
        if (unread > 0) {
            span {
                className = ClassName("sidebar-unread")
                +(if (unread > 99) "99+" else unread.toString())
            }
        }
    }
}
