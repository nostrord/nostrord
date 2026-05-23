package org.nostr.nostrord.web.screens

import kotlinx.browser.window
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.GroupHeaderBar
import org.nostr.nostrord.web.components.viewProfile
import org.nostr.nostrord.web.upload.UploadButton
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.audio
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.dom.html.ReactHTML.video
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

external interface GroupScreenProps : Props {
    var groupId: String
    var groupName: String?
}

private val QUICK_EMOJIS = listOf("👍", "❤️", "😂", "🎉")

// Consecutive messages from the same author within this window are grouped (Discord-style).
private const val GROUP_WINDOW_SECONDS = 300L

private val urlRegex = Regex("""https?://\S+""")

private fun extOf(url: String): String = url.lowercase().substringBefore('?').substringBefore('#')

private fun isImageUrl(url: String): Boolean {
    val u = extOf(url)
    return u.endsWith(".jpg") ||
        u.endsWith(".jpeg") ||
        u.endsWith(".png") ||
        u.endsWith(".gif") ||
        u.endsWith(".webp") ||
        u.endsWith(".svg") ||
        u.endsWith(".avif")
}

private fun isVideoUrl(url: String): Boolean {
    val u = extOf(url)
    return u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".mov") || u.endsWith(".m4v")
}

private fun isAudioUrl(url: String): Boolean {
    val u = extOf(url)
    return u.endsWith(".mp3") || u.endsWith(".ogg") || u.endsWith(".wav") || u.endsWith(".m4a") || u.endsWith(".flac")
}

private fun formatTime(epochSeconds: Long): String {
    val dt = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault())
    return dt.hour.toString().padStart(2, '0') + ":" + dt.minute.toString().padStart(2, '0')
}

private fun localDateOf(epochSeconds: Long) =
    Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault()).date

private fun dateLabel(epochSeconds: Long): String {
    val date = localDateOf(epochSeconds)
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    return "$month ${date.dayOfMonth}, ${date.year}"
}

private fun ChildrenBuilder.memberAvatar(picture: String?, label: String) {
    if (!picture.isNullOrBlank()) {
        img {
            className = ClassName("member-avatar")
            src = picture
            alt = ""
        }
    } else {
        div {
            className = ClassName("member-avatar avatar-fallback")
            +label.take(1).uppercase()
        }
    }
}

private fun copyToClipboard(text: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
}

private fun messageLink(relay: String, groupId: String, messageId: String): String {
    val host = relay.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    return "${window.location.origin}/?relay=$host&group=$groupId&e=$messageId"
}

private fun ChildrenBuilder.renderUrl(url: String, onImageClick: (String) -> Unit) {
    when {
        isImageUrl(url) ->
            img {
                className = ClassName("chat-image")
                src = url
                alt = ""
                onClick = { onImageClick(url) }
            }
        isVideoUrl(url) ->
            video {
                className = ClassName("chat-video")
                src = url
                controls = true
            }
        isAudioUrl(url) ->
            audio {
                className = ClassName("chat-audio")
                src = url
                controls = true
            }
        else ->
            a {
                className = ClassName("chat-link")
                href = url
                +url
            }
    }
}

