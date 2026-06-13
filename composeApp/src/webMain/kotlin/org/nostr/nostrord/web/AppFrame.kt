package org.nostr.nostrord.web

import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.removeAccountBusyLabel
import org.nostr.nostrord.auth.removeAccountConfirmLabel
import org.nostr.nostrord.auth.removeAccountDialogBody
import org.nostr.nostrord.auth.removeAccountDialogTitle
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.tabItem
import org.nostr.nostrord.web.modals.AddGroupModal
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.JoinGroupModal
import org.nostr.nostrord.web.navigation.consumeInviteInHash
import org.nostr.nostrord.web.navigation.currentHashRoute
import org.nostr.nostrord.web.navigation.pushHome
import org.nostr.nostrord.web.navigation.pushRoute
import org.nostr.nostrord.web.screens.ChatScreen
import org.nostr.nostrord.web.screens.DmPage
import org.nostr.nostrord.web.screens.HomePage
import org.nostr.nostrord.web.screens.ProfilePage
import org.nostr.nostrord.web.screens.SettingsScreen
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/**
 * New-design logged-in frame (prototype AppShell): the 72px groups rail (home,
 * joined groups with unread badges, add, DMs and notifications) plus the 240px
 * sidebar (home hub or the open group's sidebar, with the account bar) around the
 * content. Owns the new-design navigation: the #/g/<relay>/<groupId> hash is the
 * source of truth (back/forward work), parsed into a [GroupRoute]. Compact widths
 * hide the nav columns via CSS (the mobile drawer comes later). Mirrors the
 * Compose ui/components/layout/AppFrame.
 */
