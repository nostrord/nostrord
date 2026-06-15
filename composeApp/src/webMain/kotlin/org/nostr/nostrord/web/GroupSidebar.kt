package org.nostr.nostrord.web

import js.objects.unsafeJso
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.bannerGradientCss
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.EditGroupModal
import org.nostr.nostrord.web.modals.GroupInfoModal
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
        val unreadCounts = useStateFlow(AppModule.nostrRepository.unreadCounts)
        val (showInfo, setShowInfo) = useState { false }
        val (showCreateSubgroup, setShowCreateSubgroup) = useState { false }
        val (showManage, setShowManage) = useState { false }

        val relayGroups = groupsByRelay[route.relayUrl].orEmpty()
        val meta = relayGroups.firstOrNull { it.id == route.groupId }
        val name = meta?.name ?: route.groupId
        val currentUserPubkey = vm.getPublicKey()
        val isAdmin = currentUserPubkey != null && currentUserPubkey in groupAdmins[route.groupId].orEmpty()
        val memberCount = groupMembers[route.groupId].orEmpty().size
        val subgroupIds = childrenByParent[route.groupId].orEmpty()
        val parent = meta?.parent?.let { pid -> relayGroups.firstOrNull { it.id == pid } }

        div {
            className = ClassName("group-side")
            // Gradient identity banner (prototype GroupBanner): same hue pair as the
            // group's conic avatar, darkened for legible white text.
            // Identity banner: purely decorative. Group info opens from the info row
            // below (group-side-row), so the banner is no longer clickable.
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
                    span {
                        className = ClassName("group-side-banner-name")
                        +name
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
                    className = ClassName("group-side-row")
                    onClick = { setShowInfo(true) }
                    icon(Ic.People)
                    span {
                        className = ClassName("group-side-row-label")
                        +(if (memberCount > 0) "Members · $memberCount" else "Members")
                    }
                }
                if (isAdmin) {
                    button {
                        className = ClassName("group-side-row")
                        onClick = { setShowManage(true) }
                        icon(Ic.Settings)
                        span {
                            className = ClassName("group-side-row-label")
                            +"Manage group"
                        }
                    }
                }

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

        if (showInfo) {
            GroupInfoModal {
                group = meta ?: placeholderMeta(route.groupId)
                onClose = { setShowInfo(false) }
            }
        }
        if (showManage) {
            EditGroupModal {
                group = meta ?: placeholderMeta(route.groupId)
                onClose = { setShowManage(false) }
            }
        }
        if (showCreateSubgroup) {
            CreateGroupModal {
                onClose = { setShowCreateSubgroup(false) }
                subgroup = true
                parentGroupId = route.groupId
                relayUrl = route.relayUrl
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
