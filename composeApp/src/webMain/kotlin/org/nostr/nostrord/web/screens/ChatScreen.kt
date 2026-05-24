package org.nostr.nostrord.web.screens

import kotlinx.browser.window
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.ImageViewer
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.WebZapController
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.memberSkeleton
import org.nostr.nostrord.web.components.messageSkeleton
import org.nostr.nostrord.web.components.uploadBlob
import org.nostr.nostrord.web.components.zapBadge
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
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

external interface ChatScreenProps : Props {
    var group: GroupMetadata
    var onLeave: () -> Unit

    /** A deep-linked (&e=) message to scroll to + highlight once it's loaded, or null. */
    var scrollToMessageId: String?

    /** Called once the [scrollToMessageId] target has been scrolled into view. */
    var onScrolledToMessage: () -> Unit
}

// Window (seconds) for grouping consecutive messages from the same author.
private const val GROUP_WINDOW = 5 * 60

/** Parent message id of a reply — the "q" tag with a 64-char hex event id (kind 9 only). */
private fun parentMessageOf(message: org.nostr.nostrord.network.NostrGroupClient.NostrMessage): String? {
    if (message.kind != 9) return null
    return message.tags
        .firstOrNull { it.size >= 2 && it[0] == "q" && it[1].length == 64 && it[1].all { c -> c.isLetterOrDigit() } }
        ?.get(1)
}