private val formatRegex = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|\*([^*]+)\*|_([^_]+)_""")

/** Render a plain text run with inline markdown: `code`, **bold**, *italic*, _italic_. */
private fun ChildrenBuilder.renderText(text: String) {
    var last = 0
    for (m in formatRegex.findAll(text)) {
        if (m.range.first > last) +text.substring(last, m.range.first)
        when {
            m.groupValues[1].isNotEmpty() -> span { className = ClassName("chat-code"); +m.groupValues[1] }
            m.groupValues[2].isNotEmpty() -> span { className = ClassName("chat-bold"); +m.groupValues[2] }
            m.groupValues[3].isNotEmpty() -> span { className = ClassName("chat-italic"); +m.groupValues[3] }
            m.groupValues[4].isNotEmpty() -> span { className = ClassName("chat-italic"); +m.groupValues[4] }
        }
        last = m.range.last + 1
    }
    if (last < text.length) +text.substring(last)
}

/**
 * Render message content: text runs (with inline markdown), inline media/links, and
 * NIP-27 references (`nostr:npub/nprofile` → clickable @mention; note/nevent/naddr → a
 * short ref badge).
 */
private fun ChildrenBuilder.renderMessageContent(
    content: String,
    onImageClick: (String) -> Unit,
    resolveName: (String) -> String,
    onMention: (String) -> Unit,
) {
    val tokens = ArrayList<Pair<IntRange, Any>>()
    urlRegex.findAll(content).forEach { tokens.add(it.range to it.value) }
    Nip27.findReferenceMatches(content).forEach { (range, ref) -> tokens.add(range to ref) }
    tokens.sortBy { it.first.first }

    var last = 0
    for ((range, payload) in tokens) {
        if (range.first < last) continue
        if (range.first > last) {
            renderText(content.substring(last, range.first))
        }
        when (payload) {
            is String -> renderUrl(payload, onImageClick)
            is Nip27.NostrReference -> {
                val pubkey =
                    when (val entity = payload.entity) {
                        is Nip19.Entity.Npub -> entity.pubkey
                        is Nip19.Entity.Nprofile -> entity.pubkey
                        else -> null
                    }
                if (pubkey != null) {
                    span {
                        className = ClassName("chat-mention")
                        onClick = { onMention(pubkey) }
                        +("@" + resolveName(pubkey))
                    }
                } else {
                    span {
                        className = ClassName("chat-mention chat-ref")
                        +(payload.bech32.take(12) + "…")
                    }
                }
            }
        }
        last = range.last + 1
    }
    if (last < content.length) {
        renderText(content.substring(last))
    }
}

/**
 * Group chat — grouped messages with inline media (images open full-screen, native
 * video/audio players), links, reactions, reply context; member sidebar; composer with
 * reply banner. All driven by shared Kotlin via the bridge.
 */
val GroupScreen =
    FC<GroupScreenProps> { props ->
        val messagesByGroup = useStateFlow(AppModule.nostrRepository.messages)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val reactions = useStateFlow(AppModule.nostrRepository.reactions)
        val groupMembers = useStateFlow(AppModule.nostrRepository.groupMembers)
        val groupAdmins = useStateFlow(AppModule.nostrRepository.groupAdmins)
        val (input, setInput) = useState { "" }
        val (sending, setSending) = useState { false }
        val (replyingTo, setReplyingTo) = useState<NostrMessage?> { null }
        val (viewerImage, setViewerImage) = useState<String?> { null }
        val activeId = useStateFlow(AppModule.accountStore.activeId)
        val currentRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val (composerEmoji, setComposerEmoji) = useState { false }
        val (reactTarget, setReactTarget) = useState<NostrMessage?> { null }

        val messages = (messagesByGroup[props.groupId] ?: emptyList()).sortedBy { it.createdAt }
        val messagesById = messages.associateBy { it.id }
        val admins = groupAdmins[props.groupId] ?: emptyList()
        val adminSet = admins.toSet()
        val members = (groupMembers[props.groupId] ?: emptyList()).filter { it !in adminSet }
        val myIsAdmin = activeId != null && activeId in adminSet

        fun authorName(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (pubkey.take(8) + "…")
        }

        fun replyParentId(message: NostrMessage): String? = message.tags.find { it.size >= 2 && it[0] == "q" && it[1].length == 64 }?.get(1)

        useEffect(props.groupId) {
            AppModule.nostrRepository.requestGroupMessages(props.groupId)
        }
        useEffect(messages.size) {
            val authors = messages.map { it.pubkey }.toSet()
            if (authors.isNotEmpty()) AppModule.nostrRepository.requestUserMetadata(authors)
        }
        useEffect(members.size, admins.size) {
            val roster = (members + admins).toSet()
            if (roster.isNotEmpty()) AppModule.nostrRepository.requestUserMetadata(roster)
        }
        useEffect(messages.size) {
            document.getElementById(ElementId("chat-messages"))?.let { el ->
                el.scrollTop = el.scrollHeight.toDouble()
            }
        }

        fun send() {
            val content = input.trim()
            if (content.isEmpty() || sending) return
            val replyId = replyingTo?.id
            setSending(true)
            setInput("")
            setReplyingTo(null)
            launchApp {
                AppModule.nostrRepository.sendMessage(props.groupId, content, replyToMessageId = replyId)
                setSending(false)
            }
        }

        div {
            className = ClassName("chat-screen")

            div {
                className = ClassName("chat-main")

                GroupHeaderBar {
                    groupId = props.groupId
                    groupName = props.groupName
                }

                div {
                    className = ClassName("chat-messages")
                    id = ElementId("chat-messages")
                    messages.forEachIndexed { index, message ->
                        val prev = messages.getOrNull(index - 1)
                        val firstInGroup =
                            prev == null ||
                                prev.pubkey != message.pubkey ||
                                (message.createdAt - prev.createdAt) > GROUP_WINDOW_SECONDS
                        val parent = replyParentId(message)?.let { messagesById[it] }
                        val messageReactions = reactions[message.id]
                        val authorMeta = userMetadata[message.pubkey]

                        if (prev == null || localDateOf(message.createdAt) != localDateOf(prev.createdAt)) {
                            div {
                                key = "date-${message.id}"
                                className = ClassName("chat-date-divider")
                                span { +dateLabel(message.createdAt) }
                            }
                        }

                        div {
                            key = message.id
                            className = ClassName(if (firstInGroup) "chat-msg first" else "chat-msg grouped")

                            div {
                                className = ClassName("chat-gutter")
                                if (firstInGroup) {
                                    val picture = authorMeta?.picture
                                    if (!picture.isNullOrBlank()) {
                                        img {
                                            className = ClassName("chat-avatar")
                                            src = picture
                                            alt = ""
                                        }
                                    } else {
                                        div {
                                            className = ClassName("chat-avatar avatar-fallback")
                                            +authorName(message.pubkey).take(1).uppercase()
                                        }
                                    }
                                } else {
                                    span {
                                        className = ClassName("chat-gutter-time")
                                        +formatTime(message.createdAt)
                                    }
                                }
                            }

                            div {
                                className = ClassName("chat-body")

                                if (parent != null) {
                                    div {
                                        className = ClassName("reply-quote")
                                        +("↪ ${authorName(parent.pubkey)}: ${parent.content.take(80)}")
                                    }
                                }

                                if (firstInGroup) {
                                    div {
                                        className = ClassName("chat-msg-header")
                                        span {
                                            className = ClassName("chat-author clickable")
                                            onClick = { viewProfile(message.pubkey) }
                                            +authorName(message.pubkey)
                                        }
                                        span {
                                            className = ClassName("chat-time")
                                            +formatTime(message.createdAt)
                                        }
                                    }
                                }

                                div {
                                    className = ClassName("chat-content")
                                    renderMessageContent(
                                        message.content,
                                        { setViewerImage(it) },
                                        { authorName(it) },
                                        { viewProfile(it) },
                                    )
                                }

                                if (!messageReactions.isNullOrEmpty()) {
                                    div {
                                        className = ClassName("reactions")
                                        messageReactions.forEach { (emoji, info) ->
                                            button {
                                                key = emoji
                                                className = ClassName("reaction-badge")
                                                onClick = {
                                                    launchApp {
                                                        AppModule.nostrRepository.sendReaction(props.groupId, message.id, message.pubkey, emoji)
                                                    }
                                                }
                                                +"$emoji ${info.reactors.size}"
                                            }
                                        }
                                    }
                                }

                                div {
                                    className = ClassName("msg-actions")
                                    button {
                                        className = ClassName("msg-action")
                                        onClick = { setReplyingTo(message) }
                                        +"Reply"
                                    }
                                    QUICK_EMOJIS.forEach { emoji ->
                                        button {
                                            key = emoji
                                            className = ClassName("msg-action")
                                            onClick = {
                                                launchApp {
                                                    AppModule.nostrRepository.sendReaction(props.groupId, message.id, message.pubkey, emoji)
                                                }
                                            }
                                            +emoji
                                        }
                                    }
                                    button {
                                        className = ClassName("msg-action")
                                        onClick = { setReactTarget(message) }
                                        +"➕"
                                    }
                                    button {
                                        className = ClassName("msg-action")
                                        onClick = { copyToClipboard(message.content) }
                                        +"Copy"
                                    }
                                    button {
                                        className = ClassName("msg-action")
                                        onClick = { copyToClipboard(messageLink(currentRelay, props.groupId, message.id)) }
                                        +"Link"
                                    }
                                    if (message.pubkey == activeId || (activeId != null && activeId in adminSet)) {
                                        button {
                                            className = ClassName("msg-action msg-action-danger")
                                            onClick = { launchApp { AppModule.nostrRepository.deleteMessage(props.groupId, message.id) } }
                                            +"Delete"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                replyingTo?.let { target ->
                    div {
                        className = ClassName("reply-banner")
                        span { +"Replying to ${authorName(target.pubkey)}" }
                        button {
                            onClick = { setReplyingTo(null) }
                            +"×"
                        }
                    }
                }

                div {
                    className = ClassName("chat-composer")
                    UploadButton {
                        label = "📎"
                        accept = "image/*,video/*,audio/*"
                        onUploaded = { url -> setInput { prev -> (if (prev.isBlank()) "" else prev.trimEnd() + " ") + url } }
                    }
                    button {
                        className = ClassName("compose-emoji-btn")
                        onClick = { setComposerEmoji(true) }
                        +"😊"
                    }
                    textarea {
                        className = ClassName("chat-input")
                        value = input
                        placeholder = "Message…"
                        disabled = sending
                        rows = 1
                        onChange = { event -> setInput(event.currentTarget.value) }
                        onKeyDown = { event ->
                            if (event.key == "Enter" && !event.shiftKey) {
                                event.preventDefault()
                                send()
                            }
                        }
                    }
                    button {
                        disabled = sending || input.isBlank()
                        onClick = { send() }
                        +"Send"
                    }
                }
            }

            div {
                className = ClassName("member-sidebar")
                if (admins.isNotEmpty()) {
                    div {
                        className = ClassName("member-section-title")
                        +"Admins — ${admins.size}"
                    }
                    admins.forEach { pubkey ->
                        div {
                            key = pubkey
                            className = ClassName("member-row")
                            onClick = { viewProfile(pubkey) }
                            memberAvatar(userMetadata[pubkey]?.picture, authorName(pubkey))
                            span {
                                className = ClassName("member-name")
                                +authorName(pubkey)
                            }
                            span {
                                className = ClassName("member-badge")
                                +"admin"
                            }
                            if (myIsAdmin) {
                                span {
                                    className = ClassName("member-actions")
                                    button {
                                        className = ClassName("member-action")
                                        onClick = {
                                            it.stopPropagation()
                                            launchApp { AppModule.nostrRepository.addUser(props.groupId, pubkey, emptyList()) }
                                        }
                                        +"−admin"
                                    }
                                    button {
                                        className = ClassName("member-action danger")
                                        onClick = {
                                            it.stopPropagation()
                                            launchApp { AppModule.nostrRepository.removeUser(props.groupId, pubkey) }
                                        }
                                        +"remove"
                                    }
                                }
                            }
                        }
                    }
                }
                if (members.isNotEmpty()) {
                    div {
                        className = ClassName("member-section-title")
                        +"Members — ${members.size}"
                    }
                    members.forEach { pubkey ->
                        div {
                            key = pubkey
                            className = ClassName("member-row")
                            onClick = { viewProfile(pubkey) }
                            memberAvatar(userMetadata[pubkey]?.picture, authorName(pubkey))
                            span {
                                className = ClassName("member-name")
                                +authorName(pubkey)
                            }
                            if (myIsAdmin) {
                                span {
                                    className = ClassName("member-actions")
                                    button {
                                        className = ClassName("member-action")
                                        onClick = {
                                            it.stopPropagation()
                                            launchApp { AppModule.nostrRepository.addUser(props.groupId, pubkey, listOf("admin")) }
                                        }
                                        +"+admin"
                                    }
                                    button {
                                        className = ClassName("member-action danger")
                                        onClick = {
                                            it.stopPropagation()
                                            launchApp { AppModule.nostrRepository.removeUser(props.groupId, pubkey) }
                                        }
                                        +"remove"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            viewerImage?.let { url ->
                div {
                    className = ClassName("image-viewer-overlay")
                    onClick = { setViewerImage(null) }
                    img {
                        className = ClassName("image-viewer-img")
                        src = url
                        alt = ""
                    }
                }
            }

            if (composerEmoji) {
                EmojiPicker {
                    onPick = { emoji -> setInput { prev -> prev + emoji } }
                    onClose = { setComposerEmoji(false) }
                }
            }

            reactTarget?.let { target ->
                EmojiPicker {
                    onPick = { emoji ->
                        launchApp {
                            AppModule.nostrRepository.sendReaction(props.groupId, target.id, target.pubkey, emoji)
                        }
                    }
                    onClose = { setReactTarget(null) }
                }
            }
        }
    }
