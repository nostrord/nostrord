package org.nostr.nostrord.web

import js.objects.unsafeJso
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.GroupView
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.Portal
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.bannerGradientCss
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.ManageGroupModal
import org.nostr.nostrord.web.modals.MembersModal
import org.nostr.nostrord.web.navigation.pushRoute
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.Background
import web.cssom.ClassName

external interface GroupSidebarProps : Props {
    var route: GroupRoute
    var onNavigateGroup: (GroupRoute) -> Unit
}

/**
 * Second column when a group is open (prototype ChannelsSidebar group mode): the
 * gradient banner (group identity, opens the info modal) and the group tree below
 * it (Members row, the subgroups section, parent backlink). Mirrors the Compose
 * ui/components/layout/GroupSidebar.
 */
val GroupSidebar =
    FC<GroupSidebarProps> { props ->
        val route = props.route
        val vm = useViewModel(route.groupId) { GroupViewModel(AppModule.nostrRepository, route.groupId) }
        val groupsByRelay = useStateFlow(vm.groupsByRelay)
        val childrenByParent = useStateFlow(vm.childrenByParent)
        val groupMembers = useStateFlow(vm.groupMembers)
        val groupAdmins = useStateFlow(vm.groupAdmins)
        val relayMetadata = useStateFlow(vm.relayMetadata)
        val unreadCounts = useStateFlow(AppModule.nostrRepository.unreadCounts)
        val (showMembers, setShowMembers) = useState { false }
        val (showCreateSubgroup, setShowCreateSubgroup) = useState { false }
        val (showManage, setShowManage) = useState { false }
        // Tab the Manage modal opens on: the Members row jumps admins straight to "Members".
        val (manageTab, setManageTab) = useState<String?> { null }

        val relayGroups = groupsByRelay[route.relayUrl].orEmpty()
        val meta = relayGroups.firstOrNull { it.id == route.groupId }
        val name = meta?.name ?: route.groupId
        val currentUserPubkey = vm.getPublicKey()
        val isAdmin = currentUserPubkey != null && currentUserPubkey in groupAdmins[route.groupId].orEmpty()
        val memberCount = groupMembers[route.groupId].orEmpty().size
        val subgroupIds = childrenByParent[route.groupId].orEmpty()
        // Only relays that advertise nip29:{subgroups:true} in their NIP-11 host subgroups.
        val supportsSubgroups =
            (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])
                ?.supportsSubgroups == true
        val parent = meta?.parent?.let { pid -> relayGroups.firstOrNull { it.id == pid } }
        // Host relay shown under the group name (same reference as the discovery .group-card),
        // so the same group on two relays is told apart.
        val relayHost = route.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
        val relayIconUrl = (relayMetadata[route.relayUrl] ?: relayMetadata[route.relayUrl.normalizeRelayUrl()])?.icon

        div {
            className = ClassName("group-side")
            // Gradient identity banner (prototype GroupBanner): same hue pair as the
            // group's conic avatar, darkened for legible white text. Only the relay line is
            // a link (to the relay page); the banner itself is not clickable.
            div {
                className = ClassName("group-side-banner")
                style = unsafeJso { background = bannerGradientCss(route.groupId).unsafeCast<Background>() }
                div { className = ClassName("group-side-banner-scrim") }
                div {
                    className = ClassName("group-side-banner-row")
                    WebAvatar {
                        url = meta?.picture
                        seed = route.groupId
                        this.name = name
                        kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                        cls = "group-side-banner-avatar"
                    }
                    div {
                        className = ClassName("group-side-banner-meta")
                        span {
                            className = ClassName("group-side-banner-name")
                            +name
                        }
                        if (relayHost.isNotBlank()) {
                            div {
                                className = ClassName("group-side-banner-relay")
                                WebAvatar {
                                    url = relayIconUrl
                                    seed = route.relayUrl
                                    this.name = relayHost
                                    kind = org.nostr.nostrord.web.components.AvatarKind.RELAY
                                    cls = "group-side-banner-relay-icon"
                                }
                                // Only the hostname opens the relay, so the row isn't a block link.
                                span {
                                    className = ClassName("group-side-banner-relay-host link")
                                    title = "Open relay"
                                    onClick = {
                                        it.stopPropagation()
                                        pushRoute(RelayRoute(route.relayUrl))
                                    }
                                    +relayHost
                                }
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("group-side-body")
                parent?.let { p ->
                    button {
                        className = ClassName("group-side-row muted")
                        onClick = { props.onNavigateGroup(GroupRoute(route.relayUrl, p.id)) }
                        icon(Ic.ChevronRight)
                        span {
                            className = ClassName("group-side-row-label")
                            +(p.name ?: p.id)
                        }
                    }
                }
                button {
                    className = ClassName(if (route.view == GroupView.Chat) "group-side-row active" else "group-side-row")
                    onClick = { props.onNavigateGroup(route.copy(view = GroupView.Chat, threadRootId = null)) }
                    icon(Ic.Chat)
                    span {
                        className = ClassName("group-side-row-label")
                        +"Chat"
                    }
                }
                button {
                    className = ClassName(if (route.view == GroupView.Threads) "group-side-row active" else "group-side-row")
                    // Forum-style threads (kind:11 + kind:1111), shown in the centre pane.
                    onClick = { props.onNavigateGroup(route.copy(view = GroupView.Threads, threadRootId = null)) }
                    icon(Ic.Forum)
                    span {
                        className = ClassName("group-side-row-label")
                        +"Threads"
                    }
                }
                button {
                    className = ClassName("group-side-row")
                    // Admins manage members in the Manage modal; everyone else sees the roster.
                    onClick = {
                        if (isAdmin) {
                            setManageTab("members")
                            setShowManage(true)
                        } else {
                            setShowMembers(true)
                        }
                    }
                    icon(Ic.People)
                    span {
                        className = ClassName("group-side-row-label")
                        +(if (memberCount > 0) "Members · $memberCount" else "Members")
                    }
                }
                if (isAdmin) {
                    button {
                        className = ClassName("group-side-row")
                        onClick = {
                            setManageTab(null)
                            setShowManage(true)
                        }
                        icon(Ic.Settings)
                        span {
                            className = ClassName("group-side-row-label")
                            +"Manage group"
                        }
                    }
                }

                // Relays that can't host subgroups show no subgroups section at all.
                if (supportsSubgroups) {
                    div {
                        className = ClassName("group-side-label")
                        span { +"Subgroups · ${subgroupIds.size}" }
                        if (isAdmin) {
                            button {
                                className = ClassName("group-side-label-add")
                                title = "Add subgroup"
                                onClick = { setShowCreateSubgroup(true) }
                                icon(Ic.Add)
                            }
                        }
                    }
                    subgroupIds.forEach { subId ->
                        val sub = relayGroups.firstOrNull { it.id == subId }
                        val subName = sub?.name ?: subId
                        button {
                            key = subId
                            className = ClassName("group-side-row")
                            onClick = { props.onNavigateGroup(GroupRoute(route.relayUrl, subId)) }
                            WebAvatar {
                                url = sub?.picture
                                seed = subId
                                this.name = subName
                                kind = org.nostr.nostrord.web.components.AvatarKind.GROUP
                                cls = "group-side-row-avatar"
                            }
                            span {
                                className = ClassName("group-side-row-label")
                                +subName
                            }
                            val unread = unreadCounts[subId] ?: 0
                            if (unread > 0) {
                                span {
                                    className = ClassName("count-badge")
                                    +(if (unread > 99) "99+" else "$unread")
                                }
                            }
                        }
                    }
                    if (subgroupIds.isEmpty()) {
                        div {
                            className = ClassName("group-side-empty")
                            +"No subgroups."
                        }
                    }
                }
            }
        }

        // The sidebar lives inside the mobile nav drawer, whose `transform` would trap a
        // position:fixed overlay inside the drawer; portal these modals to <body> so they cover
        // the viewport.
        if (showMembers) {
            Portal {
                MembersModal {
                    groupId = route.groupId
                    onClose = { setShowMembers(false) }
                }
            }
        }
        if (showManage) {
            Portal {
                ManageGroupModal {
                    group = meta ?: placeholderMeta(route.groupId)
                    initialTab = manageTab
                    onClose = { setShowManage(false) }
                }
            }
        }
        if (showCreateSubgroup) {
            Portal {
                CreateGroupModal {
                    onClose = { setShowCreateSubgroup(false) }
                    subgroup = true
                    parentGroupId = route.groupId
                    relayUrl = route.relayUrl
                }
            }
        }
    }

private fun placeholderMeta(groupId: String): GroupMetadata = GroupMetadata(
    id = groupId,
    name = null,
    about = null,
    picture = null,
    isPublic = true,
    isOpen = true,
)
