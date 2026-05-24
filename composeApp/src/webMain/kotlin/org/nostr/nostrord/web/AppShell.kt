package org.nostr.nostrord.web

import kotlinx.browser.window
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.auth.WebAuth
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.ImageViewerHost
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.ZapModalHost
import org.nostr.nostrord.web.components.groupNavSkeleton
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.modals.AddRelayModal
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.JoinGroupModal
import org.nostr.nostrord.web.screens.AddAccountSheet
import org.nostr.nostrord.web.screens.ChatScreen
import org.nostr.nostrord.web.screens.HomeScreen
import org.nostr.nostrord.web.screens.NotificationsScreen
import org.nostr.nostrord.web.screens.OnboardingScreen
import org.nostr.nostrord.web.screens.SettingsScreen
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

private fun relayDisplayName(url: String, names: Map<String, String>): String = names[url]?.takeIf { it.isNotBlank() }
    ?: url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

private fun authMethodLabel(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "Private key"
    AuthMethod.BUNKER -> "Bunker (NIP-46)"
    AuthMethod.NIP07 -> "Browser extension (NIP-07)"
}

/** Build the URL query that reflects the current navigation (round-trips with main.kt). */
private fun searchFor(relay: String, groupId: String?, notifications: Boolean): String {
    val host = relay.removePrefix("wss://").removePrefix("ws://")
    return when {
        notifications -> "?view=notifications"
        groupId != null && relay.isNotBlank() -> "?relay=$host&group=$groupId"
        relay.isNotBlank() -> "?relay=$host"
        else -> ""
    }
}

