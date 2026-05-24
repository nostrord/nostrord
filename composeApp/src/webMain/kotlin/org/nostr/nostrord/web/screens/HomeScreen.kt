package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.groupCardSkeleton
import org.nostr.nostrord.web.components.icon
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface HomeScreenProps : Props {
    var onOpenGroup: (String) -> Unit
}

/**
 * Home / group picker — real port of the Compose HomeScreenDesktop: centered relay header,
 * My Groups / Other Groups filter chips, search, and a grid of group cards. The header cog
 * opens a "Manage relay" page (leave individual groups, remove the relay) — mirrors the
 * Compose ManageRelayContent. Shown when a relay is active but no group is open.
 */
val HomeScreen =
    FC<HomeScreenProps> { props ->
        val repo = AppModule.nostrRepository
        val currentRelay = useStateFlow(repo.currentRelayUrl)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val joinedByRelay = useStateFlow(repo.joinedGroupsByRelay)
        val kind10009 = useStateFlow(repo.kind10009Relays)
        val loadingRelays = useStateFlow(repo.loadingRelays)

        val isRelaySaved = currentRelay in kind10009
        val groupsLoading = currentRelay in loadingRelays

        val relayMeta = relayMetadata[currentRelay]
        val relayLabel =
            relayMeta?.name?.takeIf { it.isNotBlank() } ?: currentRelay.removePrefix("wss://").removePrefix("ws://")

        val groups = groupsByRelay[currentRelay].orEmpty()
        val joined = joinedByRelay[currentRelay].orEmpty()
        val myGroups = groups.filter { it.id in joined }
        val otherGroups = groups.filter { it.id !in joined }

        val (filter, setFilter) = useState { "Mine" }
        val (search, setSearch) = useState { "" }
        val (managing, setManaging) = useState { false }
        val (confirmRemove, setConfirmRemove) = useState { false }
        val (leaveGroup, setLeaveGroup) = useState<GroupMetadata?> { null }

        val base = if (filter == "Mine") myGroups else otherGroups
        val shown =
            base.filter {
                search.isBlank() ||
                    (it.name ?: it.id).contains(search, ignoreCase = true) ||
                    (it.about ?: "").contains(search, ignoreCase = true)
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
                div {
                    className = ClassName("home-header")
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
                        className = ClassName("home-relay-options")
                        onClick = {
                            if (isRelaySaved) setManaging(true) else launchApp { repo.addRelay(currentRelay) }
                        }
                        if (isRelaySaved) icon(Ic.Settings) else icon(Ic.Add)
                    }
                }

                div {
                    className = ClassName("home-toolbar")
                    div {
                        className = ClassName("home-filters")
                        homeFilter("My Groups", myGroups.size, filter == "Mine") { setFilter("Mine") }
                        homeFilter("Other Groups", otherGroups.size, filter == "Other") { setFilter("Other") }
                    }
                    input {
                        className = ClassName("home-search")
                        placeholder = "Search groups..."
                        value = search
                        onChange = { event -> setSearch(event.currentTarget.value) }
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
                                onJoin = { launchApp { repo.joinGroup(group.id) } },
                            )
                        }
                    }
                }
            }
        }

        // Confirm: remove relay
        if (confirmRemove) {
            confirmDialog(
                title = "Remove relay?",
                message = "$relayLabel will be removed from your relay list. You'll leave its groups.",
                confirmLabel = "Remove relay",
                onCancel = { setConfirmRemove(false) },
                onConfirm = {
                    setConfirmRemove(false)
                    setManaging(false)
                    launchApp { repo.removeRelay(currentRelay) }
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
                    launchApp { repo.leaveGroup(group.id) }
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
