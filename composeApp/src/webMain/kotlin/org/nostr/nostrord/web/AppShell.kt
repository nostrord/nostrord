package org.nostr.nostrord.web

import kotlinx.browser.window
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.removeAccountBusyLabel
import org.nostr.nostrord.auth.removeAccountConfirmLabel
import org.nostr.nostrord.auth.removeAccountDialogBody
import org.nostr.nostrord.auth.removeAccountDialogTitle
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.clearLastGroupForRelay
import org.nostr.nostrord.storage.getLastGroupForRelay
import org.nostr.nostrord.storage.saveLastGroupForRelay
import org.nostr.nostrord.utils.toRelayUrl
import org.nostr.nostrord.web.auth.WebAuth
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.BunkerStatusBanner
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.ImageViewerHost
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.ZapModalHost
import org.nostr.nostrord.web.components.groupNavSkeleton
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.modals.AccountChooserModal
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
import react.dom.html.ReactHTML.input
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
        // Sort MY GROUPS by most-recent activity (matches GroupsNavSidebar.kt:148-154).
        // Groups with no known timestamp sink to the bottom in stable input order.
        val latestMessageTimestamps = useStateFlow(repo.latestMessageTimestamps)
        val myGroups =
            groups
                .filter { it.id in joinedIds }
                .sortedByDescending { latestMessageTimestamps[it.id] ?: Long.MIN_VALUE }
        val otherGroups = groups.filter { it.id !in joinedIds }
        val groupsLoading = activeRelay in loadingRelays
        // Joined IDs that have no kind:39000 on this relay — stale kind:10009 pins
        // (the group was deleted while we were offline, or the relay forgot it).
        // We surface them with a 'forget' action so the user can clean up their
        // pin list without trying to enter a dead group.
        val orphanedByRelay = useStateFlow(repo.orphanedJoinedByRelay)
        val orphanedIds = orphanedByRelay[activeRelay].orEmpty()
        // Lazy relays only fetch their joined groups up front; the full kind:39000
        // catalogue is loaded on demand. We trigger it when OTHER GROUPS is
        // expanded (see useEffect below). Matches GroupsNavSidebar.kt:176-183.
        val isRelayLazy = repo.isGroupFetchLazy(activeRelay)
        val hasFullList = activeRelay in fullListFetched

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
        // Account chooser shown when signing out of the active account while others
        // remain signed in (mirrors native App.kt:381-387). signOutChooserId is the
        // account being signed out; signOutAfterAddId carries it through the
        // "Add a new login" path so the old account is only wiped once a new login
        // warm-swaps in — validate-before-teardown.
        val (signOutChooserId, setSignOutChooserId) = useState<String?> { null }
        val (signOutAfterAddId, setSignOutAfterAddId) = useState<String?> { null }
        // Snapshot of accounts.size taken when "Add a new login" was clicked. The
        // AddAccountSheet has no onAdded callback — we detect a successful add by
        // observing the store size grow past this baseline, then wipe the deferred
        // account (mirrors native's `onAdded` deferred-wipe in App.kt:1027-1038).
        val (accountsCountAtAdd, setAccountsCountAtAdd) = useState<Int?> { null }
        // Per-account confirmation dialog (mirrors native RemoveAccountDialog in
        // MeMenu.kt). Active+multi accounts go through the chooser; everything
        // else surfaces this dialog before AccountManager.removeAccount runs.
        val (removeTarget, setRemoveTarget) = useState<Account?> { null }
        val (removeTargetBusy, setRemoveTargetBusy) = useState { false }
        val (myExpanded, setMyExpanded) = useState { true }
        // OTHER GROUPS expansion is persisted per relay so a deliberately
        // collapsed sidebar stays collapsed on next session. Matches native
        // GroupsNavSidebar.kt:167-169.
        val (otherExpanded, setOtherExpanded) = useState { true }
        useEffect(activeRelay) {
            if (activeRelay.isBlank()) return@useEffect
            setOtherExpanded(SecureStorage.getBooleanPref("sidebar_other_expanded_$activeRelay", default = true))
        }
        useEffect(activeRelay, otherExpanded) {
            if (activeRelay.isNotBlank()) {
                SecureStorage.saveBooleanPref("sidebar_other_expanded_$activeRelay", otherExpanded)
            }
        }
        // Lazy relays only fetch joined groups up front; the OTHER GROUPS list
        // arrives only when the user opens that section. Same trigger native
        // uses (GroupsNavSidebar.kt:179-183).
        val isConnected = connState is ConnectionManager.ConnectionState.Connected
        useEffect(activeRelay, otherExpanded, isRelayLazy, hasFullList, isConnected) {
            if (otherExpanded && isRelayLazy && !hasFullList && isConnected && activeRelay.isNotBlank()) {
                launchApp { repo.requestFullGroupListForRelay(activeRelay) }
            }
        }
        // Groups sidebar search query (filters My Groups + Other Groups by name/id, like native).
        val (groupQuery, setGroupQuery) = useState { "" }
        val firstNav = useRef(true)
        // Native saves the last-active group per relay (SecureStorage.saveLastGroupForRelay)
        // so cold-booting onto a bare URL restores the user where they left off. The web
        // wasn't doing this — entering localhost:8080/ landed on the relay home even when
        // the user had been deep inside a group seconds earlier. Latch flips to false once
        // the cold-boot restore has had its chance to run.
        val coldBootRestorePending = useRef(true)

        // Deep-link parity with native: render ChatScreen as soon as we know the target group
        // id, not only after kind:39000 arrives. The full metadata flows into `groups` async-
        // ously (after switchRelay + the on-demand REQ in setActiveGroupId); until then the
        // chat shows its loading skeletons against a placeholder with defaults. Without this,
        // visiting ?relay=...&group=... briefly flashed HomeScreen because `selectedGroup`
        // was null while the metadata was in-flight.
        //
        // We also cache the last-known metadata in a ref to ride out the
        // intermediate window during account swap, when groupManager.clear()
        // wipes _groupsByRelay before restoreGroupsForRelay refills it. Without
        // this the header flashed from "Private Group X" (real) → "Group"
        // (placeholder defaulting to isPublic/isOpen=true, so the "Invite
        // Code" + "Request to Join" buttons disappeared and morphed into a
        // plain "Join"). Cache is keyed on the group id — reset on change.
        val lastGroupRef = useRef<GroupMetadata>(null)
        if (lastGroupRef.current?.id != selectedGroupId) lastGroupRef.current = null
        val selectedGroup: GroupMetadata? = selectedGroupId?.let { id ->
            val real = groups.firstOrNull { it.id == id }
            if (real != null) {
                lastGroupRef.current = real
                real
            } else {
                lastGroupRef.current ?: GroupMetadata(
                    id = id,
                    name = null,
                    about = null,
                    picture = null,
                    isPublic = true,
                    isOpen = true,
                )
            }
        }

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
            if (ctx != null) {
                StartupResolver.clearExternalLaunchContext()
                // Any deep-link context (relay/group/notifications) overrides the
                // cold-boot restore — the user asked for a specific destination.
                coldBootRestorePending.current = false
            }
        }

        // Cold-boot restore: when the URL carries no deep-link, fall back to the
        // last-viewed group on the active relay (native parity — App.kt persists
        // saveLastGroupForRelay on group entry and uses it from resolveScreenForRelay).
        // Runs once activeRelay is known so we look up the right slot.
        useEffect(activeRelay, activePubkey) {
            if (coldBootRestorePending.current != true) return@useEffect
            if (activeRelay.isBlank() || activePubkey.isNullOrBlank()) return@useEffect
            coldBootRestorePending.current = false
            val last = SecureStorage.getLastGroupForRelay(activePubkey, activeRelay) ?: return@useEffect
            setSelectedGroupId(last.first)
        }

        // Persist the user's last-active group per relay so the next cold boot
        // (and same-session relay round-trips) can land them right back in it.
        // Clear when they navigate to Home (null id).
        //
        // Guard against the cross-relay transition window: while switchRelay
        // is in flight, activeRelay may have already flipped to the new relay
        // but selectedGroupId is still pointing at the previous relay's group.
        // Saving (newRelay, oldGroupId) would corrupt the new relay's record
        // with a group that doesn't live there. Skip when the id isn't in the
        // current relay's groups — once setSelectedGroupId for the new relay
        // fires, the effect re-runs with a consistent pair.
        useEffect(activeRelay, selectedGroupId, activePubkey) {
            val pk = activePubkey ?: return@useEffect
            if (activeRelay.isBlank()) return@useEffect
            if (selectedGroupId.isNullOrBlank()) {
                SecureStorage.clearLastGroupForRelay(pk, activeRelay)
            } else {
                val group = groups.firstOrNull { it.id == selectedGroupId } ?: return@useEffect
                SecureStorage.saveLastGroupForRelay(pk, activeRelay, selectedGroupId, group.name)
            }
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

        // Escape closes the Settings overlay / account sheet / account menu (state setters are
        // stable, so a single persistent listener is safe; closing an already-closed one is a no-op).
        useEffectOnce {
            window.asDynamic().addEventListener("keydown") { e: dynamic ->
                if (e.key == "Escape") {
                    setSettingsOpen(false)
                    setAddAccountOpen(false)
                    setMenuOpen(false)
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

        // Re-establish the active relay's connection + group subscriptions when the
        // account changes. Mirrors native HomeScreen's `LaunchedEffect(Unit) { vm.connect() }`,
        // which re-runs because the screen remounts after adding/switching an account.
        // The web shell never remounts on a warm account swap, so without this the new
        // account's groups stay empty (My Groups AND the picker) until a manual relay
        // switch or restart — connect() routes a cache-hit relay through the #88
        // refreshMux branch and re-sends the group-list REQ. Fires only on a real
        // account change (not relay switches within an account), once the relay resolves.
        val connectedAccount = useRef<String>(null)
        useEffect(activeAccountId, activeRelay) {
            val acct = activeAccountId
            if (acct != null && acct != connectedAccount.current && activeRelay.isNotBlank()) {
                connectedAccount.current = acct
                launchApp { repo.connect() }
            }
        }

        // Track the open group: suppress its notifications and clear its unread count + feed
        // entries (mirrors native setActiveGroup + markAsRead on group entry).
        useEffect(selectedGroupId, notificationsOpen) {
            val active = if (notificationsOpen) null else selectedGroupId
            repo.setActiveGroup(active)
            if (active != null) repo.markGroupAsRead(active)
        }

        // NIP-57 zap modal overlay — opened from anywhere via WebZapController.
        ZapModalHost {}

        // Fullscreen image viewer — opened by tapping a chat image.
        ImageViewerHost {}

        // Bunker-unreachable banner — floats at the top while a NIP-46 signer
        // is offline or actively reconnecting; self-hides otherwise. Mounted
        // at the shell level so it persists across navigation (issue #85).
        BunkerStatusBanner {}

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
                            val relayLabel = relayDisplayName(relay, relayNames)
                            div {
                                key = relay
                                className = ClassName(if (isActive) "rail-item active" else "rail-item")
                                // Native tooltip on hover — name when known, host otherwise.
                                title = relayLabel
                                onClick = {
                                    // Mirrors native's resolveScreenForRelay (App.kt:279-293):
                                    //  - same-relay click while NOT in Notifications  → toggle Home
                                    //  - any other case (different relay, or coming
                                    //    out of Notifications)                        → restore
                                    //                                                   that relay's
                                    //                                                   last group
                                    //
                                    // Order matters when crossing relays: if we set selectedGroupId
                                    // BEFORE switchRelay completes, the save useEffect fires with
                                    // (oldRelay, newGroupId) and overwrites the previous relay's
                                    // last-group with the incoming one — clicking back never finds
                                    // the original. Switch the relay first, then set the group
                                    // (and the save effect's groups-membership guard catches the
                                    // remaining single-render window between switchRelay landing
                                    // and setSelectedGroupId firing).
                                    val wasNotifications = notificationsOpen
                                    setNotificationsOpen(false)
                                    val sameRelay = relay == currentRelayUrl
                                    if (sameRelay && !wasNotifications) {
                                        setSelectedGroupId(null)
                                    } else {
                                        val target =
                                            activePubkey?.let { pk ->
                                                SecureStorage.getLastGroupForRelay(pk, relay)?.first
                                            }
                                        if (sameRelay) {
                                            setSelectedGroupId(target)
                                        } else {
                                            launchApp {
                                                repo.switchRelay(relay)
                                                setSelectedGroupId(target)
                                            }
                                        }
                                    }
                                }
                                WebAvatar {
                                    url = relayMetadata[relay]?.icon
                                    seed = relay
                                    kind = AvatarKind.RELAY
                                    name = relayLabel
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
                            title = "Add relay"
                            onClick = { openRelay(0) }
                            div {
                                className = ClassName("avatar-tile rail-icon rail-add")
                                icon(Ic.Add)
                            }
                        }
                    }
                    div {
                        className = ClassName(if (notificationsOpen) "rail-item active" else "rail-item")
                        title = "Notifications"
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
                        title = meName
                        onClick = { setMenuOpen(!menuOpen) }
                        WebAvatar {
                            url = meMetadata?.picture
                            seed = activePubkey
                            name = meName
                            cls = "rail-icon"
                        }
                    }
                }

                // Groups sidebar (hidden while the notifications screen is open)
                div {
                    className = ClassName(if (notificationsOpen) "groups-sidebar hidden" else "groups-sidebar")
                    div {
                        // Clicking the relay title returns to the group picker (home), mirroring native.
                        className =
                            ClassName(
                                "sidebar-header" +
                                    (if (hasRelays) " clickable" else "") +
                                    (if (hasRelays && selectedGroupId == null && !notificationsOpen) " active" else ""),
                            )
                        if (hasRelays) {
                            onClick = {
                                setSelectedGroupId(null)
                                setNotificationsOpen(false)
                                setDrawerOpen(false)
                            }
                        }
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

                            // Native scopes the filter to Other Groups only; My Groups always
                            // shows the full joined list.
                            val groupQ = groupQuery.trim().lowercase()
                            val shownOther = if (groupQ.isEmpty()) {
                                otherGroups
                            } else {
                                otherGroups.filter {
                                    (it.name ?: "").lowercase().contains(groupQ) ||
                                        it.id.lowercase().contains(groupQ)
                                }
                            }

                            if (groupsLoading && groups.isEmpty()) {
                                repeat(6) { groupNavSkeleton() }
                            } else {
                                sectionToggle("My Groups", myExpanded) { setMyExpanded(!myExpanded) }
                                if (myExpanded) {
                                    myGroups.forEach { group ->
                                        sidebarGroupRow(group, selectedGroupId == group.id, unreadCounts[group.id] ?: 0, openGroup)
                                    }
                                    // Joined IDs without a matching kind:39000 on this relay
                                    // — stale kind:10009 pins. Render them at the bottom of
                                    // MY GROUPS as muted rows with a 'forget' action that
                                    // removes them from the user's pin list. Matches the
                                    // native MY GROUPS treatment (GroupsNavSidebar.kt) where
                                    // these rows are visually demoted with a forget IconButton.
                                    orphanedIds.forEach { id ->
                                        orphanedGroupRow(id) {
                                            launchApp { repo.forgetGroup(id, activeRelay) }
                                        }
                                    }
                                }

                                // Show the section if there are Other Groups OR a query is active
                                // (so an empty-result search keeps the input visible to clear/edit).
                                if (otherGroups.isNotEmpty() || groupQ.isNotEmpty()) {
                                    sectionToggle("Other Groups", otherExpanded, otherGroups.size) { setOtherExpanded(!otherExpanded) }
                                    if (otherExpanded) {
                                        div {
                                            className = ClassName("sidebar-search")
                                            icon(Ic.Search, "sidebar-search-icon")
                                            input {
                                                className = ClassName("sidebar-search-input")
                                                placeholder = "Filter…"
                                                value = groupQuery
                                                onChange = { event -> setGroupQuery(event.currentTarget.value) }
                                            }
                                        }
                                        shownOther.forEach { group ->
                                            sidebarGroupRow(group, selectedGroupId == group.id, unreadCounts[group.id] ?: 0, openGroup)
                                        }
                                        if (groupQ.isNotEmpty() && shownOther.isEmpty()) {
                                            div {
                                                className = ClassName("sidebar-section-label")
                                                +"No groups found"
                                            }
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
                                onNavigateGroup = { gid, relay ->
                                    setNotificationsOpen(false)
                                    if (relay != null && relay != currentRelayUrl) {
                                        launchApp { repo.switchRelay(relay) }
                                    }
                                    setSelectedGroupId(gid)
                                }
                                onOpenDrawer = { setDrawerOpen(true) }
                            }
                        else ->
                            HomeScreen {
                                onOpenGroup = { setSelectedGroupId(it) }
                                onOpenDrawer = { setDrawerOpen(true) }
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
                                seed = activePubkey
                                name = meName
                                cls = "me-avatar-lg"
                            }
                            div {
                                className = ClassName("me-header-meta")
                                div {
                                    className = ClassName("me-name-row")
                                    div {
                                        className = ClassName("me-name")
                                        +meName
                                    }
                                    button {
                                        className = ClassName("me-edit-btn")
                                        title = "Edit profile"
                                        onClick = {
                                            // Open Settings — it defaults to the
                                            // Profile section, which already has
                                            // the full kind:0 edit form.
                                            setMenuOpen(false)
                                            setSettingsOpen(true)
                                        }
                                        icon(Ic.Edit)
                                    }
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
                                    seed = account.pubkey
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
                                            // Native `requestRemove` (MeMenu.kt:135):
                                            // active+multi → chooser, else → confirm
                                            // dialog. Same gate, both surfaces.
                                            if (isActiveAccount && accounts.size > 1) {
                                                setMenuOpen(false)
                                                setSignOutChooserId(account.pubkey)
                                            } else {
                                                setMenuOpen(false)
                                                setRemoveTarget(account)
                                            }
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
                                // Same gate as the trash icon (and native MeMenu's
                                // `requestRemove`): active+multi opens the chooser,
                                // single-account / non-active routes through the
                                // RemoveAccountDialog so the user gets the per-account
                                // erase-warning copy before AccountManager runs.
                                val active =
                                    accounts.firstOrNull { it.id == activeAccountId }
                                if (active == null) {
                                    setMenuOpen(false)
                                    launchApp { WebAuth.logout() }
                                } else if (accounts.size > 1) {
                                    setMenuOpen(false)
                                    setSignOutChooserId(active.id)
                                } else {
                                    setMenuOpen(false)
                                    setRemoveTarget(active)
                                }
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
                SettingsScreen {
                    onClose = { setSettingsOpen(false) }
                    onLogoutWithChoice = {
                        setSettingsOpen(false)
                        val activeId = activeAccountId
                        if (activeId != null && accounts.size > 1) {
                            // Multi-account: route through the chooser instead of
                            // silently switching to the next-most-recent account.
                            setSignOutChooserId(activeId)
                        } else {
                            launchApp { repo.logout() }
                        }
                    }
                }
            }

            if (addAccountOpen) {
                AddAccountSheet {
                    onClose = {
                        setAddAccountOpen(false)
                        // Deferred-wipe (mirrors native App.kt:1027-1038): a new
                        // login completed iff accounts grew past the snapshot we
                        // took on "Add a new login". Otherwise the user cancelled
                        // and the deferred account stays active.
                        val baseline = accountsCountAtAdd
                        val toRemove = signOutAfterAddId
                        setSignOutAfterAddId(null)
                        setAccountsCountAtAdd(null)
                        if (toRemove != null && baseline != null && accounts.size > baseline) {
                            launchApp { AppModule.accountManager.removeAccount(toRemove) }
                        }
                    }
                }
            }

            // Account chooser — gates sign-out from the active account when other
            // accounts remain signed in. Mounts at the shell level so it survives
            // navigation while the user is picking.
            signOutChooserId?.let { idToSignOut ->
                AccountChooserModal {
                    signOutAccountId = idToSignOut
                    onDismiss = { setSignOutChooserId(null) }
                    onNewLogin = { signOutId ->
                        setSignOutChooserId(null)
                        setSignOutAfterAddId(signOutId)
                        // Snapshot the account count so the AddAccountSheet's
                        // onClose can tell a successful add from a cancel.
                        setAccountsCountAtAdd(accounts.size)
                        setAddAccountOpen(true)
                    }
                }
            }

            // Per-account confirmation dialog (the screenshot the user asked us
            // to add). Mirrors native RemoveAccountDialog: shows for the trash
            // icon AND the bottom Sign out when the chooser path is *not* the
            // right one (active+single, or non-active row). Strings come from
            // commonMain helpers so the wording matches native exactly.
            removeTarget?.let { target ->
                val isActiveTarget = target.id == activeAccountId
                // Same fallback the AccountManager will silently land on, so we
                // surface its label in the body (matches MeMenu.kt:212-223).
                val fallback =
                    if (isActiveTarget) accounts.filter { it.id != target.id }.maxByOrNull { it.addedAt } else null
                val fallbackMeta = fallback?.let { userMetadata[it.pubkey] }
                val fallbackLabel =
                    fallback?.let { fb ->
                        fallbackMeta?.displayName?.takeIf { it.isNotBlank() }
                            ?: fallbackMeta?.name?.takeIf { it.isNotBlank() }
                            ?: fb.label
                    }
                val targetMeta = userMetadata[target.pubkey]
                val targetLabel =
                    targetMeta?.displayName?.takeIf { it.isNotBlank() }
                        ?: targetMeta?.name?.takeIf { it.isNotBlank() }
                        ?: target.label
                div {
                    className = ClassName("modal-overlay")
                    onClick = { if (!removeTargetBusy) setRemoveTarget(null) }
                    div {
                        className = ClassName("modal-card sm")
                        onClick = { it.stopPropagation() }
                        div {
                            className = ClassName("modal-title")
                            +removeAccountDialogTitle(isActiveTarget, targetLabel)
                        }
                        div {
                            className = ClassName("modal-subtitle tight")
                            // The body branches on the target's auth method so
                            // bunker / NIP-07 users get accurate wording
                            // (no "credentials erased" lie for NIP-07; bunker
                            // users get the URL reminder instead).
                            +removeAccountDialogBody(isActiveTarget, targetLabel, fallbackLabel, target.authMethod)
                        }
                        div {
                            className = ClassName("modal-footer")
                            button {
                                className = ClassName("btn-text")
                                disabled = removeTargetBusy
                                onClick = { setRemoveTarget(null) }
                                +"Cancel"
                            }
                            button {
                                className = ClassName("btn-danger")
                                disabled = removeTargetBusy
                                onClick = {
                                    setRemoveTargetBusy(true)
                                    launchApp {
                                        AppModule.accountManager.removeAccount(target.id)
                                        setRemoveTargetBusy(false)
                                        setRemoveTarget(null)
                                    }
                                }
                                +(
                                    if (removeTargetBusy) {
                                        removeAccountBusyLabel(isActiveTarget)
                                    } else {
                                        removeAccountConfirmLabel(isActiveTarget)
                                    }
                                    )
                            }
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

private fun ChildrenBuilder.sectionToggle(label: String, expanded: Boolean, count: Int? = null, onToggle: () -> Unit) {
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
            +(if (count != null) "$label ($count)" else label)
        }
    }
}

/** Row for a joined group with no kind:39000 on the relay (stale kind:10009
 *  pin). Visually muted, click-disabled — only the trailing 'forget' button
 *  is interactive. Removes the pin so the relay's catalogue stops shadowing
 *  it on the user's other devices too (forgetGroup republishes kind:10009). */
private fun ChildrenBuilder.orphanedGroupRow(groupId: String, onForget: () -> Unit) {
    div {
        key = "orphan-$groupId"
        className = ClassName("sidebar-group orphan")
        title = "This group is no longer on the relay. Click the trash to remove it from your pins."
        WebAvatar {
            seed = groupId
            kind = AvatarKind.GROUP
            name = groupId
            cls = "group-icon-sm"
        }
        span {
            className = ClassName("sidebar-group-name")
            // Show a shortened hex id — there's no name (no kind:39000).
            +(groupId.take(12) + "…")
        }
        button {
            className = ClassName("sidebar-group-forget")
            title = "Forget this group"
            onClick = { event ->
                event.stopPropagation()
                onForget()
            }
            icon(Ic.Delete)
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
            seed = group.id
            kind = AvatarKind.GROUP
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
