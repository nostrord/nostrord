package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.WebAvatar
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
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

external interface ChatScreenProps : Props {
    var group: GroupMetadata
    var onLeave: () -> Unit
}

// Window (seconds) for grouping consecutive messages from the same author.
private const val GROUP_WINDOW = 5 * 60

private fun displayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: (pubkey.take(8) + "…")

/**
 * Chat view — real data port of the Compose GroupScreenDesktop: header + grouped messages
 * (live `messages` flow) + composer (`sendMessage`), members sidebar (`groupMembers` /
 * `groupAdmins`, split into Admins / Members). Opening the group requests its messages and
 * the author/member metadata. Moderation modals are wired; their submits land later.
 */
val ChatScreen =
    FC<ChatScreenProps> { props ->
        val group = props.group
        val groupName = group.name?.takeIf { it.isNotBlank() } ?: "Group"
        val repo = AppModule.nostrRepository

        val messagesByGroup = useStateFlow(repo.messages)
        val membersByGroup = useStateFlow(repo.groupMembers)
        val adminsByGroup = useStateFlow(repo.groupAdmins)
        val userMetadata = useStateFlow(repo.userMetadata)
        val reactionsByMsg = useStateFlow(repo.reactions)
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val myPubkey = repo.getPublicKey()

        val messages = messagesByGroup[group.id].orEmpty().sortedBy { it.createdAt }
        val members = membersByGroup[group.id].orEmpty()
        val admins = adminsByGroup[group.id].orEmpty().toSet()
        val adminMembers = members.filter { it in admins }
        val plainMembers = members.filter { it !in admins }

        val (draft, setDraft) = useState { "" }
        val (membersOpen, setMembersOpen) = useState { false }
        val (infoOpen, setInfoOpen) = useState { false }
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        // moderation modal: edit | share | members | addmember | invite | requests | subgroup | children
        val (modal, setModal) = useState<String?> { null }

        // Load messages + author/member metadata when the group (or its rosters) change.
        useEffect(group.id) {
            launchApp { repo.requestGroupMessages(group.id) }
        }
        useEffect(group.id, members.size, messages.size) {
            val pubkeys = (members + messages.map { it.pubkey }).toSet()
            if (pubkeys.isNotEmpty()) launchApp { repo.requestUserMetadata(pubkeys) }
        }
        // Keep the message list pinned to the latest message.
        useEffect(messages.size) {
            document.getElementById(ElementId("chat-messages"))?.let { it.scrollTop = it.scrollHeight.toDouble() }
        }

        fun send() {
            val text = draft.trim()
            if (text.isEmpty()) return
            setDraft("")
            launchApp { repo.sendMessage(group.id, text) }
        }

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
                        WebAvatar {
                            url = group.picture
                            name = groupName
                            cls = "chat-header-icon"
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
                                launchApp { repo.deleteGroup(group.id) }
                                props.onLeave()
                            }
                            chatMenuItem("Leave Group", danger = true) {
                                setMenuOpen(false)
                                launchApp { repo.leaveGroup(group.id) }
                                props.onLeave()
                            }
                        }
                    }
                }

                // Messages
                div {
                    className = ClassName("chat-messages")
                    id = ElementId("chat-messages")
                    if (messages.isEmpty()) {
                        div {
                            className = ClassName("chat-empty")
                            +"No messages yet. Say hello 👋"
                        }
                    } else {
                        messages.forEachIndexed { i, message ->
                            val prev = messages.getOrNull(i - 1)
                            val firstInGroup =
                                prev == null ||
                                    prev.pubkey != message.pubkey ||
                                    message.createdAt - prev.createdAt > GROUP_WINDOW
                            messageRow(
                                pubkey = message.pubkey,
                                name = displayName(message.pubkey, userMetadata[message.pubkey]),
                                avatarUrl = userMetadata[message.pubkey]?.picture,
                                time = formatTime(message.createdAt),
                                content = message.content,
                                firstInGroup = firstInGroup,
                                isAdmin = message.pubkey in admins,
                                reactions = reactionsByMsg[message.id].orEmpty(),
                                myPubkey = myPubkey,
                                userMetadata = userMetadata,
                                onUser = { setProfilePubkey(it) },
                                onReact = { emoji ->
                                    launchApp { repo.sendReaction(group.id, message.id, message.pubkey, emoji) }
                                },
                            )
                        }
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
                        onKeyDown = { event ->
                            if (event.key == "Enter" && !event.shiftKey) {
                                event.preventDefault()
                                send()
                            }
                        }
                    }
                    button {
                        className = ClassName("composer-btn")
                        +"😊"
                    }
                    button {
                        className = ClassName(if (draft.isNotBlank()) "composer-send active" else "composer-send")
                        disabled = draft.isBlank()
                        onClick = { send() }
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
                    span { +"Members — ${members.size}" }
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
                    if (adminMembers.isNotEmpty()) {
                        memberSection("Admins", adminMembers.size)
                        adminMembers.forEach { pubkey ->
                            memberRow(pubkey, displayName(pubkey, userMetadata[pubkey]), userMetadata[pubkey]?.picture, isAdmin = true) { setProfilePubkey(it) }
                        }
                    }
                    if (plainMembers.isNotEmpty()) {
                        memberSection("Members", plainMembers.size)
                        plainMembers.forEach { pubkey ->
                            memberRow(pubkey, displayName(pubkey, userMetadata[pubkey]), userMetadata[pubkey]?.picture, isAdmin = false) { setProfilePubkey(it) }
                        }
                    }
                    if (members.isEmpty()) {
                        div {
                            className = ClassName("member-section")
                            +"No members yet"
                        }
                    }
                }
            }

            if (infoOpen) {
                GroupInfoModal {
                    this.group = group
                    onClose = { setInfoOpen(false) }
                }
            }
            profilePubkey?.let { pubkey ->
                UserProfileModal {
                    this.pubkey = pubkey
                    onClose = { setProfilePubkey(null) }
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
                "members" ->
                    MemberManagementModal {
                        groupId = group.id
                        onClose = { setModal(null) }
                    }
                "addmember" ->
                    AddMemberModal {
                        groupId = group.id
                        onClose = { setModal(null) }
                    }
                "invite" ->
                    InviteCodesModal {
                        groupId = group.id
                        onClose = { setModal(null) }
                    }
                "requests" ->
                    JoinRequestsModal {
                        groupId = group.id
                        onClose = { setModal(null) }
                    }
                "subgroup" ->
                    CreateGroupModal {
                        subgroup = true
                        parentGroupId = group.id
                        this.relayUrl = relayUrl
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

private fun ChildrenBuilder.messageRow(
    pubkey: String,
    name: String,
    avatarUrl: String?,
    time: String,
    content: String,
    firstInGroup: Boolean,
    isAdmin: Boolean,
    reactions: Map<String, GroupManager.ReactionInfo>,
    myPubkey: String?,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
    onReact: (String) -> Unit,
) {
    div {
        className = ClassName(if (firstInGroup) "msg first" else "msg grouped")
        div {
            className = ClassName("msg-gutter")
            if (firstInGroup) {
                WebAvatar {
                    url = avatarUrl
                    this.name = name
                    cls = "msg-avatar clickable"
                    onClick = { onUser(pubkey) }
                }
            } else {
                span {
                    className = ClassName("msg-hover-time")
                    +time
                }
            }
        }
        div {
            className = ClassName("msg-body")
            if (firstInGroup) {
                div {
                    className = ClassName("msg-meta")
                    span {
                        className = ClassName("msg-author clickable")
                        onClick = { onUser(pubkey) }
                        +name
                    }
                    if (isAdmin) {
                        span {
                            className = ClassName("msg-admin")
                            +"ADMIN"
                        }
                    }
                    span {
                        className = ClassName("msg-time")
                        +time
                    }
                }
            }
            div {
                className = ClassName("msg-text")
                renderMessageContent(content, userMetadata, onUser)
            }
            div {
                className = ClassName("msg-reactions")
                reactions.forEach { (emoji, info) ->
                    val mine = myPubkey != null && myPubkey in info.reactors
                    button {
                        className = ClassName(if (mine) "reaction-badge mine" else "reaction-badge")
                        onClick = { onReact(emoji) }
                        +emoji
                        span {
                            className = ClassName("reaction-count")
                            +info.reactors.size.toString()
                        }
                    }
                }
                button {
                    className = ClassName("reaction-add")
                    title = "React"
                    onClick = { onReact("👍") }
                    +"+"
                }
            }
        }
    }
}

private val URL_REGEX =
    Regex("(https?://[^\\s]+)|(nostr:(?:npub1|nprofile1)[0-9a-z]+)|\\b((?:npub1|nprofile1)[0-9a-z]{20,})")
private val IMAGE_EXT = Regex("\\.(jpg|jpeg|png|gif|webp|avif|svg)(\\?.*)?$", RegexOption.IGNORE_CASE)

/** Render message text with clickable links, inline images and NIP-27 mentions. */
private fun ChildrenBuilder.renderMessageContent(
    content: String,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    var last = 0
    for (match in URL_REGEX.findAll(content)) {
        if (match.range.first > last) +content.substring(last, match.range.first)
        val token = match.value
        if (token.startsWith("http")) {
            val url = token.trimEnd('.', ',', ')', '!', '?', ';', ':')
            if (IMAGE_EXT.containsMatchIn(url)) {
                img {
                    className = ClassName("msg-image")
                    src = url
                    alt = ""
                }
            } else {
                a {
                    className = ClassName("msg-link")
                    href = url
                    +url
                }
            }
            if (url.length < token.length) +token.substring(url.length)
        } else {
            val pubkey =
                when (val entity = Nip19.decode(token.removePrefix("nostr:"))) {
                    is Nip19.Entity.Npub -> entity.pubkey
                    is Nip19.Entity.Nprofile -> entity.pubkey
                    else -> null
                }
            if (pubkey != null) {
                span {
                    className = ClassName("msg-mention")
                    onClick = { onUser(pubkey) }
                    +"@${displayName(pubkey, userMetadata[pubkey])}"
                }
            } else {
                +token
            }
        }
        last = match.range.last + 1
    }
    if (last < content.length) +content.substring(last)
}

private fun ChildrenBuilder.memberSection(title: String, count: Int) {
    div {
        className = ClassName("member-section")
        +"$title — $count"
    }
}

private fun ChildrenBuilder.memberRow(pubkey: String, name: String, avatarUrl: String?, isAdmin: Boolean, onUser: (String) -> Unit) {
    div {
        className = ClassName("member-row")
        onClick = { onUser(pubkey) }
        div {
            className = ClassName("member-avatar-wrap")
            WebAvatar {
                url = avatarUrl
                this.name = name
                cls = "member-avatar"
            }
        }
        span {
            className = ClassName("member-name")
            +name
        }
        if (isAdmin) {
            span {
                className = ClassName("member-admin")
                +"ADMIN"
            }
        }
    }
}
