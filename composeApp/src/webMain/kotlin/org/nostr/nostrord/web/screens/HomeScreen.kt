package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.screens.home.HomeViewModel
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.groupCardSkeleton
import org.nostr.nostrord.web.components.icon
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface HomeScreenProps : Props {
    var onOpenGroup: (String) -> Unit

    /** Opens the groups-sidebar drawer (mobile only — the home header hamburger). */
    var onOpenDrawer: () -> Unit
}

/**
 * Home / group picker — real port of the Compose HomeScreenDesktop: centered relay header,
 * My Groups / Other Groups filter chips, search, and a grid of group cards. The header cog
 * opens a "Manage relay" page (leave individual groups, remove the relay) — mirrors the
 * Compose ManageRelayContent. Shown when a relay is active but no group is open.
 */
val HomeScreen =
    FC<HomeScreenProps> { props ->
        val vm = useViewModel { HomeViewModel(AppModule.nostrRepository) }
        val currentRelay = useStateFlow(vm.currentRelayUrl)
        val relayMetadata = useStateFlow(vm.relayMetadata)
        val groupsByRelay = useStateFlow(vm.groupsByRelay)
        val joinedByRelay = useStateFlow(vm.joinedGroupsByRelay)
        val kind10009 = useStateFlow(vm.kind10009Relays)
        val loadingRelays = useStateFlow(vm.loadingRelays)
        val fullListFetched = useStateFlow(vm.fullGroupListFetchedRelays)
        val connState = useStateFlow(vm.connectionState)
        val orphanedByRelay = useStateFlow(vm.orphanedJoinedByRelay)

        val isRelaySaved = currentRelay in kind10009
        val groupsLoading = currentRelay in loadingRelays

        val relayMeta = relayMetadata[currentRelay]
        val relayLabel =
            relayMeta?.name?.takeIf { it.isNotBlank() } ?: currentRelay.removePrefix("wss://").removePrefix("ws://")

        val groups = groupsByRelay[currentRelay].orEmpty()
        val joined = joinedByRelay[currentRelay].orEmpty()
        val myGroups = groups.filter { it.id in joined }
        val otherGroups = groups.filter { it.id !in joined }
        // Joined ids without a matching kind:39000 on this relay — stale kind:10009
        // pins. Native surfaces them as stub cards in the My Groups tab so the count
        // matches reality (HomeScreen.kt:118-137). They carry no name, a fixed
        // explanatory about, and are rendered private/closed.
        val orphanedIds = orphanedByRelay[currentRelay].orEmpty()

        // Default to "Mine" only when the relay actually has joined groups —
        // mirrors native HomeScreen.kt:75-78. Without this, switching to a
        // relay where the user hasn't joined anything shows an empty "My Groups"
        // pane that requires a manual click on "Other Groups" to discover
        // anything. The useEffect below re-applies the rule when the joined
        // set flips empty <-> non-empty, but NOT on every count change — so a
        // deliberate switch to Other isn't undone on a routine join / leave.
        val hasJoinedHere = myGroups.isNotEmpty()
        val (filter, setFilter) = useState { if (hasJoinedHere) "Mine" else "Other" }
        useEffect(currentRelay, hasJoinedHere) {
            setFilter(if (hasJoinedHere) "Mine" else "Other")
        }
        // Selecting the Other Groups filter on a lazy relay triggers the full group
        // list fetch (the picker is the homescreen analogue of opening the sidebar's
        // OTHER GROUPS section). Without this, a lazy relay's Other tab stayed empty
        // until the user opened the sidebar section. Mirrors native HomeScreen.kt:171-175.
        val isRelayLazy = vm.isGroupFetchLazy(currentRelay)
        val isConnected = connState is org.nostr.nostrord.network.managers.ConnectionManager.ConnectionState.Connected
        useEffect(filter, currentRelay, isRelayLazy, currentRelay in fullListFetched, isConnected) {
            if (filter == "Other" &&
                isRelayLazy &&
                currentRelay !in fullListFetched &&
                isConnected &&
                currentRelay.isNotBlank()
            ) {
                vm.requestFullGroupList(currentRelay)
            }
        }
        val (search, setSearch) = useState { "" }
        val (managing, setManaging) = useState { false }
        val (confirmRemove, setConfirmRemove) = useState { false }
        val (leaveGroup, setLeaveGroup) = useState<GroupMetadata?> { null }
        // 3-dots header menu (Copy relay URL / Share) — mirrors native HomeScreenDesktop.
        val (relayMenuOpen, setRelayMenuOpen) = useState { false }

        val base =
            if (filter == "Mine") {
                val known = myGroups.map { it.id }.toSet()
                val stubs =
                    orphanedIds
                        .filter { it !in known }
                        .map { id ->
                            GroupMetadata(
                                id = id,
                                name = null,
                                about = "Deleted or unavailable on this relay",
                                picture = null,
                                isPublic = false,
                                isOpen = false,
                            )
                        }
                myGroups + stubs
            } else {
                otherGroups
            }
        // Native search matches name or id only (HomeScreen.kt:111-115), not about.
        val shown =
            base.filter {
                search.isBlank() ||
                    it.name?.contains(search, ignoreCase = true) == true ||
                    it.id.contains(search, ignoreCase = true)
            }

        if (managing) {
            // ── Manage relay page ────────────────────────────────────────────
            div {
                className = ClassName("manage")
                div {
                    className = ClassName("manage-header")
                    button {
                        className = ClassName("manage-back")
                        onClick = { setManaging(false) }
                        icon(Ic.ArrowBack)
                    }
                    div {
                        className = ClassName("manage-title")
                        +"Manage relay"
                    }
                }
                div {
                    className = ClassName("manage-body")
                    div {
                        className = ClassName("manage-relay-card")
                        WebAvatar {
                            url = relayMeta?.icon
                            seed = currentRelay
                            kind = AvatarKind.RELAY
                            name = relayLabel
                            cls = "manage-relay-icon"
                        }
                        div {
                            className = ClassName("manage-relay-meta")
                            div {
                                className = ClassName("manage-relay-name")
                                +relayLabel
                            }
                            div {
                                className = ClassName("manage-relay-url")
                                +currentRelay
                            }
                        }
                    }

                    div {
                        className = ClassName("manage-section")
                        +"MY GROUPS"
                    }
                    if (myGroups.isEmpty()) {
                        div {
                            className = ClassName("manage-empty")
                            +"No groups joined on this relay."
                        }
                    } else {
                        myGroups.forEach { group ->
                            div {
                                key = group.id
                                className = ClassName("manage-group-row")
                                WebAvatar {
                                    url = group.picture
                                    seed = group.id
                                    kind = AvatarKind.GROUP
                                    name = group.name ?: group.id
                                    cls = "manage-group-icon"
                                }
                                span {
                                    className = ClassName("manage-group-name")
                                    +(group.name ?: group.id)
                                }
                                button {
                                    className = ClassName("manage-leave")
                                    onClick = { setLeaveGroup(group) }
                                    +"Leave"
                                }
                            }
                        }
                    }

                    div {
                        className = ClassName("manage-actions")
                        button {
                            className = ClassName("btn-danger")
                            onClick = { setConfirmRemove(true) }
                            +"Remove relay"
                        }
                    }
                }
            }
        } else {
            // ── Group picker ─────────────────────────────────────────────────
            div {
                className = ClassName("home")
                // Mobile header (≥600px hides this): native-style row
                // [≡] [avatar] [name (flex)] [⚙/+] [⋮]
                div {
                    className = ClassName("home-header home-header-mobile")
                    button {
                        className = ClassName("home-relay-options home-drawer-btn")
                        onClick = { props.onOpenDrawer() }
                        icon(Ic.Menu)
                    }
                    WebAvatar {
                        url = relayMeta?.icon
                        seed = currentRelay
                        kind = AvatarKind.RELAY
                        name = relayLabel
                        cls = "home-relay-icon"
                    }
                    div {
                        className = ClassName("home-title")
                        +relayLabel
                    }
                    button {
                        className = ClassName("home-relay-options")
                        onClick = {
                            if (isRelaySaved) setManaging(true) else vm.addRelay(currentRelay)
                        }
                        if (isRelaySaved) icon(Ic.Settings) else icon(Ic.Add)
                    }
                    button {
                        className = ClassName("home-relay-options")
                        onClick = { setRelayMenuOpen(true) }
                        icon(Ic.MoreVert)
                    }
                }

                // Desktop header (≥600px shows this): centered column with a
                // big 64px relay avatar, title, "Choose a group…" subtitle, and
                // the cog (or +) absolute top-right. This is the pre-77c17f7
                // desktop layout the user wants preserved on wider screens.
                div {
                    className = ClassName("home-header home-header-desktop")
                    WebAvatar {
                        url = relayMeta?.icon
                        seed = currentRelay
                        kind = AvatarKind.RELAY
                        name = relayLabel
                        cls = "home-relay-icon"
                    }
                    div {
                        className = ClassName("home-title")
                        +relayLabel
                    }
                    div {
                        className = ClassName("home-subtitle")
                        +"Choose a group to join and start chatting."
                    }
                    // Top-right: manage (cog) when the relay is saved, else add (+).
                    button {
                        className = ClassName("home-relay-options home-relay-options-cog")
                        onClick = {
                            if (isRelaySaved) setManaging(true) else vm.addRelay(currentRelay)
                        }
                        if (isRelaySaved) icon(Ic.Settings) else icon(Ic.Add)
                    }
                    // 3-dots menu (Copy relay URL / Share) — same as mobile.
                    button {
                        className = ClassName("home-relay-options home-relay-options-more")
                        onClick = { setRelayMenuOpen(true) }
                        icon(Ic.MoreVert)
                    }
                }

                // Single 3-dots dropdown shared by both headers. Lives outside
                // either header div so it isn't hidden by display:none on the
                // inactive variant.
                if (relayMenuOpen) {
                    div {
                        className = ClassName("home-relay-menu-overlay")
                        onClick = { setRelayMenuOpen(false) }
                    }
                    div {
                        className = ClassName("home-relay-menu")
                        div {
                            className = ClassName("home-relay-menu-item")
                            onClick = {
                                copyToClipboard(currentRelay)
                                setRelayMenuOpen(false)
                            }
                            +"Copy relay URL"
                        }
                        div {
                            className = ClassName("home-relay-menu-item")
                            onClick = {
                                val host = currentRelay.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                                copyToClipboard("https://nostrord.com/open/?relay=$host")
                                setRelayMenuOpen(false)
                            }
                            +"Share"
                        }
                    }
                }

                div {
                    className = ClassName("home-toolbar")
                    div {
                        className = ClassName("home-filters")
                        homeFilter("My Groups", myGroups.size, filter == "Mine") { setFilter("Mine") }
                        homeFilter("Other Groups", otherGroups.size, filter == "Other") { setFilter("Other") }
                    }
                    div {
                        className = ClassName("home-search-wrap")
                        icon(Ic.Search, "home-search-icon")
                        input {
                            className = ClassName("home-search")
                            placeholder = "Search groups..."
                            value = search
                            onChange = { event -> setSearch(event.currentTarget.value) }
                            onKeyDown = { event ->
                                if (event.key == "Escape") {
                                    setSearch("")
                                    event.currentTarget.blur()
                                }
                            }
                        }
                        if (search.isNotEmpty()) {
                            button {
                                className = ClassName("search-clear-btn")
                                onClick = { setSearch("") }
                                icon(Ic.Close)
                            }
                        }
                    }
                }

                div {
                    className = ClassName("home-grid")
                    if (groupsLoading && groups.isEmpty()) {
                        repeat(6) { groupCardSkeleton() }
                    } else if (shown.isEmpty()) {
                        div {
                            className = ClassName("home-empty")
                            +(if (search.isNotBlank()) "No groups match \"$search\"" else "No groups found")
                        }
                    } else {
                        shown.forEach { group ->
                            pickGroupCard(
                                group = group,
                                isJoined = group.id in joined,
                                onOpen = { props.onOpenGroup(group.id) },
                                onJoin = { vm.joinGroup(group.id) },
                            )
                        }
                    }
                }
            }
        }

        // Confirm: remove relay. Wording branches on saved-state and whether the
        // user has joined groups here, matching native ManageRelayContent.kt:62-71.
        if (confirmRemove) {
            val removeMessage =
                if (isRelaySaved) {
                    if (myGroups.isEmpty()) {
                        "$relayLabel will be removed from your relay list."
                    } else {
                        "$relayLabel and all its groups will be removed from your list."
                    }
                } else {
                    "Your groups on $relayLabel will be removed from your list."
                }
            confirmDialog(
                title = "Remove relay?",
                message = removeMessage,
                confirmLabel = "Remove",
                onCancel = { setConfirmRemove(false) },
                onConfirm = {
                    setConfirmRemove(false)
                    setManaging(false)
                    vm.removeRelay(currentRelay)
                },
            )
        }

        // Confirm: leave a single group
        leaveGroup?.let { group ->
            confirmDialog(
                title = "Leave group?",
                message = "You will be removed from \"${group.name ?: group.id}\".",
                confirmLabel = "Leave",
                onCancel = { setLeaveGroup(null) },
                onConfirm = {
                    setLeaveGroup(null)
                    vm.leaveGroup(group.id)
                },
            )
        }
    }