val AppFrame =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val vm = useViewModel { HomePageViewModel(repo, AppModule.notificationHistoryStore) }
        val groups = useStateFlow(vm.myGroups)
        val friends = useStateFlow(vm.friends)
        val unreadCounts = useStateFlow(vm.unreadCounts)
        val notificationUnread = useStateFlow(vm.notificationUnread)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val (hub, setHub) = useState { 0 }
        val (menuOpen, setMenuOpen) = useState { false }
        val (confirmLogout, setConfirmLogout) = useState { false }
        val (logoutBusy, setLogoutBusy) = useState { false }
        val (showSettings, setShowSettings) = useState { false }
        // Which step of the rail "+" add-group flow is open: "chooser" / "create" / "join".
        val (addGroupStep, setAddGroupStep) = useState<String?> { null }

        // The hash is the navigation source of truth: state follows it (hashchange
        // covers pushes from this frame, back/forward, and hand-edited URLs).
        val (route, setRoute) = useState { currentHashRoute() }
        useEffect(Unit) {
            val listener: (dynamic) -> Unit = { setRoute(currentHashRoute()) }
            window.asDynamic().addEventListener("hashchange", listener)
            try {
                awaitCancellation()
            } finally {
                window.asDynamic().removeEventListener("hashchange", listener)
            }
        }
        val groupRoute = route as? GroupRoute

        // Mirror the legacy AppShell: cross-relay navigation switches the relay first;
        // the open group is tracked (notification suppression + unread clearing); an
        // explicit ?invite= auto-joins once and is stripped from the hash.
        useEffect(groupRoute?.relayUrl, groupRoute?.groupId, groupRoute?.inviteCode) {
            val r = groupRoute
            if (r == null) {
                repo.setActiveGroup(null)
            } else {
                repo.setActiveGroup(r.groupId)
                repo.markGroupAsRead(r.groupId)
                val code = r.inviteCode
                if (code != null) {
                    consumeInviteInHash(r)
                    setRoute(r.copy(inviteCode = null))
                }
                // ONE sequential launch (mirrors the legacy AppShell): the join must
                // run only after the relay switch completes, or it registers the group
                // under the previous relay.
                launchApp {
                    if (r.relayUrl != repo.currentRelayUrl.value) {
                        repo.switchRelay(r.relayUrl)
                    }
                    if (code != null) {
                        repo.joinGroup(r.groupId, code)
                    }
                }
            }
        }

        // Render the chat as soon as the target group id is known (placeholder until
        // the kind:39000 arrives), caching the last real metadata to ride out account
        // swaps — same rationale as the legacy AppShell.
        val lastGroupRef = useRef<GroupMetadata>(null)
        if (lastGroupRef.current?.id != groupRoute?.groupId) lastGroupRef.current = null
        val selectedGroup: GroupMetadata? =
            groupRoute?.let { r ->
                val real = groupsByRelay[r.relayUrl].orEmpty().firstOrNull { it.id == r.groupId }
                if (real != null) {
                    lastGroupRef.current = real
                    real
                } else {
                    lastGroupRef.current ?: GroupMetadata(
                        id = r.groupId,
                        name = null,
                        about = null,
                        picture = null,
                        isPublic = true,
                        isOpen = true,
                    )
                }
            }

        fun performLogout() {
            val id = activeId ?: return
            setLogoutBusy(true)
            launchApp {
                AppModule.accountManager.removeAccount(id)
                setLogoutBusy(false)
                setConfirmLogout(false)
            }
        }

        val active = accounts.firstOrNull { it.id == activeId }
        val meta = active?.pubkey?.let { userMetadata[it] }
        val displayName =
            meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: active?.label
                ?: "Account"

        div {
            className = ClassName("app-frame")
            div {
                className = ClassName("app-frame-nav")

                // ── Groups rail ──────────────────────────────────────────────
                div {
                    className = ClassName("rail")
                    button {
                        className = ClassName(if (route == null) "rail-btn active" else "rail-btn")
                        title = "Home"
                        onClick = { pushHome() }
                        icon(Ic.Home)
                    }

                    div {
                        className = ClassName("rail-scroll")
                        groups.forEach { group ->
                            val name = group.meta.name ?: group.meta.id
                            div {
                                key = group.meta.id
                                className = ClassName("rail-group")
                                button {
                                    className =
                                        ClassName(
                                            if (groupRoute?.groupId == group.meta.id) "rail-group-btn active" else "rail-group-btn",
                                        )
                                    title = name
                                    onClick = { pushRoute(GroupRoute(group.relayUrl, group.meta.id)) }
                                    WebAvatar {
                                        url = group.meta.picture
                                        seed = group.meta.id
                                        this.name = name
                                        kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                                        cls = "group-card-avatar"
                                    }
                                }
                                val unread = unreadCounts[group.meta.id] ?: 0
                                if (unread > 0) {
                                    span {
                                        className = ClassName("rail-badge")
                                        +(if (unread > 99) "99+" else "$unread")
                                    }
                                }
                            }
                        }
                    }
                    button {
                        className = ClassName("rail-btn")
                        title = "Add group"
                        onClick = { setAddGroupStep("chooser") }
                        icon(Ic.Add)
                    }
                    div { className = ClassName("rail-spacer") }
                    div { className = ClassName("rail-divider") }
                    button {
                        className = ClassName(if (route is DmRoute) "rail-btn active" else "rail-btn")
                        title = "Direct messages"
                        onClick = { pushRoute(DmRoute()) }
                        icon(Ic.Mail)
                    }
                    div {
                        className = ClassName("rail-group")
                        button {
                            className = ClassName("rail-btn")
                            title = "Notifications"
                            // Not ported yet
                            icon(Ic.Notifications)
                        }
                        if (notificationUnread > 0) {
                            span {
                                className = ClassName("rail-badge top")
                                +(if (notificationUnread > 99) "99+" else "$notificationUnread")
                            }
                        }
                    }
                }

                // ── Sidebar: home hub or the open group's tree ───────────────
                div {
                    className = ClassName("sidebar")
                    val dmRoute = route as? DmRoute
                    if (groupRoute != null) {
                        GroupSidebar {
                            this.route = groupRoute
                            onNavigateGroup = { pushRoute(it) }
                        }
                    } else if (dmRoute != null) {
                        DmSidebar {
                            activePubkey = dmRoute.pubkey
                            onOpenConversation = { pushRoute(it) }
                        }
                    } else {
                        div {
                            className = ClassName("sidebar-header")
                            +"nostrord"
                        }
                        div {
                            className = ClassName("sidebar-body")
                            div {
                                className = ClassName("tab-strip")
                                tabItem(hub == 0, Ic.People, "Friends") { setHub(0) }
                                tabItem(hub == 1, Ic.Bookmark, "Saved") { setHub(1) }
                            }
                            when {
                                hub == 1 ->
                                    div {
                                        className = ClassName("sidebar-note")
                                        +"Nothing saved yet."
                                    }
                                friends.isEmpty() ->
                                    div {
                                        className = ClassName("sidebar-note")
                                        +"You don't follow anyone yet."
                                    }
                                else ->
                                    div {
                                        className = ClassName("sidebar-friends")
                                        friends.forEach { friend ->
                                            val friendName =
                                                friend.metadata?.displayName?.takeIf { it.isNotBlank() }
                                                    ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
                                                    ?: (Nip19.encodeNpub(friend.pubkey).take(12) + "…")
                                            button {
                                                key = friend.pubkey
                                                className = ClassName("sidebar-friend")
                                                onClick = { pushRoute(UserRoute(friend.pubkey)) }
                                                WebAvatar {
                                                    url = friend.metadata?.picture
                                                    seed = friend.pubkey
                                                    name = friendName
                                                    cls = "sidebar-friend-avatar"
                                                }
                                                span {
                                                    className = ClassName("sidebar-friend-name")
                                                    +friendName
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                    div {
                        className = ClassName("account-menu")
                        if (menuOpen) {
                            div {
                                className = ClassName("account-pop-backdrop")
                                onClick = { setMenuOpen(false) }
                            }
                            div {
                                className = ClassName("account-pop")
                                div {
                                    className = ClassName("account-pop-label")
                                    +"Accounts"
                                }
                                accounts.forEach { account ->
                                    val isActiveAccount = account.id == activeId
                                    val m = userMetadata[account.pubkey]
                                    val name =
                                        m?.displayName?.takeIf { it.isNotBlank() }
                                            ?: m?.name?.takeIf { it.isNotBlank() }
                                            ?: account.label
                                    button {
                                        key = account.id
                                        className = ClassName("account-pop-row")
                                        onClick = {
                                            setMenuOpen(false)
                                            if (!isActiveAccount) {
                                                launchApp { AppModule.accountManager.switchAccount(account.id) }
                                            }
                                        }
                                        WebAvatar {
                                            url = m?.picture
                                            seed = account.pubkey
                                            this.name = name
                                            cls = "account-pop-avatar"
                                        }
                                        div {
                                            className = ClassName("account-pop-meta")
                                            div {
                                                className = ClassName("account-pop-name")
                                                +name
                                            }
                                            div {
                                                className = ClassName("account-pop-npub")
                                                +runCatching { Nip19.encodeNpub(account.pubkey) }.getOrDefault("")
                                            }
                                        }
                                        span {
                                            className = ClassName("signer-chip")
                                            +signerLabel(account.authMethod)
                                        }
                                        if (isActiveAccount) {
                                            span {
                                                className = ClassName("account-pop-check")
                                                icon(Ic.Check)
                                            }
                                        }
                                    }
                                }
                                div { className = ClassName("account-pop-divider") }
                                button {
                                    className = ClassName("account-pop-action")
                                    onClick = {
                                        setMenuOpen(false) /* add-account sheet: not wired in the new flow yet */
                                    }
                                    icon(Ic.Add)
                                    +"Add account"
                                }
                                button {
                                    className = ClassName("account-pop-action danger")
                                    onClick = {
                                        setMenuOpen(false)
                                        setConfirmLogout(true)
                                    }
                                    icon(Ic.Logout)
                                    +"Log out of $displayName"
                                }
                            }
                        }
                        div {
                            className = ClassName("account-bar")
                            button {
                                className = ClassName("account-bar-trigger")
                                title = "Switch account"
                                onClick = { setMenuOpen(!menuOpen) }
                                WebAvatar {
                                    url = meta?.picture
                                    seed = active?.pubkey
                                    this.name = displayName
                                    cls = "account-bar-avatar"
                                }
                                div {
                                    className = ClassName("account-bar-meta")
                                    div {
                                        className = ClassName("account-bar-name")
                                        +displayName
                                    }
                                    div {
                                        className = ClassName("account-bar-status")
                                        +"online"
                                    }
                                }
                                span {
                                    className = ClassName(if (menuOpen) "account-chevron open" else "account-chevron")
                                    icon(Ic.ChevronRight)
                                }
                            }
                            button {
                                className = ClassName("icon-btn")
                                title = "Settings"
                                onClick = { setShowSettings(true) }
                                icon(Ic.Settings)
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("app-frame-main")
                val r = route
                when {
                    r is UserRoute ->
                        ProfilePage {
                            pubkey = r.pubkey
                            onOpenGroup = { pushRoute(it) }
                            onEditProfile = { setShowSettings(true) }
                        }
                    r is DmRoute ->
                        DmPage {
                            pubkey = r.pubkey
                            onOpenProfile = { pushRoute(it) }
                        }
                    r is GroupRoute && selectedGroup != null ->
                        // The legacy full-featured chat (messages, composer, search, member
                        // sidebar, info/invite modals), keyed so a group switch remounts it.
                        ChatScreen {
                            key = "${r.relayUrl}/${r.groupId}"
                            group = selectedGroup
                            onLeave = { pushHome() }
                            scrollToMessageId = null
                            onScrolledToMessage = {}
                            onNavigateGroup = { gid, relay ->
                                pushRoute(GroupRoute(relay ?: r.relayUrl, gid))
                            }
                            onOpenDrawer = { /* mobile drawer comes later */ }
                        }
                    else ->
                        HomePage {
                            onOpenGroup = { pushRoute(GroupRoute(it.relayUrl, it.meta.id)) }
                        }
                }
            }

            // Add-group flow (rail "+"): the new-design chooser, then the existing
            // create / join modals. Opening the created/joined group chat is not ported
            // to the new design yet; the group still shows up in the rail via kind:10009.
            when (addGroupStep) {
                "chooser" ->
                    AddGroupModal {
                        onClose = { setAddGroupStep(null) }
                        onJoin = { setAddGroupStep("join") }
                        onCreate = { setAddGroupStep("create") }
                    }
                "create" -> CreateGroupModal { onClose = { setAddGroupStep(null) } }
                "join" ->
                    JoinGroupModal {
                        onClose = { setAddGroupStep(null) }
                        onJoin = { relayUrl, groupId, inviteCode ->
                            // Open the group; the route effect switches relays and
                            // consumes the invite code (auto-join). Normalized so the
                            // route matches the relay keys used everywhere else.
                            pushRoute(GroupRoute(relayUrl.normalizeRelayUrl(), groupId, inviteCode))
                        }
                    }
            }

            // Legacy Settings overlay, reachable from the account bar's gear until
            // the new-design settings page is ported.
            if (showSettings) {
                SettingsScreen {
                    onClose = { setShowSettings(false) }
                    // Settings already confirmed the logout internally.
                    onLogoutWithChoice = {
                        setShowSettings(false)
                        performLogout()
                    }
                }
            }

            // Sign-out confirmation (prototype AccountMenu -> confirmAction).
            if (confirmLogout && active != null) {
                val fallbackLabel = accounts.filter { it.id != active.id }.maxByOrNull { it.addedAt }?.label
                div {
                    className = ClassName("modal-overlay")
                    onClick = { if (!logoutBusy) setConfirmLogout(false) }
                    div {
                        className = ClassName("modal-card sm")
                        onClick = { it.stopPropagation() }
                        div {
                            className = ClassName("modal-title")
                            +removeAccountDialogTitle(isActive = true, accountLabel = displayName)
                        }
                        div {
                            className = ClassName("modal-subtitle tight")
                            +removeAccountDialogBody(
                                isActive = true,
                                accountLabel = displayName,
                                fallbackLabel = fallbackLabel,
                                method = active.authMethod,
                            )
                        }
                        div {
                            className = ClassName("modal-footer")
                            button {
                                className = ClassName("btn-text")
                                disabled = logoutBusy
                                onClick = { setConfirmLogout(false) }
                                +"Cancel"
                            }
                            button {
                                className = ClassName("btn-danger")
                                disabled = logoutBusy
                                onClick = { performLogout() }
                                +(
                                    if (logoutBusy) {
                                        removeAccountBusyLabel(isActive = true)
                                    } else {
                                        removeAccountConfirmLabel(isActive = true)
                                    }
                                    )
                            }
                        }
                    }
                }
            }
        }
    }

/** Short signer label for the account chip (prototype AccountMenu). */
private fun signerLabel(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "key"
    AuthMethod.BUNKER -> "bunker"
    AuthMethod.NIP07 -> "extension"
}
