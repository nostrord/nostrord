package org.nostr.nostrord.web.screens

import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.web.mock.Mock
import org.nostr.nostrord.web.mock.MockMember
import org.nostr.nostrord.web.mock.MockMessage
import org.nostr.nostrord.web.modals.AddMemberModal
import org.nostr.nostrord.web.modals.CreateGroupModal
import org.nostr.nostrord.web.modals.EditGroupModal
import org.nostr.nostrord.web.modals.GroupInfoModal
import org.nostr.nostrord.web.modals.InviteCodesModal
import org.nostr.nostrord.web.modals.JoinRequestsModal
import org.nostr.nostrord.web.modals.ManageChildrenModal
import org.nostr.nostrord.web.modals.MemberManagementModal
import org.nostr.nostrord.web.modals.ShareGroupModal
import org.nostr.nostrord.web.modals.UserProfileModal
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface ChatScreenProps : Props {
    var group: GroupMetadata
    var onLeave: () -> Unit
}

/**
 * Chat view — layout-first React port of the Compose GroupScreenDesktop: header +
 * grouped messages + composer in the main column, members sidebar on the right (collapses
 * to a drawer on narrow screens). Mock conversation; sending/joining is stubbed.
 */
val ChatScreen =
    FC<ChatScreenProps> { props ->
        val group = props.group
        val groupName = group.name?.takeIf { it.isNotBlank() } ?: "Group"
        val (draft, setDraft) = useState { "" }
        val (membersOpen, setMembersOpen) = useState { false }
        val (infoOpen, setInfoOpen) = useState { false }
        val (profileName, setProfileName) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        // moderation modal: "edit" | "share" | "members" | "addmember" | "invite" | "requests"
        val (modal, setModal) = useState<String?> { null }

        div {
            className = ClassName(if (membersOpen) "chat members-open" else "chat")

            // Main column: header + messages + composer
            div {
                className = ClassName("chat-main")

                // Header
                div {
                    className = ClassName("chat-header")
                    div {
                        className = ClassName("chat-header-title")
                        onClick = { setInfoOpen(true) }
                        div {
                            className = ClassName("avatar-tile chat-header-icon avatar-fallback")
                            +groupName.take(1).uppercase()
                        }
                        div {
                            className = ClassName("chat-header-meta")
                            div {
                                className = ClassName("chat-header-name")
                                +groupName
                            }
                            if (!group.about.isNullOrBlank()) {
                                div {
                                    className = ClassName("chat-header-about")
                                    +group.about
                                }
                            }
                        }
                    }
                    button {
                        className = ClassName("chat-members-btn")
                        onClick = { setMembersOpen(!membersOpen) }
                        +"👥"
                    }
                    button {
                        className = ClassName("chat-icon-btn")
                        onClick = { setMenuOpen(!menuOpen) }
                        +"⋮"
                    }

                    if (menuOpen) {
                        div {
                            className = ClassName("chat-menu-overlay")
                            onClick = { setMenuOpen(false) }
                        }
                        div {
                            className = ClassName("chat-menu")
                            chatMenuItem("Edit Group") {
                                setMenuOpen(false)
                                setModal("edit")
                            }
                            chatMenuItem("Manage Members") {
                                setMenuOpen(false)
                                setModal("members")
                            }
                            chatMenuItem("Invite Codes") {
                                setMenuOpen(false)
                                setModal("invite")
                            }
                            chatMenuItem("Join Requests") {
                                setMenuOpen(false)
                                setModal("requests")
                            }
                            chatMenuItem("Create Subgroup") {
                                setMenuOpen(false)
                                setModal("subgroup")
                            }
                            chatMenuItem("Manage Children") {
                                setMenuOpen(false)
                                setModal("children")
                            }
                            chatMenuItem("Share") {
                                setMenuOpen(false)
                                setModal("share")
                            }
                            div { className = ClassName("chat-menu-divider") }
                            chatMenuItem("Delete Group", danger = true) {
                                setMenuOpen(false)
                                props.onLeave()
                            }
                            chatMenuItem("Leave Group", danger = true) {
                                setMenuOpen(false)
                                props.onLeave()
                            }
                        }
                    }
                }

                // Messages
                div {
                    className = ClassName("chat-messages")
                    div {
                        className = ClassName("chat-date")
                        span { +Mock.sampleDate }
                    }
                    systemEvent("fiatjaf joined the group")
                    Mock.sampleMessages.forEach { message ->
                        messageRow(message) { setProfileName(it) }
                    }
                }

                // Composer
                div {
                    className = ClassName("composer")
                    button {
                        className = ClassName("composer-btn")
                        +"＋"
                    }
                    input {
                        className = ClassName("composer-input")
                        placeholder = "Message $groupName"
                        value = draft
                        onChange = { event -> setDraft(event.currentTarget.value) }
                    }
                    button {
                        className = ClassName("composer-btn")
                        +"😊"
                    }
                    button {
                        className = ClassName(if (draft.isNotBlank()) "composer-send active" else "composer-send")
                        disabled = draft.isBlank()
                        onClick = { setDraft("") }
                        +"➤"
                    }
                }
            }

            // Member sidebar
            div {
                className = ClassName("member-backdrop")
                onClick = { setMembersOpen(false) }
            }
            div {
                className = ClassName("member-sidebar")
                div {
                    className = ClassName("member-header")
                    span { +"Members — ${Mock.sampleMembers.size}" }
                    button {
                        className = ClassName("member-add-btn")
                        onClick = { setModal("addmember") }
                        +"＋"
                    }
                }
                div {
                    className = ClassName("member-search")
                    input {
                        className = ClassName("member-search-input")
                        placeholder = "Search members"
                    }
                }
                div {
                    className = ClassName("member-scroll")
                    val online = Mock.sampleMembers.filter { it.online }
                    val offline = Mock.sampleMembers.filter { !it.online }
                    if (online.isNotEmpty()) {
                        memberSection("Online", online.size)
                        online.forEach { member -> memberRow(member, online = true) { setProfileName(it) } }
                    }
                    if (offline.isNotEmpty()) {
                        memberSection("Offline", offline.size)
                        offline.forEach { member -> memberRow(member, online = false) { setProfileName(it) } }
                    }
                }
            }

            if (infoOpen) {
                GroupInfoModal {
                    this.group = group
                    onClose = { setInfoOpen(false) }
                }
            }
            profileName?.let { name ->
                UserProfileModal {
                    this.name = name
                    onClose = { setProfileName(null) }
                }
            }

            when (modal) {
                "edit" ->
                    EditGroupModal {
                        this.group = group
                        onClose = { setModal(null) }
                    }
                "share" ->
                    ShareGroupModal {
                        this.group = group
                        onClose = { setModal(null) }
                    }
                "members" -> MemberManagementModal { onClose = { setModal(null) } }
                "addmember" -> AddMemberModal { onClose = { setModal(null) } }
                "invite" -> InviteCodesModal { onClose = { setModal(null) } }
                "requests" -> JoinRequestsModal { onClose = { setModal(null) } }
                "subgroup" ->
                    CreateGroupModal {
                        subgroup = true
                        onClose = { setModal(null) }
                    }
                "children" -> ManageChildrenModal { onClose = { setModal(null) } }
            }
        }
    }

