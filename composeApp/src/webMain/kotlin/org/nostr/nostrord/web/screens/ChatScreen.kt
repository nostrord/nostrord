package org.nostr.nostrord.web.screens

import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupLoadingState
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.emoji.QuickReactions
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.utils.normalizeForSearch
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.ChatImage
import org.nostr.nostrord.web.components.ChatMessageList
import org.nostr.nostrord.web.components.ChatVideo
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.WebZapController
import org.nostr.nostrord.web.components.YouTubeEmbed
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.memberSkeleton
import org.nostr.nostrord.web.components.messageSkeleton
import org.nostr.nostrord.web.components.uploadBlob
import org.nostr.nostrord.web.components.useEscClose
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
import react.dom.html.ReactHTML.br
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.html.HTMLDivElement
import web.html.HTMLTextAreaElement
import kotlin.math.abs

external interface ChatScreenProps : Props {
    var group: GroupMetadata
    var onLeave: () -> Unit

    /** A deep-linked (&e=) message to scroll to + highlight once it's loaded, or null. */
    var scrollToMessageId: String?

    /** Called once the [scrollToMessageId] target has been scrolled into view. */
    var onScrolledToMessage: () -> Unit

    /** Open another group (from a decoded naddr reference): (groupId, relayUrl?). */
    var onNavigateGroup: (String, String?) -> Unit

    /** Opens the groups-sidebar drawer (mobile only — the chat header hamburger). */
    var onOpenDrawer: () -> Unit
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

/** Reply-preview payload: author name, plain-text body, and the parent event's tags for emoji resolution. */
data class ReplyPreviewData(
    val author: String,
    val content: String,
    val tags: List<List<String>>,
)

private val NOSTR_URI_REGEX =
    Regex(
        """nostr:(npub1[a-z0-9]+|nprofile1[a-z0-9]+|note1[a-z0-9]+|nevent1[a-z0-9]+|naddr1[a-z0-9]+)""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Replace `nostr:` mentions with their @name, [note], [event], [article] or
 * `%groupId` short form. Used in plain-text spans (reply previews, search
 * snippets) where the full pill rendering would be visual overkill.
 *
 * Web counterpart of `processMentionsInContent` in commonMain (which lives
 * inside the uiComposeMain source set and isn't visible from webMain).
 */
private fun processMentions(content: String, userMetadata: Map<String, UserMetadata>): String = NOSTR_URI_REGEX.replace(content) { match ->
    val bech32 = match.groupValues[1]
    runCatching {
        when (val entity = Nip19.decode(bech32)) {
            is Nip19.Entity.Npub -> {
                val name = userMetadata[entity.pubkey]?.let { it.displayName ?: it.name }
                if (!name.isNullOrBlank()) "@$name" else "@${entity.pubkey.take(8)}…"
            }
            is Nip19.Entity.Nprofile -> {
                val name = userMetadata[entity.pubkey]?.let { it.displayName ?: it.name }
                if (!name.isNullOrBlank()) "@$name" else "@${entity.pubkey.take(8)}…"
            }
            is Nip19.Entity.Note -> "[note]"
            is Nip19.Entity.Nevent -> "[event]"
            is Nip19.Entity.Naddr -> if (entity.kind == 39000) "%${entity.identifier}" else "[article]"
            else -> match.value
        }
    }.getOrDefault(match.value)
}

private fun displayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: (pubkey.take(8) + "…")

/** Active mention being typed in the composer: the trigger (@ or %), its query, and start index. */
private data class MentionCtx(val trigger: Char, val query: String, val start: Int)

/** A single autocomplete suggestion: how to show it, a subtitle, and the `nostr:` ref to insert. */
private data class MentionMatch(
    val label: String,
    val picture: String?,
    val seed: String,
    val group: Boolean,
    val ref: String,
    val sub: String,
)

/**
 * Find the mention being typed at [cursor]: the nearest `@`/`%` that starts a word and runs
 * unbroken (no whitespace) up to the cursor. Returns null when there's no active mention.
 */
private fun detectMention(text: String, cursor: Int): MentionCtx? {
    val before = text.take(cursor)
    val idx = maxOf(before.lastIndexOf('@'), before.lastIndexOf('%'))
    if (idx < 0) return null
    val boundary = idx == 0 || before[idx - 1].isWhitespace()
    if (!boundary) return null
    val query = before.substring(idx + 1)
    if (query.any { it.isWhitespace() }) return null
    return MentionCtx(before[idx], query, idx)
}

/** A run of composer text, flagged as a resolved @mention (gold) or plain. */
private data class HlSeg(val text: String, val mention: Boolean)

/**
 * Split [text] into plain / mention runs for the composer's colored mirror. Only the literal
 * [tokens] (e.g. "@alice", "%my group") that are resolved mentions are tinted — same rule as
 * native's MentionVisualTransformation (it colors the chosen mentions, not every "@word").
 */
private fun highlightSegments(text: String, tokens: Collection<String>): List<HlSeg> {
    if (text.isEmpty()) return emptyList()
    val colored = BooleanArray(text.length)
    tokens.forEach { token ->
        if (token.isEmpty()) return@forEach
        var i = text.indexOf(token)
        while (i >= 0) {
            for (j in i until i + token.length) colored[j] = true
            i = text.indexOf(token, i + token.length)
        }
    }
    val segs = mutableListOf<HlSeg>()
    var start = 0
    for (k in 1..text.length) {
        if (k == text.length || colored[k] != colored[start]) {
            segs.add(HlSeg(text.substring(start, k), colored[start]))
            start = k
        }
    }
    return segs
}

private external interface ChatComposerProps : Props {
    var groupId: String
    var groupName: String
    var groupIsOpen: Boolean
    var canPost: Boolean
    var isPending: Boolean
    var members: List<String>
    var allGroups: List<GroupMetadata>
    var userMetadata: Map<String, UserMetadata>
    var relayUrl: String
    var relayPubkey: String?

    /** Id of the message being replied to (null = no reply), plus the parent's display name for the banner. */
    var replyingToId: String?
    var replyParentName: String?
    var onCancelReply: () -> Unit

    /** Cleared after a successful publish so the parent can drop the reply target. */
    var onSent: () -> Unit
    var onJoin: () -> Unit
}

/**
 * Composer — the message input bar (textarea + colored mention mirror, @user / %group autocomplete,
 * emoji picker, paste/drag upload, Send). Split out of [ChatScreen] so that typing only re-renders
 * this subtree: the draft and mention state live here, not at screen level, so each keystroke no
 * longer re-runs ChatScreen's message sort/filter or re-renders the message list.
 */
private val ChatComposer =
    FC<ChatComposerProps> { props ->
        val repo = AppModule.nostrRepository
        val members = props.members
        val userMetadata = props.userMetadata
        val allGroups = props.allGroups
        val relayUrl = props.relayUrl
        val groupName = props.groupName

        val (draft, setDraft) = useState { "" }
        // Tracks an in-flight sendMessage so we (a) don't double-send if the user
        // mashes Enter, and (b) keep the draft visible until the signer + relay
        // come back — clearing optimistically erased the message when a NIP-07
        // cancel or relay reject killed the publish.
        val (sending, setSending) = useState { false }
        // Active media uploads in flight (paste / drag-and-drop). The send button
        // shows a spinner instead of the Send icon while > 0.
        val (uploadCount, setUploadCount) = useState { 0 }
        // Successful uploads pending attachment. Their NIP-68 imeta tags ride along
        // on the next sendMessage so dimensions / sha256 / video poster propagate
        // (parity with native GroupScreen.pendingUploads).
        val (pendingUploads, setPendingUploads) = useState<List<UploadResult>> { emptyList() }
        // Last upload failure (too large, unsupported, auth/server). Shown in a
        // dialog instead of being swallowed silently, matching the native picker.
        val (uploadError, setUploadError) = useState<String?> { null }
        // Composer emoji picker open state.
        val (emojiOpen, setEmojiOpen) = useState { false }
        // Active @user / %group mention being typed in the composer.
        val (mention, setMention) = useState<MentionCtx?> { null }
        // displayName -> pubkeyHex for @user mentions chosen from the popup.
        val (mentions, setMentions) = useState<Map<String, String>> { emptyMap() }
        // groupName -> "nostr:naddr…" for %group mentions, resolved into the content at send.
        val (groupMentions, setGroupMentions) = useState<Map<String, String>> { emptyMap() }
        // Highlighted row in the mention popup, driven by ArrowUp/ArrowDown and hover.
        val (mentionSelected, setMentionSelected) = useState { 0 }

        val composerInputRef = useRef<HTMLTextAreaElement>(null)
        val composerHighlightRef = useRef<HTMLDivElement>(null)

        // Per-group draft: restore what was being typed when this group was last open.
        // Runs on group change only (the same component is reused across groups, so the
        // draft state would otherwise leak from one group to the next).
        useEffect(props.groupId) {
            val draftEntry = AppModule.messageDraftStore.get(props.groupId)
            setDraft(draftEntry.text)
            setMentions(draftEntry.mentions)
            setGroupMentions(draftEntry.groupMentions)
        }
        // Persist on change. The store is a plain in-memory map (no re-render), so saving
        // on every keystroke is free. On group switch this effect's deps are still the old
        // group's values, so it does not fire until the load effect above swaps them in.
        useEffect(draft, mentions, groupMentions) {
            AppModule.messageDraftStore.setText(props.groupId, draft)
            AppModule.messageDraftStore.setMentions(props.groupId, mentions)
            AppModule.messageDraftStore.setGroupMentions(props.groupId, groupMentions)
        }

        // Autocomplete suggestions for the active mention (members for @, groups for %).
        val mentionMatches: List<MentionMatch> =
            when (mention?.trigger) {
                '@' -> {
                    val normalizedQuery = mention.query.normalizeForSearch()
                    members.asSequence()
                        .map { pk -> pk to displayName(pk, userMetadata[pk]) }
                        .filter { (_, nm) -> mention.query.isBlank() || nm.normalizeForSearch().contains(normalizedQuery) }
                        .take(6)
                        .map { (pk, nm) ->
                            MentionMatch(
                                nm,
                                userMetadata[pk]?.picture,
                                pk,
                                group = false,
                                ref = "nostr:" + Nip19.encodeNpub(pk),
                                sub = pk.take(8) + "…" + pk.takeLast(4),
                            )
                        }
                        .toList()
                }
                '%' -> {
                    val normalizedQuery = mention.query.normalizeForSearch()
                    val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                    allGroups.asSequence()
                        .filter { g -> mention.query.isBlank() || (g.name ?: g.id).normalizeForSearch().contains(normalizedQuery) }
                        .take(6)
                        .map { g ->
                            val ref = "nostr:" + Nip19.encodeNaddr(g.id, relayUrl, 39000, props.relayPubkey)
                            MentionMatch(g.name ?: g.id, g.picture, g.id, group = true, ref = ref, sub = relayHost)
                        }
                        .toList()
                }
                else -> emptyList()
            }

        // Replace the typed "@query"/"%query" with the chosen suggestion.
        fun insertMention(mm: MentionMatch) {
            val m = mention ?: return
            val cursorEnd = (m.start + 1 + m.query.length).coerceAtMost(draft.length)
            val inserted = if (mm.group) "%${mm.label}" else "@${mm.label}"
            setDraft(draft.take(m.start) + inserted + " " + draft.substring(cursorEnd))
            if (mm.group) {
                setGroupMentions { it + (mm.label to mm.ref) }
            } else {
                setMentions { it + (mm.label to mm.seed) }
            }
            setMention(null)
            setMentionSelected(0)
        }

        fun isMediaMime(type: String?): Boolean = type != null &&
            (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Upload a pasted/dropped file to nostr.build and append the URL to the draft.
        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    when (val r = uploadBlob(file)) {
                        is Result.Success -> {
                            val url = r.data.url
                            setDraft { prev -> if (prev.isBlank()) url else "$prev $url" }
                            setPendingUploads { it + r.data }
                        }
                        is Result.Error -> setUploadError(r.error.message)
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        fun send() {
            var text = draft.trim()
            if (text.isEmpty() || sending) return
            // Resolve %group mentions to their nostr:naddr inline (native does this at send too);
            // @user mentions are resolved by repo.sendMessage from the mentions map (+ p-tag).
            groupMentions.forEach { (name, ref) -> text = text.replace("%$name", ref) }
            val replyId = props.replyingToId
            // NIP-68 imeta for any media uploaded into this draft (native parity).
            val imetaTags = pendingUploads.map { it.toImetaTag() }
            setSending(true)
            launchApp {
                val result =
                    repo.sendMessage(
                        props.groupId,
                        text,
                        mentions = mentions,
                        replyToMessageId = replyId,
                        extraTags = imetaTags,
                    )
                setSending(false)
                if (result is Result.Success) {
                    // Clear only after publish succeeded so a cancel/reject keeps the draft.
                    setDraft("")
                    setMentions(emptyMap())
                    setGroupMentions(emptyMap())
                    setPendingUploads(emptyList())
                    props.onSent()
                }
            }
        }

        useEffect(props.replyingToId) {
            if (props.replyingToId != null) composerInputRef.current?.focus()
        }
        useEffect(draft) {
            val el = composerInputRef.current ?: return@useEffect
            // Reset before reading scrollHeight so the field can shrink when text
            // is deleted — otherwise scrollHeight only grows.
            el.style.height = "auto"
            el.style.height = "${el.scrollHeight}px"
        }

        if (!props.canPost) {
            // Not a member — prompt to join (or show pending) instead of the composer.
            div {
                className = ClassName("composer-join")
                if (props.isPending) {
                    span { +"Your request to join is pending approval." }
                } else {
                    span { +"Join the group to send messages" }
                    button {
                        className = ClassName("composer-join-btn")
                        onClick = { props.onJoin() }
                        icon(Ic.PersonAdd)
                        span { +(if (!props.groupIsOpen) "Request to Join" else "Join Now") }
                    }
                }
            }
        } else {
            // Reply banner (above the composer)
            val parentName = props.replyParentName
            if (props.replyingToId != null && parentName != null) {
                div {
                    className = ClassName("composer-reply")
                    span {
                        +"Replying to "
                        span {
                            className = ClassName("composer-reply-name")
                            +parentName
                        }
                    }
                    button {
                        className = ClassName("composer-reply-close")
                        onClick = { props.onCancelReply() }
                        icon(Ic.Close)
                    }
                }
            }

            // Composer
            div {
                className = ClassName(if (props.replyingToId != null) "composer replying" else "composer")
                UploadButton {
                    cls = "composer-btn"
                    icon = Ic.AttachFile
                    onUploaded = { upload ->
                        val url = upload.url
                        setDraft { prev -> if (prev.isBlank()) url else "$prev $url" }
                        setPendingUploads { it + upload }
                    }
                    onError = { setUploadError(it) }
                }
                div {
                    className = ClassName("composer-input-wrap")
                    // Colored mirror painted behind the textarea: same text, but with
                    // resolved @user / %group mentions tinted gold (native parity).
                    div {
                        className = ClassName("composer-highlight")
                        ref = composerHighlightRef
                        val tokens = mentions.keys.map { "@$it" } + groupMentions.keys.map { "%$it" }
                        highlightSegments(draft, tokens).forEach { seg ->
                            if (seg.mention) {
                                span {
                                    className = ClassName("msg-mention")
                                    +seg.text
                                }
                            } else {
                                +seg.text
                            }
                        }
                    }
                    textarea {
                        ref = composerInputRef
                        onScroll = {
                            composerHighlightRef.current?.scrollTop =
                                composerInputRef.current?.scrollTop ?: 0.0
                        }
                        className = ClassName("composer-input")
                        placeholder = "Message $groupName"
                        value = draft
                        readOnly = sending
                        rows = 1
                        onChange = { event ->
                            val v = event.currentTarget.value
                            setDraft(v)
                            val cursor = (event.currentTarget.asDynamic().selectionStart as? Int) ?: v.length
                            setMention(detectMention(v, cursor))
                            setMentionSelected(0)
                        }
                        onKeyDown = { event ->
                            val hasMentions = mention != null && mentionMatches.isNotEmpty()
                            when {
                                hasMentions && event.key == "ArrowDown" -> {
                                    event.preventDefault()
                                    setMentionSelected { (it + 1).coerceAtMost(mentionMatches.size - 1) }
                                }
                                hasMentions && event.key == "ArrowUp" -> {
                                    event.preventDefault()
                                    setMentionSelected { (it - 1).coerceAtLeast(0) }
                                }
                                hasMentions && (event.key == "Enter" || event.key == "Tab") -> {
                                    event.preventDefault()
                                    insertMention(mentionMatches[mentionSelected.coerceIn(0, mentionMatches.size - 1)])
                                }
                                mention != null && event.key == "Escape" -> {
                                    event.preventDefault()
                                    setMention(null)
                                }
                                event.key == "Enter" && !event.shiftKey -> {
                                    event.preventDefault()
                                    send()
                                }
                            }
                        }
                        onBlur = { window.setTimeout({ setMention(null) }, 150) }
                        onPaste = { event ->
                            val items = event.asDynamic().clipboardData?.items
                            val count = (items?.length as? Int) ?: 0
                            for (i in 0 until count) {
                                val item = items[i]
                                val type = item.type.unsafeCast<String?>()
                                if (item.kind == "file" && isMediaMime(type)) {
                                    val file = item.getAsFile()
                                    if (file != null) {
                                        event.preventDefault()
                                        handleMediaFile(file)
                                    }
                                }
                            }
                        }
                        onDragOver = { it.preventDefault() }
                        onDrop = { event ->
                            val files = event.asDynamic().dataTransfer?.files
                            val count = (files?.length as? Int) ?: 0
                            if (count > 0) event.preventDefault()
                            for (i in 0 until count) {
                                val file = files[i]
                                val type = file.type.unsafeCast<String?>()
                                if (isMediaMime(type)) handleMediaFile(file)
                            }
                        }
                    }
                }
                if (mention != null && mentionMatches.isNotEmpty()) {
                    div {
                        className = ClassName("mention-popup")
                        div {
                            className = ClassName("mention-header")
                            +(if (mention.trigger == '%') "GROUPS" else "MEMBERS")
                        }
                        val sel = mentionSelected.coerceIn(0, mentionMatches.size - 1)
                        mentionMatches.forEachIndexed { idx, mm ->
                            div {
                                key = mm.ref
                                className = ClassName(if (idx == sel) "mention-row selected" else "mention-row")
                                onMouseDown = { e ->
                                    e.preventDefault()
                                    insertMention(mm)
                                }
                                onMouseEnter = { setMentionSelected(idx) }
                                WebAvatar {
                                    url = mm.picture
                                    seed = mm.seed
                                    this.name = mm.label
                                    kind = if (mm.group) AvatarKind.GROUP else AvatarKind.USER
                                    cls = "mention-avatar"
                                }
                                div {
                                    className = ClassName("mention-text")
                                    span {
                                        className = ClassName("mention-name")
                                        +mm.label
                                    }
                                    span {
                                        className = ClassName("mention-key")
                                        +mm.sub
                                    }
                                }
                            }
                        }
                    }
                }
                button {
                    className = ClassName(if (emojiOpen) "composer-btn active" else "composer-btn")
                    onClick = { setEmojiOpen(!emojiOpen) }
                    icon(Ic.EmojiEmotions)
                }
                button {
                    val uploading = uploadCount > 0
                    val busy = uploading || sending
                    className = ClassName(
                        if (draft.isNotBlank() || busy) "composer-send active" else "composer-send",
                    )
                    disabled = (draft.isBlank() && !uploading) || sending || uploading
                    onClick = { send() }
                    if (busy) {
                        span { className = ClassName("btn-spinner") }
                    } else {
                        icon(Ic.Send)
                    }
                }
                if (emojiOpen) {
                    div {
                        className = ClassName("emoji-overlay")
                        onClick = { setEmojiOpen(false) }
                        EmojiPicker {
                            onPick = { emoji -> setDraft { prev -> prev + emoji } }
                        }
                    }
                }
            }
            uploadError?.let { error ->
                uploadErrorDialog(error) { setUploadError(null) }
            }
        }
    }

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
        val allGroups = useStateFlow(repo.groups)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val isLoadingMore = useStateFlow(repo.isLoadingMore)[group.id] ?: false
        val hasMore = useStateFlow(repo.hasMoreMessages)[group.id] ?: true
        // Per-group GroupLoadingState. Only the Exhausted state means the relay
        // truly confirmed "no more messages" THIS session — the cached
        // `hasMore == false` from a prior visit doesn't. Used by the empty-
        // state gate below to avoid showing "No messages yet" before the
        // socket has even spoken on this open.
        val groupLoadingState = useStateFlow(repo.groupStates)[group.id]
        val connState = useStateFlow(repo.connectionState)
        val membersLoading = group.id in useStateFlow(repo.loadingMembers)
        val myPubkey = repo.getPublicKey()
        // Re-key effects that fire per-session work (requestGroupMessages,
        // metadata, polling) on activeAccountId too. Without this, switching
        // accounts while the same group is open leaves group.id unchanged, the
        // effects below don't re-run, and the new session never issues its own
        // REQ — chat sits in skeletons forever because groupStates[group.id]
        // never reaches Exhausted (the new GroupManager session has no state
        // for it).
        val activeAccountId = useStateFlow(AppModule.accountStore.activeId)

        val messages = messagesByGroup[group.id].orEmpty().sortedBy { it.createdAt }
        val messagesById = messages.associateBy { it.id }
        val members = membersByGroup[group.id].orEmpty()
        val admins = adminsByGroup[group.id].orEmpty().toSet()
        val isAdmin = myPubkey != null && myPubkey in admins
        val adminMembers = members.filter { it in admins }
        val plainMembers = members.filter { it !in admins }
        // Online = posted at least one event in the last 10 minutes. Same heuristic as the
        // native GroupScreen.kt:295 — no presence protocol, just "recently active". Drives the
        // green/grey dot on member avatars in the sidebar.
        val tenMinutesAgo = epochSeconds() - (10 * 60)
        val recentlyActiveMembers =
            messages.asSequence()
                .filter { it.createdAt >= tenMinutesAgo }
                .map { it.pubkey }
                .toSet()
        // Can post = an actual member (kind:39002), or in our list for an open group.
        val isMember = myPubkey != null && myPubkey in members
        val inMyList = group.id in joinedByRelay[relayUrl].orEmpty()
        val canPost = isMember || (group.isOpen && inMyList)
        // Pending = we sent a join request (kind 9021) but aren't a member yet.
        val isPending = !canPost && myPubkey != null && messages.any { it.kind == 9021 && it.pubkey == myPubkey }
        // Restricted: relay returned a CLOSED "restricted" frame for this group.
        // Used to render the "Private group" UI in place of skeletons when the
        // account has no read access (NIP-29 private+closed group).
        val restrictedGroups = useStateFlow(repo.restrictedGroups)
        val isGroupRestricted = group.id in restrictedGroups
        // Pending approval: joined a closed group, kind:39002 came back and
        // we're not in it. Only fires when we have DATA — !membersLoading
        // AND members.isNotEmpty(). Without those gates, an empty members
        // list during the fetch window (or right after an account swap when
        // _groupMembers was wiped + not yet re-loaded) made the screen
        // wrongly assert "Awaiting" for actual members. Native's
        // `members.isEmpty() && !group.isOpen` branch (GroupScreen.kt:237)
        // is too optimistic — better to fall through to skeleton / empty
        // state until we know than to assert pending and persist that UI
        // through every transient empty.
        val isPendingApproval =
            inMyList &&
                myPubkey != null &&
                !membersLoading &&
                members.isNotEmpty() &&
                myPubkey !in members
        // Pending join-request count — admin/closed-group only, drives the header badge.
        // Same logic as JoinRequestsModal: latest 9021 per pubkey, minus current members
        // and anyone whose most-recent event is a 9022 leave.
        val pendingJoinRequests = if (isAdmin && !group.isOpen) {
            val lastLeave =
                messages.filter { it.kind == 9022 }
                    .groupBy { it.pubkey }
                    .mapValues { (_, evs) -> evs.maxOf { it.createdAt } }
            messages.filter { it.kind == 9021 && it.pubkey !in members }
                .filter { req -> lastLeave[req.pubkey].let { it == null || req.createdAt > it } }
                .distinctBy { it.pubkey }
                .size
        } else {
            0
        }

        val (membersOpen, setMembersOpen) = useState { false }
        val (infoOpen, setInfoOpen) = useState { false }
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        val (replyingToId, setReplyingToId) = useState<String?> { null }
        // Members sidebar search query.
        val (memberQuery, setMemberQuery) = useState { "" }
        // The deep-linked message currently flashing (cleared after the highlight animation).
        val (highlightId, setHighlightId) = useState<String?> { null }
        // moderation modal: edit | share | members | addmember | invite | requests | subgroup | children
        val (modal, setModal) = useState<String?> { null }
        // Message id pending delete confirmation. Native pops an AlertDialog
        // first (GroupScreen.kt:523); the web used to call repo.deleteMessage
        // straight from the onDelete callback — one stray click on the
        // overflow menu would wipe the message with no undo.
        val (messageToDelete, setMessageToDelete) = useState<String?> { null }
        // Surface relay-side rejects of the kind:5 (e.g., "blocked: only
        // admins can delete this", "not a member"). Mirrors native's
        // deleteMessageError flow at GroupViewModel.kt:260-275 +
        // GroupScreen.kt:548-562 — the web used to swallow the failure
        // silently so the user thought the message was deleted when it
        // wasn't.
        val (deleteError, setDeleteError) = useState<String?> { null }
        // Relay rejected a kind:7 reaction (e.g., "kind 7 not allowed", or an
        // "unknown member" needing to join). Mirrors native's reactionError flow
        // (GroupViewModel.kt:148-157 + GroupScreen.kt:620-665) — the web used to
        // swallow it, so the reaction just blinked away with no explanation.
        val (reactionError, setReactionError) = useState<String?> { null }

        // Scroll/pagination bookkeeping (refs so they don't trigger re-render).
        //
        // Scroll + pagination (the latch, stall detection and scrollHeight-restore)
        // all live inside ChatMessageList now; this screen only mirrors the at-bottom
        // state for the FAB / divider dismissal.
        val atBottom = useRef(true)
        // Commands to the list (ChatMessageList owns the scroll container ref):
        // scrollKey jumps to a row (deep-link / reply), jumpNonce jumps to bottom.
        val (scrollKey, setScrollKey) = useState<String?> { null }
        val (jumpNonce, setJumpNonce) = useState { 0 }

        // "New messages" divider snapshot. Captured once on group entry, then
        // cleared the first time the user scrolls up and back down to the bottom
        // (issue #83 — divider sticks around after the user has clearly read them).
        // Tracked together with `wasNotAtBottom` so the initial auto-pin-to-bottom
        // doesn't clear it immediately on entry.
        val (lastReadSnapshot, setLastReadSnapshot) = useState<Long?> { null }
        val wasNotAtBottom = useRef(false)
        // Mirror of atBottom for re-renders the FAB needs. The ref stays as the
        // hot-path source of truth for the scroll handler; setAtBottomState is
        // only invoked on the transition so we don't re-render every scroll tick.
        val (atBottomState, setAtBottomState) = useState { true }
        // Unread count for the FAB badge — only counts unread messages from
        // *other* users, mirroring the divider's own filter. Updates whenever
        // messages or the snapshot change.
        val unreadCount =
            if (lastReadSnapshot == null) {
                0
            } else {
                messages.count { it.createdAt > lastReadSnapshot && it.pubkey != myPubkey }
            }

        // Load messages + author/member metadata when the group (or its rosters)
        // change. Re-firing covers three race windows during account swap:
        //
        //  - activeAccountId flips: applyActiveAccountChange wiped messages,
        //    groupStates, joinedGroups — the new session needs its own REQ.
        //  - isConnected goes false→true: the connectionState transition is the
        //    cleanest "client healthy again" signal. But the state can briefly
        //    stay Connected while reconnect() has already nulled primaryClient
        //    (state is updated by connectPrimary, not by disconnect).
        //  - relayUrl flips empty → non-empty: clearCurrentRelay sets the URL
        //    to '' under the swap, then loadSavedRelay restores it. The empty
        //    interval is observable through useStateFlow and is the safest
        //    gate — if relayUrl is blank, there's literally no relay to fetch
        //    from yet, regardless of what connectionState says.
        //
        // Without all three checks the REQ would silently drop into
        // clientForGroup -> null and groupStates stays Idle -> skeleton forever.
        //
        // For OTHER GROUPS specifically (non-joined): G isn't in
        // joinedGroupsByRelay so the mux doesn't auto-subscribe, AND it isn't
        // restored to groupsByRelay from storage on swap, so clientForGroup
        // falls back to the primary client. If that primary's session is mid-
        // settle (post-reconnect AUTH still pending), the REQ may silently
        // no-op (send() on a half-open socket doesn't throw) and the
        // controller is stuck in InitialLoading until the long network
        // timeout. To cover that, retry up to 5x with a 2s gap while the
        // group is still unsettled. CancellationException from the next
        // effect run / unmount tears the loop down.
        val isConnected = connState is ConnectionManager.ConnectionState.Connected
        // Also re-fire on isMember change — when an admin approves a pending
        // join request, the relay broadcasts the updated kind:39002 member
        // list and our reactive members flow flips us to a member. The
        // earlier REQ for this group (run while we were non-member) likely
        // returned empty / restricted — controller is sitting in Exhausted /
        // Error with 0 msgs and won't re-issue on its own. Re-running the
        // effect with isMember as a dep + the force-refresh block below
        // catches that moment and replays the fetch so messages stream in
        // without the user having to leave / re-enter the group.
        useEffect(group.id, activeAccountId, isConnected, relayUrl, isMember) {
            if (!isConnected) return@useEffect
            if (relayUrl.isBlank()) return@useEffect
            // Snapshot the previous read point BEFORE markGroupAsRead persists "now",
            // so the divider can anchor on the user's actual last-read message
            // through the session. Native does the same with remember(groupId).
            setLastReadSnapshot(repo.getLastReadTimestamp(group.id))
            wasNotAtBottom.current = false
            // Reset atBottom to true on group entry. Without this, the ref
            // carries the PREVIOUS group's value across the ChatScreen re-
            // render: if the user was reading mid-feed in group A (atBottom
            // = false) and opens group B, the pin-to-bottom useLayoutEffect
            // sees atBottom=false and skips, leaving B at scrollTop=0 or
            // wherever onScroll's first calculation lands. Forcing true at
            // entry lets the next pin-to-bottom commit fire as the natural
            // 'just opened, go to latest' behaviour. Subsequent user scroll
            // flips it via the onScroll handler as normal.
            atBottom.current = true
            setAtBottomState(true)
            // Preemptive reset: if the controller was already in InitialLoading
            // when we entered, it's almost certainly the stale REQ that
            // resubscribePoolRelay (NostrRepository.kt:2628) fired during the
            // reconnect — that REQ went out on the dying socket and won't get
            // an EOSE. Resetting now lets attempt 0 actually re-issue instead
            // of being a no-op (startInitialLoad rejects when state is
            // already InitialLoading). Skip the reset for legitimate
            // states (HasMore / Paginating) — those mean we already have data.
            //
            // Also reset on Exhausted / Error with zero messages: covers the
            // post-approval case where a previous non-member REQ returned
            // empty (or was refused), and now that the user is a member the
            // relay would serve the chat. Without this reset the controller
            // sits in Exhausted with 0 msgs and never tries again. Safe
            // because the retry loop below exits as soon as state settles
            // (Exhausted with 0 msgs is still settled — we just trigger one
            // extra fetch on relay reconnect of an empty group, which is
            // fine).
            val entryState = repo.groupStates.value[group.id]
            val haveMsgsAtEntry = repo.messages.value[group.id].orEmpty().isNotEmpty()
            val needsForceRefresh = entryState is GroupLoadingState.InitialLoading ||
                (
                    !haveMsgsAtEntry &&
                        (
                            entryState is GroupLoadingState.Exhausted ||
                                entryState is GroupLoadingState.Error
                            )
                    )
            if (needsForceRefresh) {
                repo.resetGroupLoadingState(group.id)
            }
            var attempts = 0
            while (attempts < 5) {
                val stateBefore = repo.groupStates.value[group.id]
                // Same defensive reset between retries: if the prior attempt's
                // REQ is still in InitialLoading at the start of the next
                // attempt, it's stuck — reset before re-issuing.
                if (stateBefore is GroupLoadingState.InitialLoading && attempts > 0) {
                    repo.resetGroupLoadingState(group.id)
                }
                repo.requestGroupMessages(group.id)
                delay(2_000)
                val msgsAfter = repo.messages.value[group.id].orEmpty().size
                val state = repo.groupStates.value[group.id]
                val settled = msgsAfter > 0 ||
                    state is GroupLoadingState.HasMore ||
                    state is GroupLoadingState.Paginating ||
                    state is GroupLoadingState.Exhausted ||
                    state is GroupLoadingState.Error
                if (settled) break
                attempts++
            }
        }
        // Auto-recover from controller PARTIAL_TIMEOUT / TIMEOUT (state=Error).
        //
        // Web cold-boot is heavier than native (SW + kotlin/js bundle + ~30
        // simultaneous websockets for kind:0 fan-out and outbox). Messages
        // arrive within the 10s controller timeout but the EOSE on the
        // primary's REQ often doesn't, so the controller flips to Error
        // with the cursor advanced. From the user's POV: 50 messages
        // visible, scroll-up does nothing because hasMore=false.
        //
        // Native has the same controller but doesn't hit this case in
        // practice because the connection settles in < 1s. Web needs an
        // explicit recovery. Manually navigating away and back already
        // works (the entry useEffect kicks a fresh REQ because Error →
        // InitialLoading is allowed in startInitialLoad); we just plumb
        // the same kick in automatically when the user is sitting on a
        // chat the controller gave up on.
        //
        // Capped at 2 attempts per group entry to avoid hot-looping on a
        // genuinely dead relay; the counter resets when the user changes
        // groups (group.id dep on the reset effect below).
        val errorRetryCount = useRef(0)
        useEffect(group.id) {
            errorRetryCount.current = 0
        }
        useEffect(groupLoadingState, isMember) {
            val s = groupLoadingState
            if (s !is GroupLoadingState.Error) return@useEffect
            val count = errorRetryCount.current ?: 0
            if (count >= 2) return@useEffect
            errorRetryCount.current = count + 1
            // Brief debounce so we don't fight a relay that's mid-reconnect.
            delay(1_500)
            // Re-check: state might have moved on (user reset by switching
            // groups, fresh REQ from elsewhere, etc.).
            val stillError = repo.groupStates.value[group.id] is GroupLoadingState.Error
            if (!stillError) return@useEffect
            repo.resetGroupLoadingState(group.id)
            repo.requestGroupMessages(group.id)
        }
        // Admin in a closed group: pull pending join requests explicitly + poll.
        // The standard chat REQ caps at 50 events and buries old 9021s under recent
        // chat, so the badge wouldn't appear on a fresh page load until navigating
        // away and back triggered a mux refresh. The poll also covers relays that
        // don't push 9021s on open subscriptions after EOSE, keeping the badge live.
        // The useEffect block is suspend (this wrappers version — same pattern as
        // useEscClose / useStateFlow); `delay` propagates CancellationException when
        // deps change, so leaving the group / losing admin tears down the loop.
        useEffect(group.id, isAdmin, group.isOpen, activeAccountId) {
            if (!(isAdmin && !group.isOpen)) return@useEffect
            repo.requestPendingJoinRequests(group.id)
            while (true) {
                delay(15_000)
                repo.requestPendingJoinRequests(group.id)
            }
        }
        useEffect(group.id, members.size, messages.size, activeAccountId) {
            val pubkeys = (members + messages.map { it.pubkey }).toSet()
            if (pubkeys.isNotEmpty()) launchApp { repo.requestUserMetadata(pubkeys) }
        }
        // The full ordered row list (date separators, grouped messages, system rows,
        // the "new messages" divider) — fed to ChatMessageList as its data. Scroll,
        // pagination latch and stall detection all live inside that component now.
        val chatItems = if (messages.isEmpty()) emptyList() else buildWebChatItems(messages, lastReadSnapshot, myPubkey)
        // Open at the bottom (newest) and let Virtuoso's followOutput keep it there
        // as the initial pages stream in. We deliberately do NOT auto-scroll to the
        // "New messages" divider on entry: during the connect/initial-load churn
        // (the controller cycles Idle -> InitialLoading several times) that
        // scrollToIndex fought followOutput and made the feed lurch up and down. The
        // divider row still renders in place so the user sees where unread begins;
        // it's just not auto-jumped to. (followOutput + per-item resize compensation
        // are handled by Virtuoso, so the old DOM scroll / ResizeObserver effects
        // that lived here are gone.)
        // Deep-link target (?e=<id>): fetch the exact event by id once. This is the fast path —
        // a single targeted REQ that lands the message even when it's far older than the loaded
        // window, instead of paginating the whole history (mirrors native's fetchMessageById).
        useEffect(props.scrollToMessageId, group.id) {
            val target = props.scrollToMessageId ?: return@useEffect
            launchApp { repo.fetchGroupMessageById(group.id, target) }
        }
        // Then seek to it: scroll into view + flash once it's loaded. While it's still missing and
        // older messages remain, paginate one page at a time until it appears (or history runs
        // out) — covers the case where the direct fetch is dropped. hasMore/isLoadingMore are keys
        // so the loop advances after each page lands. Runs after the auto-scroll effect so it wins
        // the race on group entry.
        useEffect(props.scrollToMessageId, messages.size, hasMore, isLoadingMore) {
            val target = props.scrollToMessageId ?: return@useEffect
            if (target in messagesById) {
                // Command ChatMessageList to scroll the target row into view (by its
                // DOM id, msg-<id>).
                setScrollKey("msg-$target")
                atBottom.current = false
                setHighlightId(target)
                props.onScrolledToMessage()
                window.setTimeout({ setHighlightId(null) }, 3_000)
            } else if (messages.isNotEmpty() && hasMore && !isLoadingMore) {
                launchApp { repo.loadMoreMessages(group.id) }
            }
        }

        fun join() {
            launchApp { repo.joinGroup(group.id) }
        }

        // Scroll a loaded message into view and flash it (used by reply-preview clicks).
        // Commands ChatMessageList via scrollKey (the row's DOM id, msg-<id>).
        fun scrollToMessage(id: String) {
            setScrollKey("msg-$id")
            setHighlightId(id)
            window.setTimeout({ setHighlightId(null) }, 2_600)
        }

        div {
            className = ClassName(if (membersOpen) "chat members-open" else "chat")

            // Main column: header + messages + composer
            div {
                className = ClassName("chat-main")

                // Header
                div {
                    className = ClassName("chat-header")
                    // Mobile-only: opens the groups-sidebar drawer (the `≡` in native's header).
                    // CSS hides it on desktop where the sidebar is always visible.
                    button {
                        className = ClassName("chat-icon-btn chat-drawer-btn")
                        onClick = { props.onOpenDrawer() }
                        icon(Ic.Menu)
                    }
                    div {
                        className = ClassName("chat-header-title")
                        onClick = { setInfoOpen(true) }
                        WebAvatar {
                            url = group.picture
                            seed = group.id
                            kind = AvatarKind.GROUP
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
                    // Pending join-requests indicator (admin + closed group, count > 0).
                    // Opens the JoinRequestsModal directly so admins don't need to dig into
                    // the 3-dots menu when the badge is calling for attention.
                    if (pendingJoinRequests > 0) {
                        button {
                            className = ClassName("chat-icon-btn chat-requests-btn")
                            onClick = { setModal("requests") }
                            icon(Ic.PersonAdd)
                            span {
                                className = ClassName("chat-requests-badge")
                                +pendingJoinRequests.toString()
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
                            // Closed groups: surface an "Invite Code" button next to
                            // Join (matches native GroupHeader.kt:208-232). Uses
                            // window.prompt for the code input — quick & matches the
                            // single-field native modal without a new component.
                            if (!group.isOpen) {
                                button {
                                    className = ClassName("chat-invite-btn")
                                    onClick = {
                                        val code = window.prompt("Enter invite code", "")?.trim()
                                        if (!code.isNullOrBlank()) {
                                            launchApp { repo.joinGroup(group.id, code) }
                                        }
                                    }
                                    icon(Ic.Key)
                                    span { +"Invite Code" }
                                }
                            }
                            button {
                                className = ClassName("chat-join-btn")
                                onClick = { join() }
                                icon(Ic.PersonAdd)
                                span { +(if (!group.isOpen) "Request to Join" else "Join") }
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
                            // Admin-only moderation actions (mirrors native: gated by isAdmin).
                            if (isAdmin) {
                                chatMenuItem("Edit Group") {
                                    setMenuOpen(false)
                                    setModal("edit")
                                }
                                chatMenuItem("Manage Members") {
                                    setMenuOpen(false)
                                    setModal("members")
                                }
                                // Invite codes only matter for closed groups (native gates on isClosed).
                                if (!group.isOpen) {
                                    chatMenuItem("Invite Codes") {
                                        setMenuOpen(false)
                                        setModal("invite")
                                    }
                                    chatMenuItem("Join Requests") {
                                        setMenuOpen(false)
                                        setModal("requests")
                                    }
                                }
                                chatMenuItem("Create Subgroup") {
                                    setMenuOpen(false)
                                    setModal("subgroup")
                                }
                                chatMenuItem("Manage Children") {
                                    setMenuOpen(false)
                                    setModal("children")
                                }
                                chatMenuItem("Delete Group", danger = true) {
                                    setMenuOpen(false)
                                    launchApp { repo.deleteGroup(group.id) }
                                    props.onLeave()
                                }
                                div { className = ClassName("chat-menu-divider") }
                            }
                            // Available to everyone.
                            chatMenuItem("Share") {
                                setMenuOpen(false)
                                setModal("share")
                            }
                            chatMenuItem("Leave Group", danger = true) {
                                setMenuOpen(false)
                                launchApp { repo.leaveGroup(group.id) }
                                props.onLeave()
                            }
                        }
                    }
                }

                // Messages — non-scrolling flex wrapper; Virtuoso (below) owns the
                // scroll, pagination and scroll anchoring.
                div {
                    className = ClassName("chat-messages")
                    if (isLoadingMore && messages.isNotEmpty()) {
                        div {
                            // Stable key: this pill is a sibling rendered BEFORE the
                            // message list and toggles with isLoadingMore. Without
                            // keys, React reconciles the wrapper's children by index,
                            // so the pill appearing/disappearing shifted the list's
                            // index and REMOUNTED it — and each remount snapped the
                            // feed to the bottom (the pagination "jump to bottom").
                            key = "chat-loading-pill"
                            className = ClassName("chat-loading-more")
                            +"Loading earlier messages…"
                        }
                    }
                    // Empty-state gating — matches native GroupScreen.kt:343:
                    // `isInitialLoading = isLoadingMoreMap[groupId] == true && empty`.
                    // Skeleton only while the active REQ is in flight; once
                    // isLoadingMore flips false, fall through to "No messages
                    // yet" regardless of which terminal state the controller
                    // reached. Earlier the gate required Exhausted / Error
                    // specifically, which left web stuck on skeletons in cases
                    // where native shows the empty-state cleanly (e.g. relay
                    // returns an empty EOSE but the controller settles into
                    // HasMore-with-zero, or into a state web didn't whitelist).
                    val isLoadingThis = isLoadingMore ||
                        groupLoadingState is GroupLoadingState.InitialLoading ||
                        groupLoadingState is GroupLoadingState.Retrying
                    // Restricted / pending-approval / skeleton / empty panels apply
                    // ONLY when there are no messages. Once messages exist we always
                    // render the list (the final else). This is essential: isMember /
                    // canPost / isPendingApproval / isGroupRestricted all derive from
                    // flows that flip while loading (member list / restricted flag
                    // arrive late); if the panel could win with messages present, that
                    // flip would unmount and remount ChatMessageList, and each remount
                    // snapped the feed to the bottom (the pagination "jump to bottom").
                    if (messages.isEmpty() && (isGroupRestricted || isPendingApproval)) {
                        div {
                            className = ClassName("chat-restricted")
                            icon(Ic.Lock, "chat-restricted-icon")
                            div {
                                className = ClassName("chat-restricted-title")
                                +(if (isPendingApproval) "Awaiting admin approval" else "Private group")
                            }
                            div {
                                className = ClassName("chat-restricted-body")
                                +(
                                    if (isPendingApproval) {
                                        "Messages will appear once an admin approves your request."
                                    } else {
                                        "You need an invite code or admin approval to see messages."
                                    }
                                    )
                            }
                        }
                    } else if (messages.isEmpty() && (!isConnected || isLoadingThis)) {
                        repeat(8) { messageSkeleton() }
                    } else if (messages.isEmpty()) {
                        div {
                            className = ClassName("chat-empty")
                            +"No messages yet. Say hello 👋"
                        }
                    } else {
                        // Non-virtualized message list: renders every row as DOM and
                        // keeps the reading position on prepend with a scrollHeight
                        // delta. This screen owns the data; the component owns scroll +
                        // pagination. (aliases avoid the prop-name shadowing the locals.)
                        val moreAvail = hasMore
                        val loadingMore = isLoadingMore
                        ChatMessageList {
                            // Stable key so the list is never remounted when a sibling
                            // (the loading pill) toggles — a remount snaps to the bottom.
                            key = "chat-message-list"
                            items = chatItems.toTypedArray().unsafeCast<Array<dynamic>>()
                            keyOf = { chatItemKey(it.unsafeCast<WebChatItem>()) }
                            resetKey = group.id
                            this.hasMore = moreAvail
                            this.isLoadingMore = loadingMore
                            scrollToKey = scrollKey
                            onScrolledToKey = { setScrollKey(null) }
                            this.jumpNonce = jumpNonce
                            onStartReached = { launchApp { repo.loadMoreMessages(group.id) } }
                            onAtBottomChange = { ab ->
                                atBottom.current = ab
                                setAtBottomState(ab)
                                if (!ab) {
                                    wasNotAtBottom.current = true
                                } else {
                                    if (wasNotAtBottom.current == true && lastReadSnapshot != null) setLastReadSnapshot(null)
                                    repo.markGroupAsRead(group.id)
                                }
                            }
                            onRangeChange = { end ->
                                // Mark read up to the newest message in/above the
                                // visible window (mirrors native's per-visible pass).
                                var maxSeen = 0L
                                val e = end.coerceIn(0, chatItems.size - 1)
                                for (i in 0..e) {
                                    val ci = chatItems[i]
                                    if (ci is WebChatItem.Message && ci.message.createdAt > maxSeen) maxSeen = ci.message.createdAt
                                }
                                if (maxSeen > 0L) repo.markGroupAsReadUpTo(group.id, maxSeen)
                            }
                            renderRow = { cb, itDyn ->
                                val item = itDyn.unsafeCast<WebChatItem>()
                                cb.run {
                                    when (item) {
                                        is WebChatItem.DateSeparator ->
                                            div {
                                                key = "date-${item.date}"
                                                // Stable DOM id for the pagination scroll-anchor restore.
                                                id = ElementId("date-${item.date}")
                                                className = ClassName("date-sep")
                                                span {
                                                    className = ClassName("date-sep-label")
                                                    +item.date
                                                }
                                            }

                                        is WebChatItem.NewMessagesDivider ->
                                            div {
                                                key = "new-messages-divider"
                                                // Stable DOM id so the entry-alignment effect can
                                                // scrollIntoView the divider itself rather than the
                                                // adjacent message (which fails for moderation rows
                                                // that have no msg-${id} element).
                                                id = ElementId("new-msg-divider")
                                                className = ClassName("new-msg-divider")
                                                span {
                                                    className = ClassName("new-msg-divider-label")
                                                    +"New Messages"
                                                }
                                            }

                                        is WebChatItem.SystemEvent -> systemEventRow(item, userMetadata) { setProfilePubkey(it) }

                                        is WebChatItem.Message -> {
                                            val message = item.message
                                            val parent = parentMessageOf(message)?.let { messagesById[it] }
                                            val replyPreview =
                                                parent?.let {
                                                    ReplyPreviewData(
                                                        author = displayName(it.pubkey, userMetadata[it.pubkey]),
                                                        content = processMentions(it.content, userMetadata)
                                                            .replace('\n', ' ')
                                                            .trim()
                                                            .take(120),
                                                        tags = it.tags,
                                                    )
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
                                                this.tags = message.tags
                                                this.firstInGroup = item.firstInGroup
                                                isAuthorAdmin = message.pubkey in admins
                                                reactions = reactionsByMsg[message.id].orEmpty()
                                                this.myPubkey = myPubkey
                                                this.userMetadata = userMetadata
                                                this.messagesById = messagesById
                                                onEventRef = { id -> scrollToMessage(id) }
                                                onGroupRef = { gid, relay -> props.onNavigateGroup(gid, relay) }
                                                canZap =
                                                    message.pubkey != myPubkey &&
                                                    (!authorMeta?.lud16.isNullOrBlank() || !authorMeta?.lud06.isNullOrBlank())
                                                zapTotalMsats = zapInfo?.totalMsats ?: 0L
                                                zapCount = zapInfo?.count ?: 0
                                                zappedByMe = myPubkey != null && zapInfo != null && myPubkey in zapInfo.zappers
                                                onZap = { WebZapController.request(message.pubkey, message.id) }
                                                replyTo = replyPreview
                                                onReplyClick = { parent?.let { scrollToMessage(it.id) } }
                                                canDelete = myPubkey != null && (message.pubkey == myPubkey || myPubkey in admins)
                                                messageLink = "https://nostrord.com/open/?relay=$relayHost&group=${group.id}&e=${message.id}"
                                                eventJson = eventJsonOf(message)
                                                onUser = { setProfilePubkey(it) }
                                                onReply = { setReplyingToId(message.id) }
                                                onReact = { emoji ->
                                                    // Surface a relay rejection (e.g. "kind 7 not allowed")
                                                    // instead of letting the reaction silently blink away.
                                                    when (val result = repo.sendReaction(group.id, message.id, message.pubkey, emoji)) {
                                                        is Result.Error -> {
                                                            val raw = result.error.cause?.message ?: result.error.toString()
                                                            setReactionError(
                                                                raw
                                                                    .removePrefix("blocked: ")
                                                                    .removePrefix("error: ")
                                                                    .replaceFirstChar { it.uppercaseChar() },
                                                            )
                                                        }
                                                        is Result.Success -> Unit
                                                    }
                                                }
                                                onDelete = { setMessageToDelete(message.id) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Jump-to-bottom FAB with unread badge — Telegram pattern. Visible
                // when the user has scrolled up from the bottom; the badge shows
                // the count of unread messages from others so the user can either
                // skip them (click) or keep reading from the divider.
                if (!atBottomState) {
                    button {
                        // Lift the FAB above the reply banner so it never covers
                        // the banner's close (X) button on the right edge.
                        className = ClassName(
                            if (replyingToId != null) "chat-jump-bottom replying" else "chat-jump-bottom",
                        )
                        title = "Jump to latest message"
                        onClick = {
                            // Command ChatMessageList to jump to the newest row.
                            setJumpNonce { it + 1 }
                            // Tapping the FAB is an explicit "I've seen everything"
                            // intent — dismiss the divider for this session.
                            if (lastReadSnapshot != null) setLastReadSnapshot(null)
                        }
                        // Chevron, not the bold arrow — matches native's
                        // KeyboardArrowDown in MessagesList.kt:644.
                        icon(Ic.ExpandMore)
                        if (unreadCount > 0) {
                            span {
                                className = ClassName("chat-jump-badge")
                                +unreadCount.toString()
                            }
                        }
                    }
                }

                ChatComposer {
                    this.groupId = group.id
                    this.groupName = groupName
                    this.groupIsOpen = group.isOpen
                    this.canPost = canPost
                    this.isPending = isPending
                    this.members = members
                    this.allGroups = allGroups
                    this.userMetadata = userMetadata
                    this.relayUrl = relayUrl
                    this.relayPubkey = relayMetadata[relayUrl]?.pubkey
                    this.replyingToId = replyingToId
                    this.replyParentName =
                        replyingToId?.let { id -> messagesById[id]?.let { p -> displayName(p.pubkey, userMetadata[p.pubkey]) } }
                    this.onCancelReply = { setReplyingToId(null) }
                    this.onSent = { setReplyingToId(null) }
                    this.onJoin = { join() }
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
                    span {
                        className = ClassName("member-header-title")
                        // Person icon next to "Members (N)" — same layout as
                        // native MemberSidebar.kt:130-145. Both icon and label
                        // stay in white (the muted-grey was easy to miss on the
                        // BackgroundDark band).
                        icon(Ic.Person)
                        span { +"Members (${members.size})" }
                    }
                    // Only admins can add members — kind:9000 (put-user) is admin-only and the
                    // relay rejects it from non-admins. Mirrors native MemberSidebar.
                    if (isAdmin) {
                        button {
                            className = ClassName("member-add-btn")
                            onClick = { setModal("addmember") }
                            icon(Ic.PersonAdd)
                        }
                    }
                }
                div {
                    className = ClassName("member-search")
                    icon(Ic.Search, "member-search-icon")
                    input {
                        className = ClassName("member-search-input")
                        placeholder = "Search members"
                        value = memberQuery
                        onChange = { event -> setMemberQuery(event.currentTarget.value) }
                        onKeyDown = { event ->
                            if (event.key == "Escape") {
                                setMemberQuery("")
                                event.currentTarget.blur()
                            }
                        }
                    }
                    if (memberQuery.isNotEmpty()) {
                        button {
                            className = ClassName("search-clear-btn member-search-clear")
                            onClick = { setMemberQuery("") }
                            icon(Ic.Close)
                        }
                    }
                }
                div {
                    className = ClassName("member-scroll")
                    val memberQ = memberQuery.trim().normalizeForSearch()
                    val filtered =
                        if (memberQ.isEmpty()) {
                            members.toList()
                        } else {
                            members.filter { displayName(it, userMetadata[it]).normalizeForSearch().contains(memberQ) }
                        }
                    // Mirrors native MemberSidebar.kt:100-101 — chat apps surface
                    // who can reply NOW (online) above static role hierarchy.
                    // The ADMIN badge still appears inline on each row, in either
                    // section, so the role info isn't lost.
                    val online = filtered.filter { it in recentlyActiveMembers }
                    val offline = filtered.filter { it !in recentlyActiveMembers }
                    if (isGroupRestricted || isPendingApproval) {
                        // Mirror native MemberSidebar.kt:296-324 — a private
                        // group's member list is hidden until you join /
                        // get approved, surfaced as a centered lock panel
                        // instead of a skeleton or an empty list.
                        div {
                            className = ClassName("member-private")
                            icon(Ic.Lock, "member-private-icon")
                            div {
                                className = ClassName("member-private-label")
                                +(if (isPendingApproval) "Members hidden until approved" else "Members are private")
                            }
                        }
                    } else if (membersLoading && members.isEmpty()) {
                        repeat(6) { memberSkeleton() }
                    } else {
                        if (online.isNotEmpty()) {
                            memberSection("ONLINE", online.size)
                            online.forEach { pubkey ->
                                memberRow(
                                    pubkey,
                                    displayName(pubkey, userMetadata[pubkey]),
                                    userMetadata[pubkey]?.picture,
                                    isAdmin = pubkey in admins,
                                    isOnline = true,
                                ) { setProfilePubkey(it) }
                            }
                        }
                        if (offline.isNotEmpty()) {
                            memberSection("OFFLINE", offline.size)
                            offline.forEach { pubkey ->
                                memberRow(
                                    pubkey,
                                    displayName(pubkey, userMetadata[pubkey]),
                                    userMetadata[pubkey]?.picture,
                                    isAdmin = pubkey in admins,
                                    isOnline = false,
                                ) { setProfilePubkey(it) }
                            }
                        }
                        if (online.isEmpty() && offline.isEmpty()) {
                            div {
                                className = ClassName("member-section")
                                +(if (members.isEmpty()) "No members yet" else "No members found")
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
            // Delete-message confirm. Mirrors the native AlertDialog
            // (GroupScreen.kt:523-545): destructive action, red confirm,
            // explicit "cannot be undone" copy. Esc closes via useEscClose,
            // backdrop click cancels.
            messageToDelete?.let { msgId ->
                deleteMessageConfirm(
                    onCancel = { setMessageToDelete(null) },
                    onConfirm = {
                        setMessageToDelete(null)
                        launchApp {
                            // Mirrors GroupViewModel.kt:260-275: keep the
                            // relay's reason for refusing the kind:5 and
                            // surface it. The 'blocked:' / 'error:' prefix
                            // the NIP-29 relay tacks on is stripped so the
                            // user sees the bare reason capitalised.
                            when (val result = repo.deleteMessage(group.id, msgId)) {
                                is Result.Error -> {
                                    val raw = result.error.cause?.message ?: result.error.toString()
                                    val friendly =
                                        raw
                                            .removePrefix("blocked: ")
                                            .removePrefix("error: ")
                                            .replaceFirstChar { it.uppercaseChar() }
                                    setDeleteError(friendly)
                                }
                                is Result.Success -> Unit
                            }
                        }
                    },
                )
            }
            // Relay rejected the kind:5 (e.g., not an admin, message
            // gone, group restricted) — show the reason instead of
            // silently swallowing.
            deleteError?.let { error ->
                deleteMessageErrorDialog(error) { setDeleteError(null) }
            }
            // Relay rejected the kind:7 reaction — explain it instead of the
            // reaction just blinking away. Mirrors native's "Cannot React" /
            // "Join Required" dialog (GroupScreen.kt:620-665).
            reactionError?.let { error ->
                reactionErrorDialog(
                    message = error,
                    onDismiss = { setReactionError(null) },
                    onJoin = {
                        setReactionError(null)
                        join()
                    },
                )
            }
        }
    }

/** Error dialog shown when the relay rejects a kind:5. Single OK button —
 *  matches the native AlertDialog at GroupScreen.kt:548-562. */
private fun ChildrenBuilder.deleteMessageErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +"Could Not Delete Message"
            }
            div {
                className = ClassName("modal-subtitle tight")
                +message
            }
            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}

/** Error dialog shown when a media upload is rejected (too large, unsupported
 *  format, or an auth/server failure). Single OK button — mirrors the native
 *  "Upload Failed" AlertDialog at MessageUploadButton.kt:70-82. The message can
 *  carry newlines (the supported-formats list), so render it line by line. */
private fun ChildrenBuilder.uploadErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +"Upload Failed"
            }
            div {
                className = ClassName("modal-subtitle tight")
                message.split("\n").forEachIndexed { i, line ->
                    if (i > 0) br {}
                    +line
                }
            }
            div {
                className = ClassName("modal-footer")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}

/** Dialog shown when the relay rejects a kind:7 reaction. Mirrors the native
 *  AlertDialog at GroupScreen.kt:620-665: a "Join Required" variant (offers Join)
 *  when the relay says we're an unknown member, otherwise "Cannot React" + OK. */
private fun ChildrenBuilder.reactionErrorDialog(message: String, onDismiss: () -> Unit, onJoin: () -> Unit) {
    val isUnknownMember = message.contains("unknown member", ignoreCase = true)
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-title")
                +(if (isUnknownMember) "Join Required" else "Cannot React")
            }
            div {
                className = ClassName("modal-subtitle tight")
                if (isUnknownMember) {
                    +"You need to join this group before you can react to messages."
                } else {
                    div { +"This relay does not support reactions." }
                    div {
                        className = ClassName("modal-reason")
                        +message
                    }
                }
            }
            div {
                className = ClassName("modal-footer")
                if (isUnknownMember) {
                    button {
                        className = ClassName("btn-text")
                        onClick = { onDismiss() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        onClick = { onJoin() }
                        +"Join Group"
                    }
                } else {
                    button {
                        className = ClassName("btn-primary")
                        onClick = { onDismiss() }
                        +"OK"
                    }
                }
            }
        }
    }
}

/** Confirm dialog for the destructive "delete this message" action. Uses the
 *  same modal-card + title/subtitle/footer pattern as RemoveAccountDialog so
 *  the destructive confirms across the app read the same. */
private fun ChildrenBuilder.deleteMessageConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onCancel() }
        div {
            className = ClassName("modal-card sm")
            onClick = { it.stopPropagation() }

            div {
                className = ClassName("modal-title")
                +"Delete Message"
            }
            div {
                className = ClassName("modal-subtitle tight")
                +"Are you sure you want to delete this message? This action cannot be undone."
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
                    +"Delete"
                }
            }
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
    var tags: List<List<String>>
    var firstInGroup: Boolean
    var isAuthorAdmin: Boolean
    var reactions: Map<String, GroupManager.ReactionInfo>
    var myPubkey: String?
    var userMetadata: Map<String, UserMetadata>
    var messagesById: Map<String, NostrGroupClient.NostrMessage>
    var onEventRef: (String) -> Unit
    var onGroupRef: (String, String?) -> Unit
    var replyTo: ReplyPreviewData?
    var onReplyClick: () -> Unit
    var canDelete: Boolean
    var canZap: Boolean
    var zapTotalMsats: Long
    var zapCount: Int
    var zappedByMe: Boolean
    var messageLink: String
    var eventJson: String
    var onUser: (String) -> Unit
    var onReply: () -> Unit
    var onReact: suspend (String) -> Unit
    var onZap: () -> Unit
    var onDelete: () -> Unit
}

private val MessageRow =
    FC<MessageRowProps> { props ->
        val (menuOpen, setMenuOpen) = useState { false }
        val (reactOpen, setReactOpen) = useState { false }
        // Emojis with an in-flight sendReaction. The reaction only appears
        // optimistically AFTER signEvent resolves, which on NIP-46 (Amber /
        // bunker) is a 1-2s round-trip with no feedback otherwise. We show a
        // pending badge + spinner during that window (same idea as the send
        // button's spinner), and drop it once the optimistic badge lands.
        val (pendingEmojis, setPendingEmojis) = useState<Set<String>> { emptySet() }
        val react: (String) -> Unit = { emoji ->
            if (emoji !in pendingEmojis) {
                setPendingEmojis { it + emoji }
                launchApp {
                    try {
                        props.onReact(emoji)
                    } finally {
                        setPendingEmojis { it - emoji }
                    }
                }
            }
        }
        // Esc closes whichever overlay is open (emoji picker or context menu),
        // matching the backdrop click. Document-level listener so it fires even
        // when focus sits in the picker's search input.
        useEscClose {
            if (reactOpen) setReactOpen(false)
            if (menuOpen) setMenuOpen(false)
        }
        // Anchor (viewport x,y) for the context menu; positioned/flipped by the effect below.
        val (menuAt, setMenuAt) = useState<Pair<Int, Int>?> { null }
        // Right-click opens the menu to the right of the cursor; the ⋯ button hangs it
        // off the button's right edge (opens leftward). Drives the flip math below.
        val (menuFromCursor, setMenuFromCursor) = useState { false }
        val menuRef = useRef<HTMLDivElement>(null)

        // Swipe-to-reply (touch): drag the row left past a threshold to reply (mirrors native).
        // The .msg-swipe-icon sits absolutely-positioned on the right edge under the row; its
        // opacity + color rise with the swipe distance, snapping to the primary blue once the
        // threshold is crossed. .chat-messages has `overflow-x: hidden` so the translateX
        // can't leak horizontal scroll onto the page.
        val rowRef = useRef<HTMLDivElement>(null)
        val swipeIconRef = useRef<HTMLDivElement>(null)
        val touchStartX = useRef(0.0)
        val touchStartY = useRef(0.0)
        val swiping = useRef(false)
        val swipeArmed = useRef(false)
        // Long-press to open the context menu on touch (mirrors the Android app). The
        // menu opens when the finger is LIFTED after a stationary hold — not mid-hold —
        // so the page never jumps and the ensuing synthesized click can be suppressed
        // (otherwise it lands on the overlay and instantly closes the menu).
        val longPressTimer = useRef(0)
        // True once the press has been held long enough (and hasn't moved) to count as
        // a long-press; the menu opens on touchend while this is set.
        val longPressReady = useRef(false)
        // Timestamp (ms) of a touch-opened menu, to swallow the trailing ghost click.
        val menuOpenedAt = useRef(0.0)

        // Place the fixed context menu at its anchor, flipping left/up when it would overflow.
        useEffect(menuOpen) {
            if (!menuOpen) return@useEffect
            val el = menuRef.current?.asDynamic() ?: return@useEffect
            val anchor = menuAt ?: return@useEffect
            val w = el.offsetWidth as Int
            val h = el.offsetHeight as Int
            val vw = window.innerWidth
            val vh = window.innerHeight
            // Cursor anchor opens rightward (left = x); button anchor opens leftward
            // (left = x - w). Either way, clamp into the viewport with an 8px margin.
            var left = if (menuFromCursor) anchor.first else anchor.first - w
            if (left + w > vw - 8) left = (vw - 8 - w).coerceAtLeast(8)
            if (left < 8) left = 8
            var top = anchor.second
            if (top + h > vh - 8) top = (anchor.second - h).coerceAtLeast(8)
            el.style.left = "${left}px"
            el.style.top = "${top}px"
            el.style.visibility = "visible"
        }

        div {
            id = ElementId(props.domId)
            className =
                ClassName(
                    (if (props.firstInGroup) "msg first" else "msg grouped") +
                        (if (menuOpen) " menu-open" else "") +
                        (if (props.highlighted) " highlight" else ""),
                )
            ref = rowRef
            // Two-stage right-click (Telegram-style): the first right-click on a
            // message opens our app menu at the cursor and suppresses the browser's
            // native menu. A second right-click escapes to the native menu. Right-click
            // off any message keeps the browser default untouched.
            onContextMenu = { event ->
                if (!menuOpen) {
                    // First right-click: open ours at the cursor and suppress native.
                    event.preventDefault()
                    setReactOpen(false)
                    menuOpenedAt.current = 0.0 // mouse/right-click: no ghost click to swallow
                    setMenuAt(event.clientX.toInt() to event.clientY.toInt())
                    setMenuFromCursor(true)
                    setMenuOpen(true)
                } else {
                    // Second right-click: our menu is open and the row sits above the
                    // overlay (.msg.menu-open z-index 60 > .ctx-overlay 50), so the
                    // event lands here, not on the overlay. Close ours and do NOT
                    // preventDefault, so the browser shows its native menu.
                    setMenuOpen(false)
                }
            }
            onTouchStart = { event ->
                val t = event.asDynamic().touches[0]
                touchStartX.current = t.clientX as Double
                touchStartY.current = t.clientY as Double
                swiping.current = false
                swipeArmed.current = false
                longPressReady.current = false
                window.clearTimeout(longPressTimer.current ?: 0)
                // Arm the long-press after a stationary hold; the menu itself opens on
                // touchend (below) so the page can't jump while the finger is down. A
                // light haptic signals that releasing now will open the menu.
                longPressTimer.current = window.setTimeout({
                    if (swiping.current != true) {
                        longPressReady.current = true
                        val nav = window.navigator.asDynamic()
                        if (nav.vibrate != null) nav.vibrate(15)
                    }
                }, 380)
            }
            onTouchMove = { event ->
                val t = event.asDynamic().touches[0]
                val dx = (t.clientX as Double) - (touchStartX.current ?: 0.0)
                val dy = (t.clientY as Double) - (touchStartY.current ?: 0.0)
                // Any real movement means this is a scroll/swipe, not a long-press.
                if (abs(dx) > 10.0 || abs(dy) > 10.0) {
                    window.clearTimeout(longPressTimer.current ?: 0)
                    longPressReady.current = false
                }
                if (swiping.current != true && abs(dx) > 10.0 && abs(dx) > abs(dy)) {
                    swiping.current = true
                }
                if (swiping.current == true && dx < 0) {
                    val off = dx.coerceAtLeast(-80.0)
                    val armed = off <= -56.0
                    swipeArmed.current = armed
                    rowRef.current?.asDynamic()?.style?.transform = "translateX(${off}px)"
                    // Drive the reply icon's opacity (0→1 as the row slides 0→56px)
                    // and flip to the primary blue once the threshold is crossed —
                    // same affordance as the native gesture's reveal layer.
                    val iconStyle = swipeIconRef.current?.asDynamic()?.style
                    if (iconStyle != null) {
                        val progress = (-off / 56.0).coerceIn(0.0, 1.0)
                        iconStyle.opacity = progress.toString()
                        iconStyle.color =
                            if (armed) "var(--color-primary)" else "var(--color-text-muted)"
                        // A tiny scale bump on cross helps the discrete "armed" moment
                        // read as a snap rather than a colour change in isolation.
                        // Keep the translateY(-50%) from the base CSS or the icon
                        // jumps off vertical center when the JS overrides transform.
                        iconStyle.transform =
                            if (armed) "translateY(-50%) scale(1.15)" else "translateY(-50%) scale(1.0)"
                    }
                }
            }
            onTouchEnd = { event ->
                window.clearTimeout(longPressTimer.current ?: 0)
                // Open the menu at the finger if the press was a stationary long-press
                // and didn't land on something interactive (link, avatar, badge, ...).
                val tgt = event.target.asDynamic()
                val onInteractive = tgt != null &&
                    tgt.closest("a, button, img, video, input, textarea, .clickable, .mention") != null
                val openMenu = longPressReady.current == true &&
                    swiping.current != true &&
                    !onInteractive &&
                    !menuOpen
                if (openMenu) {
                    // Suppress the synthesized mouse/click sequence so it can't hit the
                    // overlay and immediately close the menu we're about to open.
                    event.preventDefault()
                    setReactOpen(false)
                    menuOpenedAt.current = kotlin.js.Date.now()
                    setMenuAt((touchStartX.current ?: 0.0).toInt() to (touchStartY.current ?: 0.0).toInt())
                    setMenuFromCursor(true)
                    setMenuOpen(true)
                }
                // Animate the row back ONLY if it was actually swiped. Giving the row a
                // transform otherwise (even translateX(0)) makes it the containing block
                // for the position:fixed context menu, which would yank the menu out of
                // place the moment it opens.
                if (swiping.current == true) {
                    val el = rowRef.current?.asDynamic()
                    if (el != null) {
                        el.style.transition = "transform 0.15s ease"
                        el.style.transform = "translateX(0)"
                        window.setTimeout({
                            el.style.transition = ""
                            el.style.transform = ""
                        }, 180)
                    }
                    val iconStyle = swipeIconRef.current?.asDynamic()?.style
                    if (iconStyle != null) {
                        iconStyle.transition = "opacity 0.15s ease, transform 0.15s ease"
                        iconStyle.opacity = "0"
                        iconStyle.transform = "translateY(-50%) scale(1.0)"
                        window.setTimeout({
                            iconStyle.transition = ""
                        }, 180)
                    }
                }
                if (!openMenu && swipeArmed.current == true) props.onReply()
                swiping.current = false
                swipeArmed.current = false
                longPressReady.current = false
            }

            // Swipe-to-reply icon (revealed under the row as it slides left). Sits
            // behind everything via z-index so the row scrolls over it.
            div {
                ref = swipeIconRef
                className = ClassName("msg-swipe-icon")
                icon(Ic.Reply)
            }

            div {
                className = ClassName("msg-gutter")
                if (props.firstInGroup) {
                    WebAvatar {
                        url = props.avatarUrl
                        seed = props.pubkey
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
                props.replyTo?.let { reply ->
                    div {
                        className = ClassName("msg-reply clickable")
                        onClick = { props.onReplyClick() }
                        div { className = ClassName("msg-reply-bar") }
                        div {
                            className = ClassName("msg-reply-content")
                            div {
                                className = ClassName("msg-reply-author")
                                +reply.author
                            }
                            div {
                                className = ClassName("msg-reply-text")
                                // Mentions were already resolved to @name in the parent
                                // build step (so we don't need userMetadata here); this
                                // pass swaps :shortcode: tokens for inline <img>.
                                renderTextWithEmojis(reply.content, extractEmojiMap(reply.tags))
                            }
                        }
                    }
                }
                div {
                    className = ClassName("msg-text")
                    renderMessageContent(
                        props.content,
                        props.tags,
                        props.userMetadata,
                        props.messagesById,
                        props.onUser,
                        props.onEventRef,
                        props.onGroupRef,
                    )
                }
                // Pending emojis still waiting on signEvent + relay; hide any
                // that the optimistic update already merged into props.reactions
                // so we never show a spinner badge next to its real counterpart.
                val visiblePending = pendingEmojis.filter { it !in props.reactions }
                if (props.reactions.isNotEmpty() || visiblePending.isNotEmpty()) {
                    div {
                        className = ClassName("msg-reactions")
                        props.reactions.forEach { (emoji, info) ->
                            val mine = props.myPubkey != null && props.myPubkey in info.reactors
                            button {
                                className = ClassName(if (mine) "reaction-badge mine" else "reaction-badge")
                                onClick = { react(emoji) }
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
                                // Stacked avatars of who reacted (up to 3, overlapping), then +N overflow.
                                div {
                                    className = ClassName("reaction-avatars")
                                    info.reactors.take(3).forEach { reactor ->
                                        WebAvatar {
                                            url = props.userMetadata[reactor]?.picture
                                            seed = reactor
                                            this.name = displayName(reactor, props.userMetadata[reactor])
                                            cls = "reaction-avatar"
                                        }
                                    }
                                }
                                if (info.reactors.size > 3) {
                                    span {
                                        className = ClassName("reaction-count")
                                        +"+${info.reactors.size - 3}"
                                    }
                                }
                            }
                        }
                        visiblePending.forEach { emoji ->
                            div {
                                className = ClassName("reaction-badge pending")
                                +emoji
                                span { className = ClassName("reaction-spinner") }
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

            // Context menu (right-click / long-press)
            if (menuOpen) {
                div {
                    className = ClassName("ctx-overlay")
                    // Don't let overlay/menu touches reach the row's swipe + long-press
                    // handlers (they would transform the row and yank this fixed menu).
                    onTouchStart = { it.stopPropagation() }
                    onTouchMove = { it.stopPropagation() }
                    onTouchEnd = { it.stopPropagation() }
                    onClick = {
                        // Ignore the synthesized click that trails a touch-open
                        // (it would otherwise close the menu the instant it opens).
                        if (kotlin.js.Date.now() - (menuOpenedAt.current ?: 0.0) > 400.0) setMenuOpen(false)
                    }
                    // Second right-click (this overlay is now on top): close our menu
                    // and let the browser show its native menu, since we don't
                    // preventDefault here. Matches Telegram's two-stage behavior.
                    onContextMenu = { setMenuOpen(false) }
                }
                div {
                    ref = menuRef
                    className = ClassName("ctx-menu")
                    onTouchStart = { it.stopPropagation() }
                    onTouchMove = { it.stopPropagation() }
                    onTouchEnd = { it.stopPropagation() }
                    // Quick-reactions row (one tap to react) + an affordance to open
                    // the full picker. Mirrors the native menu's QuickReactionsRow.
                    div {
                        className = ClassName("ctx-reactions")
                        for (emoji in QuickReactions) {
                            button {
                                className = ClassName("ctx-reaction")
                                onClick = {
                                    react(emoji)
                                    setMenuOpen(false)
                                }
                                +emoji
                            }
                        }
                        button {
                            className = ClassName("ctx-reaction ctx-reaction-more")
                            title = "Add reaction"
                            onClick = {
                                setMenuOpen(false)
                                setReactOpen(true)
                            }
                            icon(Ic.EmojiEmotions)
                        }
                    }
                    div { className = ClassName("ctx-divider") }
                    ctxItem(Ic.Reply, "Reply") {
                        props.onReply()
                        setMenuOpen(false)
                    }
                    if (props.canZap) {
                        ctxItem(Ic.Bolt, "Zap", zap = true) {
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

            // Reaction emoji picker (opened from the 😊 hover action or the context menu).
            if (reactOpen) {
                div {
                    className = ClassName("emoji-overlay")
                    onClick = { setReactOpen(false) }
                    EmojiPicker {
                        onPick = { emoji ->
                            react(emoji)
                            setReactOpen(false)
                        }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.ctxItem(ic: Ic, label: String, danger: Boolean = false, zap: Boolean = false, onSelect: () -> Unit) {
    div {
        className =
            ClassName(
                when {
                    danger -> "ctx-item danger"
                    zap -> "ctx-item zap"
                    else -> "ctx-item"
                },
            )
        onClick = { onSelect() }
        span {
            className = ClassName("ctx-item-icon")
            icon(ic)
        }
        span { +label }
    }
}

/** A moderation/system event row (join / role change / removed / left), matching native. */
private fun ChildrenBuilder.systemEventRow(
    event: WebChatItem.SystemEvent,
    userMetadata: Map<String, UserMetadata>,
    onUser: (String) -> Unit,
) {
    val typeClass =
        when (event.type) {
            SystemEventType.JOINED -> "joined"
            SystemEventType.ROLE_CHANGED -> "role"
            SystemEventType.REMOVED -> "removed"
            SystemEventType.LEFT -> "left"
        }
    val typeIcon =
        when (event.type) {
            SystemEventType.JOINED -> Ic.Login
            SystemEventType.ROLE_CHANGED -> Ic.Shield
            SystemEventType.REMOVED -> Ic.PersonRemove
            SystemEventType.LEFT -> Ic.Logout
        }
    div {
        // Stable React key — without it React reconciles SystemEvent rows by
        // position. When pagination prepends older system events at the top,
        // positional reconciliation re-renders existing rows in place with the
        // older data and appends fresh rows at the bottom, scrambling the DOM
        // order and breaking the scroll anchor. All other row types (Message,
        // DateSeparator, NewMessagesDivider) already key correctly.
        key = "sys-${event.id}"
        // Stable DOM id so the pagination scroll restore can anchor to a
        // system event row (the only thing visible in some join/leave-heavy
        // groups when the user paginates up).
        id = ElementId("sys-${event.id}")
        className = ClassName("sys-event")
        div {
            className = ClassName("sys-event-icon $typeClass")
            icon(typeIcon)
        }
        if (event.totalUsers > 1) {
            div {
                className = ClassName("sys-event-avatars")
                (listOf(event.pubkey) + event.additionalUsers).take(4).forEach { pk ->
                    WebAvatar {
                        url = userMetadata[pk]?.picture
                        seed = pk
                        name = displayName(pk, userMetadata[pk])
                        cls = "sys-event-avatar"
                    }
                }
                val overflow = event.totalUsers - 4
                if (overflow > 0) {
                    span {
                        className = ClassName("sys-event-overflow")
                        +"+$overflow"
                    }
                }
            }
        } else {
            WebAvatar {
                url = userMetadata[event.pubkey]?.picture
                seed = event.pubkey
                name = displayName(event.pubkey, userMetadata[event.pubkey])
                cls = "sys-event-avatar clickable"
                onClick = { onUser(event.pubkey) }
            }
        }
        span {
            className = ClassName("sys-event-text")
            span {
                className = ClassName("sys-event-name")
                +(if (event.totalUsers > 1) "${event.totalUsers} members" else displayName(event.pubkey, userMetadata[event.pubkey]))
            }
            +" ${event.action}"
        }
        span {
            className = ClassName("sys-event-time")
            +formatTimestamp(event.createdAt)
        }
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
    Regex(
        "(data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+)" +
            "|(https?://[^\\s]+)" +
            "|(nostr:(?:npub1|nprofile1|nevent1|note1|naddr1)[0-9a-z]+)" +
            "|\\b((?:npub1|nprofile1|nevent1|note1|naddr1)[0-9a-z]{20,})",
    )
private val IMAGE_EXT = Regex("\\.(jpg|jpeg|png|gif|webp|avif|svg)(\\?.*)?$", RegexOption.IGNORE_CASE)
private val VIDEO_EXT = Regex("\\.(mp4|webm|mov|avi|mkv|m4v|ogv)(\\?.*)?$", RegexOption.IGNORE_CASE)

// Detect YouTube links (watch / shorts / live / embed / youtu.be) and capture
// the 11-char video id, mirroring the native MessageContentParser regex.
private val YOUTUBE_REGEX =
    Regex(
        """(?:youtube\.com/(?:watch\?v=|shorts/|live/|embed/)|youtu\.be/)([\w-]{11})""",
        RegexOption.IGNORE_CASE,
    )

private val CODE_BLOCK_REGEX = Regex("```(?:([A-Za-z0-9_+-]*)\n)?([\\s\\S]*?)```")
private val INLINE_CODE_REGEX = Regex("`([^`\n]+)`")

// NIP-30 custom emoji. Mirrors MessageContentParser in commonMain: tag = ["emoji",
// shortcode, url]; the body uses :shortcode: which renders as an inline image.
private val EMOJI_SHORTCODE_REGEX = Regex(""":([a-zA-Z0-9_-]+):""")

private fun extractEmojiMap(tags: List<List<String>>): Map<String, String> = tags.asSequence()
    .filter { it.size >= 3 && it[0] == "emoji" }
    .mapNotNull { tag ->
        val shortcode = tag[1]
        val url = tag[2]
        if (shortcode.isBlank()) return@mapNotNull null
        if (!shortcode.all { c -> c.isLetterOrDigit() || c == '_' || c == '-' }) return@mapNotNull null
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return@mapNotNull null
        shortcode to url
    }.toMap()

/** NIP-92 imeta thumbnails: maps a media url to its poster image url (thumb, else
 *  image). Used as the <video> poster so a click-to-load player shows a preview
 *  frame instead of a black box — without fetching the video itself. */
private fun extractVideoPosters(tags: List<List<String>>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (tag in tags) {
        if (tag.isEmpty() || tag[0] != "imeta") continue
        var url: String? = null
        var thumb: String? = null
        for (i in 1 until tag.size) {
            val field = tag[i]
            when {
                field.startsWith("url ") -> url = field.removePrefix("url ")
                field.startsWith("thumb ") -> thumb = field.removePrefix("thumb ")
                field.startsWith("image ") -> if (thumb == null) thumb = field.removePrefix("image ")
            }
        }
        if (url != null && thumb != null) result[url] = thumb
    }
    return result
}

/** Emit [text] verbatim, replacing :shortcode: tokens with `<img>` when in [emojiMap]. */
private fun ChildrenBuilder.renderTextWithEmojis(text: String, emojiMap: Map<String, String>) {
    if (emojiMap.isEmpty() || ':' !in text) {
        +text
        return
    }
    var last = 0
    for (m in EMOJI_SHORTCODE_REGEX.findAll(text)) {
        val url = emojiMap[m.groupValues[1]] ?: continue
        if (m.range.first > last) +text.substring(last, m.range.first)
        img {
            className = ClassName("msg-emoji")
            src = url
            alt = m.value
        }
        last = m.range.last + 1
    }
    if (last < text.length) +text.substring(last)
}

/**
 * Render message text: fenced code blocks (```), then inline `code`, then links / images /
 * videos / NIP-27 mentions / event & group refs in the remaining text — mirroring native.
 */
private fun ChildrenBuilder.renderMessageContent(
    content: String,
    tags: List<List<String>>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    val emojiMap = extractEmojiMap(tags)
    val posters = extractVideoPosters(tags)
    var last = 0
    for (block in CODE_BLOCK_REGEX.findAll(content)) {
        if (block.range.first > last) {
            renderInline(content.substring(last, block.range.first), emojiMap, posters, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        val lang = block.groupValues[1].takeIf { it.isNotBlank() }
        div {
            className = ClassName("code-block")
            if (lang != null) {
                div {
                    className = ClassName("code-lang")
                    +lang
                }
            }
            pre {
                className = ClassName("code-pre")
                +block.groupValues[2].trim('\n')
            }
        }
        last = block.range.last + 1
    }
    if (last < content.length) {
        renderInline(content.substring(last), emojiMap, posters, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

/** Render a non-code text span: inline `code` as monospace, the rest through [renderEntities]. */
private fun ChildrenBuilder.renderInline(
    text: String,
    emojiMap: Map<String, String>,
    posters: Map<String, String>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (m in INLINE_CODE_REGEX.findAll(text)) {
        if (m.range.first > last) {
            renderEntities(text.substring(last, m.range.first), emojiMap, posters, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        code {
            className = ClassName("msg-code")
            +m.groupValues[1]
        }
        last = m.range.last + 1
    }
    if (last < text.length) {
        renderEntities(text.substring(last), emojiMap, posters, userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

private fun ChildrenBuilder.renderEntities(
    content: String,
    emojiMap: Map<String, String>,
    posters: Map<String, String>,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (match in URL_REGEX.findAll(content)) {
        if (match.range.first > last) {
            renderTextWithEmojis(content.substring(last, match.range.first), emojiMap)
        }
        val token = match.value
        if (token.startsWith("data:image/")) {
            ChatImage { imageUrl = token }
        } else if (token.startsWith("http")) {
            val url = token.trimEnd('.', ',', ')', '!', '?', ';', ':')
            val youtubeId = YOUTUBE_REGEX.find(url)?.groupValues?.get(1)
            if (youtubeId != null) {
                YouTubeEmbed { videoId = youtubeId }
            } else if (IMAGE_EXT.containsMatchIn(url)) {
                ChatImage { imageUrl = url }
            } else if (VIDEO_EXT.containsMatchIn(url)) {
                ChatVideo {
                    videoUrl = url
                    posterUrl = posters[url]
                }
            } else {
                a {
                    className = ClassName("msg-link")
                    href = url
                    asDynamic().target = "_blank"
                    rel = "noopener noreferrer"
                    +url
                }
            }
            if (url.length < token.length) +token.substring(url.length)
        } else {
            when (val entity = Nip19.decode(token.removePrefix("nostr:"))) {
                is Nip19.Entity.Npub -> mentionSpan(entity.pubkey, userMetadata, onUser)
                is Nip19.Entity.Nprofile -> mentionSpan(entity.pubkey, userMetadata, onUser)
                is Nip19.Entity.Nevent ->
                    QuotedEvent {
                        eventId = entity.eventId
                        relays = entity.relays
                        author = entity.author
                        localById = messagesById
                        onScrollTo = onEventRef
                    }
                is Nip19.Entity.Note ->
                    QuotedEvent {
                        eventId = entity.eventId
                        relays = emptyList()
                        author = null
                        localById = messagesById
                        onScrollTo = onEventRef
                    }
                is Nip19.Entity.Naddr ->
                    if (entity.kind == 39000) {
                        GroupLinkCard {
                            groupId = entity.identifier
                            relayUrl = entity.relays.firstOrNull()
                            onNavigate = onGroupRef
                        }
                    } else {
                        a {
                            className = ClassName("msg-link")
                            href = "https://njump.me/${token.removePrefix("nostr:")}"
                            asDynamic().target = "_blank"
                            rel = "noopener noreferrer"
                            +"[article]"
                        }
                    }
                else -> +token
            }
        }
        last = match.range.last + 1
    }
    if (last < content.length) renderTextWithEmojis(content.substring(last), emojiMap)
}

private fun ChildrenBuilder.mentionSpan(pubkey: String, userMetadata: Map<String, UserMetadata>, onUser: (String) -> Unit) {
    span {
        className = ClassName("msg-mention")
        onClick = { onUser(pubkey) }
        +"@${displayName(pubkey, userMetadata[pubkey])}"
    }
}

private external interface QuotedEventProps : Props {
    var eventId: String
    var relays: List<String>
    var author: String?
    var localById: Map<String, NostrGroupClient.NostrMessage>
    var onScrollTo: (String) -> Unit
}

/**
 * A decoded nevent/note reference rendered as a quote card (author + content), mirroring the
 * native QuotedEvent. Resolves the event from the current group or the global event cache, and
 * fetches it by id from the nevent's relay hints when unknown. Clicking scrolls to it if loaded.
 */
private val QuotedEvent =
    FC<QuotedEventProps> { props ->
        val repo = AppModule.nostrRepository
        val cached = useStateFlow(repo.cachedEvents)
        val userMetadata = useStateFlow(repo.userMetadata)

        val local = props.localById[props.eventId]
        val cachedEv = cached[props.eventId]
        val pubkey = local?.pubkey ?: cachedEv?.pubkey ?: props.author
        val content = local?.content ?: cachedEv?.content

        useEffectOnce {
            if (props.eventId !in cached && local == null) {
                launchApp { repo.requestEventById(props.eventId, props.relays, props.author) }
            }
        }
        useEffect(pubkey) {
            val pk = pubkey
            if (pk != null && userMetadata[pk] == null) {
                launchApp { repo.requestUserMetadata(setOf(pk)) }
            }
        }

        div {
            className = ClassName("quoted-event")
            onClick = { props.onScrollTo(props.eventId) }
            if (content != null && pubkey != null) {
                div {
                    className = ClassName("quoted-event-head")
                    WebAvatar {
                        url = userMetadata[pubkey]?.picture
                        seed = pubkey
                        this.name = displayName(pubkey, userMetadata[pubkey])
                        cls = "quoted-event-avatar"
                    }
                    span {
                        className = ClassName("quoted-event-author")
                        +displayName(pubkey, userMetadata[pubkey])
                    }
                }
                div {
                    className = ClassName("quoted-event-content")
                    +content.replace('\n', ' ').trim().take(280)
                }
            } else {
                span {
                    className = ClassName("quoted-event-loading")
                    +"Loading quoted message…"
                }
            }
        }
    }

private external interface GroupLinkCardProps : Props {
    var groupId: String
    var relayUrl: String?
    var onNavigate: (String, String?) -> Unit
}

/**
 * A decoded naddr group reference (kind 39000) rendered as a card (avatar + name + about +
 * relay), mirroring the native GroupLinkCard. Resolves metadata from groupsByRelay and fetches
 * a preview if unknown; clicking opens the group.
 */
private val GroupLinkCard =
    FC<GroupLinkCardProps> { props ->
        val repo = AppModule.nostrRepository
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val meta = groupsByRelay.values.flatten().firstOrNull { it.id == props.groupId }
        val name = meta?.name?.takeIf { it.isNotBlank() } ?: props.groupId
        val relayDisplay = props.relayUrl?.removePrefix("wss://")?.removePrefix("ws://")?.trimEnd('/')

        useEffect(props.groupId, props.relayUrl, meta?.name) {
            val relay = props.relayUrl
            if (relay != null && meta?.name == null) {
                launchApp { repo.fetchGroupPreview(props.groupId, relay) }
            }
        }

        div {
            className = ClassName("group-link-card")
            onClick = { props.onNavigate(props.groupId, props.relayUrl) }
            WebAvatar {
                url = meta?.picture
                seed = props.groupId
                kind = AvatarKind.GROUP
                this.name = name
                cls = "group-link-avatar"
            }
            div {
                className = ClassName("group-link-meta")
                div {
                    className = ClassName("group-link-name")
                    +name
                }
                meta?.about?.takeIf { it.isNotBlank() }?.let { about ->
                    div {
                        className = ClassName("group-link-about")
                        +about
                    }
                }
                relayDisplay?.let {
                    div {
                        className = ClassName("group-link-relay")
                        +it
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.memberSection(title: String, count: Int) {
    div {
        className = ClassName("member-section")
        +"$title ($count)"
    }
}

private fun ChildrenBuilder.memberRow(
    pubkey: String,
    name: String,
    avatarUrl: String?,
    isAdmin: Boolean,
    isOnline: Boolean,
    onUser: (String) -> Unit,
) {
    div {
        className = ClassName("member-row")
        onClick = { onUser(pubkey) }
        div {
            className = ClassName("member-avatar-wrap")
            WebAvatar {
                url = avatarUrl
                seed = pubkey
                this.name = name
                // Offline avatars fade (`.member-avatar.dimmed`) — mirrors native's
                // MemberAvatar(dimmed = isOnline == false) in MemberSidebar.kt:388.
                cls = if (isOnline) "member-avatar" else "member-avatar dimmed"
            }
            // Status sticker on the bottom-right of the avatar: Success green when
            // online (a member who posted in the last 10 min), TextMuted grey
            // otherwise. The 2px Surface-coloured ring around the dot mirrors
            // MemberSidebar.kt:391's `.background(Surface, CircleShape).padding(2.dp)`
            // and reads as a sticker, not as a pixel glued to the avatar edge.
            span {
                className = ClassName(if (isOnline) "member-dot online" else "member-dot")
            }
        }
        span {
            // Native dims the name to TextMuted when the member is offline
            // (MemberSidebar.kt:415). The `.dimmed` modifier already maps to
            // `color: var(--color-text-muted)` in the CSS.
            className = ClassName(if (isOnline) "member-name" else "member-name dimmed")
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