private fun ChildrenBuilder.confirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onCancel() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +title
            }
            div {
                className = ClassName("modal-subtitle tight")
                +message
            }
            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-text")
                    onClick = { onCancel() }
                    +"Cancel"
                }
                button {
                    className = ClassName("btn-danger")
                    onClick = { onConfirm() }
                    +confirmLabel
                }
            }
        }
    }
}

private fun ChildrenBuilder.homeFilter(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    button {
        className = ClassName(if (active) "home-filter active" else "home-filter")
        this.onClick = { onClick() }
        +(if (count > 0) "$label ($count)" else label)
    }
}

private fun ChildrenBuilder.pickGroupCard(
    group: GroupMetadata,
    isJoined: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
) {
    div {
        className = ClassName("pick-card")
        onClick = { onOpen() }
        WebAvatar {
            url = group.picture
            seed = group.id
            kind = AvatarKind.GROUP
            name = group.name ?: group.id
            cls = "pick-icon"
        }
        div {
            className = ClassName("pick-info")
            div {
                className = ClassName("pick-name")
                +(group.name ?: group.id)
            }
            if (!group.about.isNullOrBlank()) {
                div {
                    className = ClassName("pick-about")
                    +group.about
                }
            }
            div {
                className = ClassName("pick-badges")
                if (!group.isPublic) {
                    span {
                        className = ClassName("pick-badge private")
                        +"private"
                    }
                }
                if (!group.isOpen) {
                    span {
                        className = ClassName("pick-badge closed")
                        +"invite only"
                    }
                } else {
                    span {
                        className = ClassName("pick-badge open")
                        +"open"
                    }
                }
            }
        }
        button {
            className = ClassName(if (isJoined) "pick-btn joined" else "pick-btn")
            disabled = isJoined
            onClick = {
                it.stopPropagation()
                if (!isJoined) onJoin()
            }
            +(
                if (isJoined) {
                    "Joined"
                } else if (!group.isOpen) {
                    "Request"
                } else {
                    "Join"
                }
                )
        }
    }
}