private fun parseSearch(search: String): Map<String, String> = search.removePrefix("?").split("&").filter { it.isNotEmpty() }.associate { part ->
    val i = part.indexOf("=")
    if (i >= 0) part.substring(0, i) to part.substring(i + 1) else part to ""
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
        val loadingRelays = useStateFlow(repo.loadingRelays)
        val fullListFetched = useStateFlow(repo.fullGroupListFetchedRelays)
        val accounts = useStateFlow(AppModule.accountStore.accounts)
        val activeAccountId = useStateFlow(AppModule.accountStore.activeId)
        val notifUnread = useStateFlow(AppModule.notificationHistoryStore.entries).count { !it.read }
        val connState = useStateFlow(repo.connectionState)

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
        val otherGroups = groups.filter { it.id !in joinedIds }
        val groupsLoading = activeRelay in loadingRelays

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
        // A deep-link (&e=) message to scroll to + highlight once the chat opens.
        val (scrollToEventId, setScrollToEventId) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        val (copied, setCopied) = useState { false }
        val (modal, setModal) = useState<String?> { null }
        val (relayTab, setRelayTab) = useState { 0 }
        val (settingsOpen, setSettingsOpen) = useState { false }
        val (notificationsOpen, setNotificationsOpen) = useState { false }
        val (addAccountOpen, setAddAccountOpen) = useState { false }
        val (myExpanded, setMyExpanded) = useState { true }
        val (otherExpanded, setOtherExpanded) = useState { true }
        val firstNav = useRef(true)

        val selectedGroup: GroupMetadata? = groups.firstOrNull { it.id == selectedGroupId }

        // Open the Add-relay modal on a given tab (0 = Suggested, 1 = Custom URL).
        val openRelay: (Int) -> Unit = { tab ->
            setRelayTab(tab)
            setModal("relay")
        }

        // Consume a URL deep-link once (?relay= / &group= / &code= / &e= / ?view=notifications),
        // parsed into StartupResolver by main.kt. switchRelay is idempotent.
        useEffectOnce {
            val ctx = StartupResolver.externalLaunchContext
            when (ctx) {
                is ExternalLaunchContext.OpenRelay ->
                    launchApp { repo.switchRelay(ctx.relayUrl) }
                is ExternalLaunchContext.OpenGroup -> {
                    launchApp {
                        ctx.relayUrl?.let { repo.switchRelay(it) }
                        if (!ctx.inviteCode.isNullOrBlank()) repo.joinGroup(ctx.groupId, ctx.inviteCode)
                    }
                    setSelectedGroupId(ctx.groupId)
                    ctx.messageId?.takeIf { it.isNotBlank() }?.let { setScrollToEventId(it) }
                }
                is ExternalLaunchContext.OpenNotifications -> {
                    ctx.relayUrl?.let { url -> launchApp { repo.switchRelay(url) } }
                    setNotificationsOpen(true)
                }
                else -> {}
            }
            if (ctx != null) StartupResolver.clearExternalLaunchContext()
        }

        // Keep the URL in sync with navigation so it's shareable and back/forward works.
        useEffect(activeRelay, selectedGroupId, notificationsOpen) {
            val target = window.location.pathname + searchFor(activeRelay, selectedGroupId, notificationsOpen)
            val current = window.location.pathname + window.location.search
            if (target != current) {
                if (firstNav.current == true) {
                    window.history.replaceState(null, "", target)
                } else {
                    window.history.pushState(null, "", target)
                }
            }
            firstNav.current = false
        }

        // Browser back/forward → apply the URL to state.
        useEffectOnce {
            window.asDynamic().addEventListener("popstate") {
                val params = parseSearch(window.location.search)
                if (params["view"] == "notifications") {
                    setNotificationsOpen(true)
                } else {
                    setNotificationsOpen(false)
                    val relay = params["relay"]?.takeIf { it.isNotBlank() }?.toRelayUrl()
                    if (relay != null && relay != repo.currentRelayUrl.value) {
                        launchApp { repo.switchRelay(relay) }
                    }
                    setSelectedGroupId(params["group"]?.takeIf { it.isNotBlank() })
                }
            }
        }

        // Fetch the full group list for the active relay (so Other Groups / the picker
        // show non-joined groups). Idempotent — the repo tracks fetched relays.
        useEffect(activeRelay, fullListFetched.size) {
            if (activeRelay.isNotBlank() && activeRelay !in fullListFetched) {
                launchApp { repo.requestFullGroupListForRelay(activeRelay) }
            }
        }

        // NIP-57 zap modal overlay — opened from anywhere via WebZapController.
        ZapModalHost {}

        // Fullscreen image viewer — opened by tapping a chat image.
        ImageViewerHost {}

        div {
            className = ClassName(if (drawerOpen) "layout drawer-open" else "layout")

            button {
                className = ClassName("mobile-menu-btn")
                onClick = { setDrawerOpen(true) }
                icon(Ic.Menu)
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
                                WebAvatar {
                                    url = relayMetadata[relay]?.icon
                                    name = relayDisplayName(relay, relayNames)
                                    cls = "rail-icon"
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
                                icon(Ic.Add)
                            }
                        }
                    }
                    div {
                        className = ClassName(if (notificationsOpen) "rail-item active" else "rail-item")
                        onClick = { setNotificationsOpen(true) }
                        div {
                            className = ClassName("avatar-tile rail-icon rail-bell")
                            icon(Ic.Notifications)
                        }
                        if (notifUnread > 0) {
                            span {
                                className = ClassName("rail-badge")
                                +(if (notifUnread > 9) "9+" else notifUnread.toString())
                            }
                        }
                    }
                    div {
                        className = ClassName("rail-account")
                        onClick = { setMenuOpen(!menuOpen) }
                        WebAvatar {
                            url = meMetadata?.picture
                            name = meName
                            cls = "rail-icon"
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

                            val openGroup: (String) -> Unit = { id ->
                                setSelectedGroupId(id)
                                setNotificationsOpen(false)
                                setDrawerOpen(false)
                            }

                            if (groupsLoading && groups.isEmpty()) {
                                repeat(6) { groupNavSkeleton() }
                            } else {
                                sectionToggle("My Groups", myExpanded) { setMyExpanded(!myExpanded) }
                                if (myExpanded) {
                                    myGroups.forEach { group ->
                                        sidebarGroupRow(group, selectedGroupId == group.id, unreadCounts[group.id] ?: 0, openGroup)
                                    }
                                }

                                if (otherGroups.isNotEmpty()) {
                                    sectionToggle("Other Groups", otherExpanded) { setOtherExpanded(!otherExpanded) }
                                    if (otherExpanded) {
                                        otherGroups.forEach { group ->
                                            sidebarGroupRow(group, selectedGroupId == group.id, unreadCounts[group.id] ?: 0, openGroup)
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

                // Connection status banner (disconnected / reconnecting / error)
                if (hasRelays &&
                    !notificationsOpen &&
                    connState != ConnectionManager.ConnectionState.Connected &&
                    connState != ConnectionManager.ConnectionState.Connecting
                ) {
                    div {
                        className = ClassName("connection-banner")
                        span {
                            +when (val s = connState) {
                                is ConnectionManager.ConnectionState.Reconnecting ->
                                    "Reconnecting (${s.attempt}/${s.maxAttempts})…"
                                is ConnectionManager.ConnectionState.Error -> "Unable to connect to the relay."
                                else -> "Disconnected from the relay."
                            }
                        }
                        button {
                            className = ClassName("connection-retry")
                            onClick = { launchApp { repo.reconnect() } }
                            +"Retry"
                        }
                    }
                }

                div {
                    className = ClassName("content-screen")
                    when {
                        notificationsOpen ->
                            NotificationsScreen {
                                onOpen = { relay, gid ->
                                    if (relay.isNotBlank() && relay != currentRelayUrl) {
                                        launchApp { repo.switchRelay(relay) }
                                    }
                                    setSelectedGroupId(gid)
                                    setNotificationsOpen(false)
                                }
                            }
                        !hasRelays ->
                            OnboardingScreen {
                                onAddRelay = { openRelay(0) }
                                onAddRelayCustomUrl = { openRelay(1) }
                            }
                        selectedGroup != null ->
                            ChatScreen {
                                group = selectedGroup
                                onLeave = { setSelectedGroupId(null) }
                                scrollToMessageId = scrollToEventId
                                onScrolledToMessage = { setScrollToEventId(null) }
                            }
                        else ->
                            HomeScreen {
                                onOpenGroup = { setSelectedGroupId(it) }
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
                            WebAvatar {
                                url = meMetadata?.picture
                                name = meName
                                cls = "me-avatar-lg"
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
                                        if (copied) icon(Ic.Check) else icon(Ic.ContentCopy)
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
                                WebAvatar {
                                    url = meta?.picture
                                    this.name = name
                                    cls = "me-avatar-sm"
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
                                        icon(Ic.Check)
                                    }
                                }
                                if (accounts.size > 1) {
                                    button {
                                        className = ClassName("me-delete")
                                        onClick = {
                                            it.stopPropagation()
                                            launchApp { AppModule.accountManager.removeAccount(account.pubkey) }
                                        }
                                        icon(Ic.Delete)
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
                                icon(Ic.Add)
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
                                icon(Ic.Settings)
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
                                icon(Ic.Logout)
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
                            // Persist to the kind:10009 list, then switch to it.
                            launchApp {
                                repo.addRelay(url)
                                repo.switchRelay(url)
                            }
                            setModal(null)
                        }
                    }
            }
        }
    }

private fun ChildrenBuilder.sectionToggle(label: String, expanded: Boolean, onToggle: () -> Unit) {
    div {
        className = ClassName("sidebar-section-toggle")
        onClick = { onToggle() }
        span {
            // Native uses a tiny ▼ glyph that rotates (down = expanded, -90° = collapsed),
            // not a Material chevron icon.
            className = ClassName(if (expanded) "sidebar-chevron" else "sidebar-chevron collapsed")
            +"▼"
        }
        span {
            className = ClassName("sidebar-section-label")
            +label
        }
    }
}

private fun ChildrenBuilder.sidebarGroupRow(group: GroupMetadata, selected: Boolean, unread: Int, onOpen: (String) -> Unit) {
    div {
        key = group.id
        className = ClassName(if (selected) "sidebar-group selected" else "sidebar-group")
        onClick = { onOpen(group.id) }
        WebAvatar {
            url = group.picture
            name = group.name ?: group.id
            cls = "group-icon-sm"
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