private fun ChildrenBuilder.chatMenuItem(label: String, danger: Boolean = false, onSelect: () -> Unit) {
    div {
        className = ClassName(if (danger) "chat-menu-item danger" else "chat-menu-item")
        onClick = { onSelect() }
        +label
    }
}

private fun ChildrenBuilder.messageRow(message: MockMessage, onUser: (String) -> Unit) {
    div {
        className = ClassName(if (message.firstInGroup) "msg first" else "msg grouped")
        div {
            className = ClassName("msg-gutter")
            if (message.firstInGroup) {
                div {
                    className = ClassName("avatar-tile msg-avatar avatar-fallback clickable")
                    onClick = { onUser(message.author) }
                    +message.author.take(1).uppercase()
                }
            } else {
                span {
                    className = ClassName("msg-hover-time")
                    +message.time
                }
            }
        }
        div {
            className = ClassName("msg-body")
            if (message.firstInGroup) {
                div {
                    className = ClassName("msg-meta")
                    span {
                        className = ClassName("msg-author clickable")
                        onClick = { onUser(message.author) }
                        +message.author
                    }
                    if (message.admin) {
                        span {
                            className = ClassName("msg-admin")
                            +"ADMIN"
                        }
                    }
                    span {
                        className = ClassName("msg-time")
                        +message.time
                    }
                }
            }
            div {
                className = ClassName("msg-text")
                +message.content
            }
        }
    }
}

private fun ChildrenBuilder.systemEvent(text: String) {
    div {
        className = ClassName("system-event")
        span {
            className = ClassName("system-event-icon")
            +"→"
        }
        span { +text }
    }
}

private fun ChildrenBuilder.memberSection(title: String, count: Int) {
    div {
        className = ClassName("member-section")
        +"$title — $count"
    }
}

private fun ChildrenBuilder.memberRow(member: MockMember, online: Boolean, onUser: (String) -> Unit) {
    div {
        className = ClassName("member-row")
        onClick = { onUser(member.name) }
        div {
            className = ClassName("member-avatar-wrap")
            div {
                className = ClassName(if (online) "avatar-tile member-avatar avatar-fallback" else "avatar-tile member-avatar avatar-fallback dimmed")
                +member.name.take(1).uppercase()
            }
            span {
                className = ClassName(if (online) "member-dot online" else "member-dot")
            }
        }
        span {
            className = ClassName(if (online) "member-name" else "member-name dimmed")
            +member.name
        }
        if (member.admin) {
            span {
                className = ClassName("member-admin")
                +"ADMIN"
            }
        }
    }
}