private fun copyToClipboard(text: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
}

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
        val joinedByRelay = useStateFlow(repo.joinedGroupsByRelay)
        val userMetadata = useStateFlow(repo.userMetadata)
        val reactionsByMsg = useStateFlow(repo.reactions)
        val zapsByMsg = useStateFlow(repo.zaps)
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val isLoadingMore = useStateFlow(repo.isLoadingMore)[group.id] ?: false
        val hasMore = useStateFlow(repo.hasMoreMessages)[group.id] ?: true
        val membersLoading = group.id in useStateFlow(repo.loadingMembers)
        val myPubkey = repo.getPublicKey()

        val messages = messagesByGroup[group.id].orEmpty().sortedBy { it.createdAt }
        val messagesById = messages.associateBy { it.id }
        val members = membersByGroup[group.id].orEmpty()
        val admins = adminsByGroup[group.id].orEmpty().toSet()
        val adminMembers = members.filter { it in admins }
        val plainMembers = members.filter { it !in admins }
        // Can post = an actual member (kind:39002), or in our list for an open group.
        val isMember = myPubkey != null && myPubkey in members
        val inMyList = group.id in joinedByRelay[relayUrl].orEmpty()
        val canPost = isMember || (group.isOpen && inMyList)
        // Pending = we sent a join request (kind 9021) but aren't a member yet.
        val isPending = !canPost && myPubkey != null && messages.any { it.kind == 9021 && it.pubkey == myPubkey }

        val (draft, setDraft) = useState { "" }
        val (membersOpen, setMembersOpen) = useState { false }
        val (infoOpen, setInfoOpen) = useState { false }
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        val (replyingToId, setReplyingToId) = useState<String?> { null }
        // The deep-linked message currently flashing (cleared after the highlight animation).
        val (highlightId, setHighlightId) = useState<String?> { null }
        // moderation modal: edit | share | members | addmember | invite | requests | subgroup | children
        val (modal, setModal) = useState<String?> { null }

        // Scroll/pagination bookkeeping (refs so they don't trigger re-render).
        val loadingOlder = useRef(false)
        val prevScrollHeight = useRef(0.0)
        val atBottom = useRef(true)

        // Load messages + author/member metadata when the group (or its rosters) change.
        useEffect(group.id) {
            launchApp { repo.requestGroupMessages(group.id) }
        }
        useEffect(group.id, members.size, messages.size) {
            val pubkeys = (members + messages.map { it.pubkey }).toSet()
            if (pubkeys.isNotEmpty()) launchApp { repo.requestUserMetadata(pubkeys) }
        }
        // After messages change: restore position when older messages were prepended,
        // otherwise pin to the bottom only if the user was already near it.
        useEffect(messages.size) {
            val el = document.getElementById(ElementId("chat-messages")) ?: return@useEffect
            when {
                loadingOlder.current == true -> {
                    el.scrollTop = el.scrollHeight.toDouble() - (prevScrollHeight.current ?: 0.0)
                    loadingOlder.current = false
                }
                atBottom.current == true -> el.scrollTop = el.scrollHeight.toDouble()
            }
        }
        // Deep-link target: once the message is loaded, scroll it into view and flash it.
        // Runs after the auto-scroll effect so it wins the race on group entry. Waits across
        // message loads (keeps trying as messages.size grows) until the target appears.
        useEffect(props.scrollToMessageId, messages.size) {
            val target = props.scrollToMessageId ?: return@useEffect
            if (target !in messagesById) return@useEffect
            val el = document.getElementById(ElementId("msg-$target")) ?: return@useEffect
            el.asDynamic().scrollIntoView(js("({ behavior: 'smooth', block: 'center' })"))
            setHighlightId(target)
            props.onScrolledToMessage()
            window.setTimeout({ setHighlightId(null) }, 2_600)
        }

        fun send() {
            val text = draft.trim()
            if (text.isEmpty()) return
            val replyId = replyingToId
            setDraft("")
            setReplyingToId(null)
            launchApp { repo.sendMessage(group.id, text, replyToMessageId = replyId) }
        }

        fun join() {
            launchApp { repo.joinGroup(group.id) }
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
                        icon(Ic.People)
                    }
                    if (!canPost) {
                        if (isPending) {
                            span {
                                className = ClassName("chat-pending")
                                +"Request pending"
                            }
                        } else {
                            button {
                                className = ClassName("chat-join-btn")
                                onClick = { join() }
                                +(if (!group.isOpen) "Request to Join" else "Join")
                            }
                        }
                    } else {
                        button {
                            className = ClassName("chat-icon-btn")
                            onClick = { setMenuOpen(!menuOpen) }
                            icon(Ic.MoreVert)
                        }
                    }

                    if (menuOpen && canPost) {
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
                    onScroll = { event ->
                        val el = event.currentTarget
                        val sh = el.scrollHeight.toDouble()
                        atBottom.current = (sh - el.scrollTop - el.clientHeight.toDouble()) < 120.0
                        if (el.scrollTop < 80.0 && hasMore && !isLoadingMore && loadingOlder.current != true) {
                            loadingOlder.current = true
                            prevScrollHeight.current = sh
                            launchApp { repo.loadMoreMessages(group.id) }
                        }
                    }
                    if (isLoadingMore && messages.isNotEmpty()) {
                        div {
                            className = ClassName("chat-loading-more")
                            +"Loading earlier messages…"
                        }
                    }
                    if (isLoadingMore && messages.isEmpty()) {
                        repeat(8) { messageSkeleton() }
                    } else if (messages.isEmpty()) {
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
                            val parent = parentMessageOf(message)?.let { messagesById[it] }
                            val replyPreview =
                                parent?.let {
                                    displayName(it.pubkey, userMetadata[it.pubkey]) to
                                        it.content.replace('\n', ' ').trim().take(120)
                                }
                            val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://")
                            val authorMeta = userMetadata[message.pubkey]
                            val zapInfo = zapsByMsg[message.id]
                            MessageRow {
                                key = message.id
                                domId = "msg-${message.id}"
                                highlighted = message.id == highlightId
                                pubkey = message.pubkey
                                name = displayName(message.pubkey, userMetadata[message.pubkey])
                                avatarUrl = userMetadata[message.pubkey]?.picture
                                time = formatTime(message.createdAt)
                                content = message.content
                                this.firstInGroup = firstInGroup
                                isAuthorAdmin = message.pubkey in admins
                                reactions = reactionsByMsg[message.id].orEmpty()
                                this.myPubkey = myPubkey
                                this.userMetadata = userMetadata
                                canZap =
                                    message.pubkey != myPubkey &&
                                    (!authorMeta?.lud16.isNullOrBlank() || !authorMeta?.lud06.isNullOrBlank())
                                zapTotalMsats = zapInfo?.totalMsats ?: 0L
                                zapCount = zapInfo?.count ?: 0
                                zappedByMe = myPubkey != null && zapInfo != null && myPubkey in zapInfo.zappers
                                onZap = { WebZapController.request(message.pubkey, message.id) }
                                replyTo = replyPreview
                                canDelete = myPubkey != null && (message.pubkey == myPubkey || myPubkey in admins)
                                messageLink = "https://nostrord.com/open/?relay=$relayHost&group=${group.id}&e=${message.id}"
                                eventJson = eventJsonOf(message)
                                onUser = { setProfilePubkey(it) }
                                onReply = { setReplyingToId(message.id) }
                                onReact = { emoji ->
                                    launchApp { repo.sendReaction(group.id, message.id, message.pubkey, emoji) }
                                }
                                onDelete = { launchApp { repo.deleteMessage(group.id, message.id) } }
                            }
                        }
                    }
                }

                if (!canPost) {
                    // Not a member — prompt to join (or show pending) instead of the composer.
                    div {
                        className = ClassName("composer-join")
                        if (isPending) {
                            span { +"Your request to join is pending approval." }
                        } else {
                            span { +"Join the group to send messages" }
                            button {
                                className = ClassName("composer-join-btn")
                                onClick = { join() }
                                +(if (!group.isOpen) "Request to Join" else "Join Now")
                            }
                        }
                    }
                } else {
                    // Reply banner (above the composer)
                    replyingToId?.let { id ->
                        messagesById[id]?.let { parent ->
                            div {
                                className = ClassName("composer-reply")
                                span {
                                    +"Replying to "
                                    span {
                                        className = ClassName("composer-reply-name")
                                        +displayName(parent.pubkey, userMetadata[parent.pubkey])
                                    }
                                }
                                button {
                                    className = ClassName("composer-reply-close")
                                    onClick = { setReplyingToId(null) }
                                    icon(Ic.Close)
                                }
                            }
                        }
                    }

                    // Composer
                    div {
                        className = ClassName(if (replyingToId != null) "composer replying" else "composer")
                        UploadButton {
                            cls = "composer-btn"
                            icon = Ic.AttachFile
                            onUploaded = { url -> setDraft { prev -> if (prev.isBlank()) url else "$prev $url" } }
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
                            // Ctrl/Cmd+V of an image: upload it and append the URL to the draft.
                            onPaste = { event ->
                                val items = event.asDynamic().clipboardData?.items
                                val count = (items?.length as? Int) ?: 0
                                for (i in 0 until count) {
                                    val item = items[i]
                                    val type = item.type.unsafeCast<String?>()
                                    if (item.kind == "file" && type != null && type.startsWith("image/")) {
                                        val file = item.getAsFile()
                                        if (file != null) {
                                            event.preventDefault()
                                            launchApp {
                                                val url = uploadBlob(file)
                                                if (url != null) {
                                                    setDraft { prev -> if (prev.isBlank()) url else "$prev $url" }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        button {
                            className = ClassName("composer-btn")
                            icon(Ic.EmojiEmotions)
                        }
                        button {
                            className = ClassName(if (draft.isNotBlank()) "composer-send active" else "composer-send")
                            disabled = draft.isBlank()
                            onClick = { send() }
                            icon(Ic.Send)
                        }
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
                        icon(Ic.PersonAdd)
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
                    if (membersLoading && members.isEmpty()) {
                        repeat(6) { memberSkeleton() }
                    } else {
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

external interface MessageRowProps : Props {
    var domId: String
    var highlighted: Boolean
    var pubkey: String
    var name: String
    var avatarUrl: String?
    var time: String
    var content: String
    var firstInGroup: Boolean
    var isAuthorAdmin: Boolean
    var reactions: Map<String, GroupManager.ReactionInfo>
    var myPubkey: String?
    var userMetadata: Map<String, UserMetadata>
    var replyTo: Pair<String, String>?
    var canDelete: Boolean
    var canZap: Boolean
    var zapTotalMsats: Long
    var zapCount: Int
    var zappedByMe: Boolean
    var messageLink: String
    var eventJson: String
    var onUser: (String) -> Unit
    var onReply: () -> Unit
    var onReact: (String) -> Unit
    var onZap: () -> Unit
    var onDelete: () -> Unit
}

private val MessageRow =
    FC<MessageRowProps> { props ->
        val (menuOpen, setMenuOpen) = useState { false }

        div {
            id = ElementId(props.domId)
            className =
                ClassName(
                    (if (props.firstInGroup) "msg first" else "msg grouped") +
                        (if (menuOpen) " menu-open" else "") +
                        (if (props.highlighted) " highlight" else ""),
                )
            onContextMenu = { event ->
                event.preventDefault()
                setMenuOpen(true)
            }

            div {
                className = ClassName("msg-gutter")
                if (props.firstInGroup) {
                    WebAvatar {
                        url = props.avatarUrl
                        name = props.name
                        cls = "msg-avatar clickable"
                        onClick = { props.onUser(props.pubkey) }
                    }
                } else {
                    span {
                        className = ClassName("msg-hover-time")
                        +props.time
                    }
                }
            }
            div {
                className = ClassName("msg-body")
                props.replyTo?.let { reply ->
                    div {
                        className = ClassName("msg-reply")
                        div { className = ClassName("msg-reply-bar") }
                        div {
                            className = ClassName("msg-reply-content")
                            div {
                                className = ClassName("msg-reply-author")
                                +reply.first
                            }
                            div {
                                className = ClassName("msg-reply-text")
                                +reply.second
                            }
                        }
                    }
                }
                if (props.firstInGroup) {
                    div {
                        className = ClassName("msg-meta")
                        span {
                            className = ClassName("msg-author clickable")
                            onClick = { props.onUser(props.pubkey) }
                            +props.name
                        }
                        if (props.isAuthorAdmin) {
                            span {
                                className = ClassName("msg-admin")
                                +"ADMIN"
                            }
                        }
                        span {
                            className = ClassName("msg-time")
                            +props.time
                        }
                    }
                }
                div {
                    className = ClassName("msg-text")
                    renderMessageContent(props.content, props.userMetadata, props.onUser)
                }
                if (props.reactions.isNotEmpty()) {
                    div {
                        className = ClassName("msg-reactions")
                        props.reactions.forEach { (emoji, info) ->
                            val mine = props.myPubkey != null && props.myPubkey in info.reactors
                            button {
                                className = ClassName(if (mine) "reaction-badge mine" else "reaction-badge")
                                onClick = { props.onReact(emoji) }
                                val emojiUrl = info.emojiUrl
                                if (!emojiUrl.isNullOrBlank()) {
                                    img {
                                        className = ClassName("reaction-emoji")
                                        src = emojiUrl
                                        alt = emoji
                                    }
                                } else {
                                    +emoji
                                }
                                span {
                                    className = ClassName("reaction-count")
                                    +info.reactors.size.toString()
                                }
                            }
                        }
                    }
                }
                if (props.zapTotalMsats > 0) {
                    div {
                        className = ClassName("msg-zaps")
                        zapBadge(props.zapTotalMsats, props.zapCount, props.zappedByMe) {
                            if (props.canZap) props.onZap()
                        }
                    }
                }
            }

            // Hover action toolbar (top-right)
            div {
                className = ClassName("msg-actions")
                button {
                    className = ClassName("msg-action-btn")
                    title = "Add reaction"
                    onClick = { props.onReact("👍") }
                    icon(Ic.EmojiEmotions)
                }
                button {
                    className = ClassName("msg-action-btn")
                    title = "Reply"
                    onClick = { props.onReply() }
                    icon(Ic.Reply)
                }
                if (props.canZap) {
                    button {
                        className = ClassName("msg-action-btn zap")
                        title = "Zap"
                        onClick = { props.onZap() }
                        icon(Ic.Bolt)
                    }
                }
                button {
                    className = ClassName("msg-action-btn")
                    title = "More"
                    onClick = { setMenuOpen(!menuOpen) }
                    icon(Ic.MoreVert)
                }
            }

            // Context menu (right-click or the ⋯ button)
            if (menuOpen) {
                div {
                    className = ClassName("ctx-overlay")
                    onClick = { setMenuOpen(false) }
                }
                div {
                    className = ClassName("ctx-menu")
                    ctxItem(Ic.EmojiEmotions, "Add Reaction") {
                        props.onReact("👍")
                        setMenuOpen(false)
                    }
                    ctxItem(Ic.Reply, "Reply") {
                        props.onReply()
                        setMenuOpen(false)
                    }
                    if (props.canZap) {
                        ctxItem(Ic.Bolt, "Zap") {
                            props.onZap()
                            setMenuOpen(false)
                        }
                    }
                    div { className = ClassName("ctx-divider") }
                    ctxItem(Ic.ContentCopy, "Copy Text") {
                        copyToClipboard(props.content)
                        setMenuOpen(false)
                    }
                    ctxItem(Ic.Link, "Copy Message Link") {
                        copyToClipboard(props.messageLink)
                        setMenuOpen(false)
                    }
                    ctxItem(Ic.Code, "Copy Event JSON") {
                        copyToClipboard(props.eventJson)
                        setMenuOpen(false)
                    }
                    if (props.canDelete) {
                        div { className = ClassName("ctx-divider") }
                        ctxItem(Ic.Delete, "Delete Message", danger = true) {
                            props.onDelete()
                            setMenuOpen(false)
                        }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.ctxItem(ic: Ic, label: String, danger: Boolean = false, onSelect: () -> Unit) {
    div {
        className = ClassName(if (danger) "ctx-item danger" else "ctx-item")
        onClick = { onSelect() }
        span {
            className = ClassName("ctx-item-icon")
            icon(ic)
        }
        span { +label }
    }
}

private fun eventJsonOf(message: NostrGroupClient.NostrMessage): String = buildJsonObject {
    put("id", message.id)
    put("pubkey", message.pubkey)
    put("created_at", message.createdAt)
    put("kind", message.kind)
    put("content", message.content)
    putJsonArray("tags") {
        message.tags.forEach { tag -> addJsonArray { tag.forEach { add(it) } } }
    }
}.toString()

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
                    onClick = { ImageViewer.show(url) }
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
