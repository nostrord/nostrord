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
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.ChatImage
import org.nostr.nostrord.web.components.ChatVideo
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.Ic
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
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.pre
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useEffectOnce
import react.useLayoutEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document
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

private fun copyToClipboard(text: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
}

private fun displayName(pubkey: String, meta: UserMetadata?): String = meta?.displayName?.takeIf { it.isNotBlank() }
    ?: meta?.name?.takeIf { it.isNotBlank() }
    ?: (pubkey.take(8) + "…")

/** Active mention being typed in the composer: the trigger (@ or %), its query, and start index. */
private data class MentionCtx(val trigger: Char, val query: String, val start: Int)

/** A single autocomplete suggestion: how to show it and the `nostr:` reference to insert. */
private data class MentionMatch(val label: String, val picture: String?, val seed: String, val group: Boolean, val ref: String)

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
        val membersLoading = group.id in useStateFlow(repo.loadingMembers)
        val myPubkey = repo.getPublicKey()

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

        val (draft, setDraft) = useState { "" }
        // Tracks an in-flight sendMessage so we (a) don't double-send if the user
        // mashes Enter, and (b) keep the draft visible until the signer + relay
        // come back — clearing optimistically erased the message when a NIP-07
        // cancel or relay reject killed the publish.
        val (sending, setSending) = useState { false }
        // Active media uploads in flight (paste / drag-and-drop). The send button
        // shows a spinner instead of the Send icon while > 0 — without it the
        // user can't tell that a freshly-pasted file is still uploading and might
        // click Send before the URL appears in the draft.
        val (uploadCount, setUploadCount) = useState { 0 }
        val (membersOpen, setMembersOpen) = useState { false }
        val (infoOpen, setInfoOpen) = useState { false }
        val (profilePubkey, setProfilePubkey) = useState<String?> { null }
        val (menuOpen, setMenuOpen) = useState { false }
        val (replyingToId, setReplyingToId) = useState<String?> { null }
        // Members sidebar search query.
        val (memberQuery, setMemberQuery) = useState { "" }
        // The deep-linked message currently flashing (cleared after the highlight animation).
        val (highlightId, setHighlightId) = useState<String?> { null }
        // Composer emoji picker open state.
        val (emojiOpen, setEmojiOpen) = useState { false }
        // Active @user / %group mention being typed in the composer.
        val (mention, setMention) = useState<MentionCtx?> { null }

        // Autocomplete suggestions for the active mention (members for @, groups for %).
        val mentionMatches: List<MentionMatch> =
            when (mention?.trigger) {
                '@' ->
                    members.asSequence()
                        .map { pk -> pk to displayName(pk, userMetadata[pk]) }
                        .filter { (_, nm) -> mention.query.isBlank() || nm.contains(mention.query, ignoreCase = true) }
                        .take(6)
                        .map { (pk, nm) ->
                            MentionMatch(nm, userMetadata[pk]?.picture, pk, group = false, ref = "nostr:" + Nip19.encodeNpub(pk))
                        }
                        .toList()
                '%' ->
                    allGroups.asSequence()
                        .filter { g -> mention.query.isBlank() || (g.name ?: g.id).contains(mention.query, ignoreCase = true) }
                        .take(6)
                        .map { g ->
                            val ref = "nostr:" + Nip19.encodeNaddr(g.id, relayUrl, 39000, relayMetadata[relayUrl]?.pubkey)
                            MentionMatch(g.name ?: g.id, g.picture, g.id, group = true, ref = ref)
                        }
                        .toList()
                else -> emptyList()
            }

        // Replace the typed "@query"/"%query" with the chosen nostr: reference.
        fun insertMention(ref: String) {
            val m = mention ?: return
            val cursorEnd = (m.start + 1 + m.query.length).coerceAtMost(draft.length)
            setDraft(draft.take(m.start) + ref + " " + draft.substring(cursorEnd))
            setMention(null)
        }

        // Mirrors native's isSupportedMediaMime: image / video / audio go to
        // nostr.build. Anything else is dropped — copying a .zip or .pdf from the
        // file manager would otherwise upload bytes the relay can't render.
        fun isMediaMime(type: String?): Boolean = type != null &&
            (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Shared between Ctrl/Cmd+V paste and drag-and-drop: upload the file to
        // nostr.build, append the returned URL to the composer draft (matches
        // the native ClipboardImageReader path — see ClipboardImage.jvm.kt).
        // Tracks uploadCount via inc/dec so the send button can swap to a
        // spinner while at least one upload is in flight (handles multi-file
        // drops correctly — finishes are not necessarily in start order).
        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    val url = uploadBlob(file)
                    if (url != null) {
                        setDraft { prev -> if (prev.isBlank()) url else "$prev $url" }
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }
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

        // Scroll/pagination bookkeeping (refs so they don't trigger re-render).
        //
        // Pagination restore anchors to a SPECIFIC DOM ELEMENT (the one at the
        // top of the viewport when the prepend was triggered), then after the
        // prepend lands we adjust scrollTop so that exact element ends up at
        // the same offset from the viewport top it had before. This replaces
        // the earlier `prevScrollTop + heightAdded` math, which broke whenever
        // anything OTHER than the prepend changed scrollHeight between save and
        // restore — new socket messages arriving in parallel, async images /
        // videos resolving dimensions, reactions / replies adding height to
        // existing rows. The anchor approach is robust to all of those because
        // the element's offsetTop reflects whatever the DOM ended up as.
        val loadingOlder = useRef(false)
        val anchorElementId = useRef<String>(null)
        val anchorOffsetFromTop = useRef(0.0)
        val atBottom = useRef(true)
        // "New messages" divider snapshot. Captured once on group entry, then
        // cleared the first time the user scrolls up and back down to the bottom
        // (issue #83 — divider sticks around after the user has clearly read them).
        // Tracked together with `wasNotAtBottom` so the initial auto-pin-to-bottom
        // doesn't clear it immediately on entry.
        val (lastReadSnapshot, setLastReadSnapshot) = useState<Long?> { null }
        val wasNotAtBottom = useRef(false)
        // True once we've performed the entry alignment to the divider (Telegram
        // behaviour — open the chat at the top of the new messages, not at the
        // bottom). Gates both the alignment effect itself (one-shot per group
        // entry) and the entry-time auto-pin-to-bottom in the auto-scroll effect.
        val openedAtDivider = useRef(false)
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

        // Composer textarea ref. Two effects ride on it: (1) auto-focus when the
        // user stages a reply (matches native's keyboard-up behaviour), and (2)
        // auto-grow with the content — the field starts at one line and expands up
        // to the CSS `max-height` cap before scrolling. Required because the old
        // <input> just clipped long messages to the visible width with no signal
        // that the rest of the text was still there.
        val composerInputRef = useRef<HTMLTextAreaElement>(null)
        // Debounce handle for the per-visible mark-as-read pass (Gap 3). Cleared
        // on every scroll tick so we only commit after the scroll settles for
        // 500ms — same cadence the native MessagesList snapshotFlow uses.
        val markReadDebounce = useRef<Int>(null)
        useEffect(replyingToId) {
            if (replyingToId != null) composerInputRef.current?.focus()
        }
        useEffect(draft) {
            val el = composerInputRef.current ?: return@useEffect
            // Reset before reading scrollHeight so the field can shrink when text
            // is deleted — otherwise scrollHeight only grows.
            el.style.height = "auto"
            el.style.height = "${el.scrollHeight}px"
        }

        // Load messages + author/member metadata when the group (or its rosters) change.
        useEffect(group.id) {
            launchApp { repo.requestGroupMessages(group.id) }
            // Snapshot the previous read point BEFORE markGroupAsRead persists "now",
            // so the divider can anchor on the user's actual last-read message
            // through the session. Native does the same with remember(groupId).
            setLastReadSnapshot(repo.getLastReadTimestamp(group.id))
            wasNotAtBottom.current = false
            openedAtDivider.current = false
        }
        // Admin in a closed group: pull pending join requests explicitly + poll.
        // The standard chat REQ caps at 50 events and buries old 9021s under recent
        // chat, so the badge wouldn't appear on a fresh page load until navigating
        // away and back triggered a mux refresh. The poll also covers relays that
        // don't push 9021s on open subscriptions after EOSE, keeping the badge live.
        // The useEffect block is suspend (this wrappers version — same pattern as
        // useEscClose / useStateFlow); `delay` propagates CancellationException when
        // deps change, so leaving the group / losing admin tears down the loop.
        useEffect(group.id, isAdmin, group.isOpen) {
            if (!(isAdmin && !group.isOpen)) return@useEffect
            repo.requestPendingJoinRequests(group.id)
            while (true) {
                delay(15_000)
                repo.requestPendingJoinRequests(group.id)
            }
        }
        useEffect(group.id, members.size, messages.size) {
            val pubkeys = (members + messages.map { it.pubkey }).toSet()
            if (pubkeys.isNotEmpty()) launchApp { repo.requestUserMetadata(pubkeys) }
        }
        // Sum of all reactions across all messages — used as a stable scalar
        // dependency so the auto-scroll effect re-fires when a reaction lands
        // (otherwise reactions appearing under the user's viewport on a near-
        // bottom feed would not re-pin to bottom). Cheap: O(messages).
        val reactionCount = reactionsByMsg.values.sumOf { it.size }
        // Pagination restore — SYNC after the DOM commits, before the browser
        // paints, so the user never sees the feed mid-shift.
        //
        // Anchor-based: find the element captured when the prepend was triggered
        // (recorded in anchorElementId / anchorOffsetFromTop in onScroll) and
        // adjust scrollTop so that element ends up at the same offset from the
        // viewport top it had before. Robust against any parallel DOM change
        // because the anchor's offsetTop reflects current reality — we don't
        // care WHY it moved, only that it gets restored to its previous
        // viewport position. Matches the spirit of native's LazyColumn anchoring
        // by item key (firstVisibleItemIndex + firstVisibleItemScrollOffset).
        useLayoutEffect(messages.size) {
            if (loadingOlder.current != true) return@useLayoutEffect
            if (props.scrollToMessageId != null) return@useLayoutEffect
            val el = document.getElementById(ElementId("chat-messages")) ?: return@useLayoutEffect
            val anchorId = anchorElementId.current
            if (anchorId == null) {
                loadingOlder.current = false
                return@useLayoutEffect
            }
            val anchorEl = document.getElementById(ElementId(anchorId))
            if (anchorEl == null) {
                // Anchor unmounted between save and restore (rare — e.g., the
                // original row was a message that got deleted mid-pagination).
                // Bail without yanking the scroll.
                loadingOlder.current = false
                anchorElementId.current = null
                return@useLayoutEffect
            }
            val containerTop = el.getBoundingClientRect().top
            val currentOffset = (anchorEl.getBoundingClientRect().top as Number).toDouble() - containerTop
            val correction = currentOffset - (anchorOffsetFromTop.current ?: 0.0)
            // Positive correction means the anchor moved DOWN (prepend pushed
            // it); we add to scrollTop to scroll the viewport down by the same
            // amount, putting the anchor back exactly where it was.
            el.scrollTop = el.scrollTop + correction
            loadingOlder.current = false
            anchorElementId.current = null
        }
        // Pin to bottom when the user was already there. useLayoutEffect (pre-
        // paint) so the user sees the scrolled-to-bottom state directly on first
        // paint instead of briefly seeing the unscrolled feed for one frame.
        // Also runs before the entry-alignment layout effect below — without the
        // pre-paint scheduling, the user would see a bottom-flash followed by a
        // divider-flash on entry (the "pisca sobe-desce" the user reported).
        useLayoutEffect(messages.size, reactionCount) {
            // While a deep-link target (?e=) is pending, the seek effect owns scrolling. atBottom
            // is true on entry, so without this the auto-scroll would pin the view to the bottom on
            // every page the seek loads — the target would load but never come into view (mirrors
            // native's AutoScrollEffect `enabled = !isSeekingTarget`).
            if (props.scrollToMessageId != null) return@useLayoutEffect
            if (loadingOlder.current == true) return@useLayoutEffect
            val el = document.getElementById(ElementId("chat-messages")) ?: return@useLayoutEffect
            if (atBottom.current == true) {
                el.scrollTop = el.scrollHeight.toDouble()
                // Messages seen at the bottom are read — clear their unread + notification entries.
                repo.markGroupAsRead(group.id)
            }
        }
        // Entry alignment to the "New messages" divider — the Telegram pattern.
        // useLayoutEffect (pre-paint) so the user lands directly on the divider
        // without flashing through the pinned-to-bottom state above first.
        //
        // We scroll to the divider DOM element itself (not to the first unread
        // message). The earlier "look up msg-${firstUnread.id}" approach broke
        // on socket-heavy streams of joins / leaves: messages.firstOrNull picked
        // up a 9021 / 9022 event (no msg-${id} element exists for those — they
        // render as system rows), the lookup silently returned null, the latch
        // never flipped, and the effect re-fired on every new socket frame until
        // a kind:9 happened to satisfy the filter and pulled the viewport into
        // the middle of the feed. Anchoring to the divider element bypasses the
        // whole id-lookup race; the divider is computed by buildWebChatItems
        // with a kind:9 filter so it only appears where it should.
        useLayoutEffect(messages.size, lastReadSnapshot) {
            if (openedAtDivider.current == true) return@useLayoutEffect
            if (messages.isEmpty()) return@useLayoutEffect
            if (props.scrollToMessageId != null) return@useLayoutEffect
            if (lastReadSnapshot == null) return@useLayoutEffect
            val dividerEl = document.getElementById(ElementId("new-msg-divider")) ?: return@useLayoutEffect
            // block: 'start' puts the divider line at the top of the viewport —
            // same framing Telegram uses on entry.
            dividerEl.asDynamic().scrollIntoView(js("({ behavior: 'auto', block: 'start' })"))
            openedAtDivider.current = true
            // Mark not-at-bottom so subsequent auto-scrolls don't yank the view
            // down, and prime the round-trip gate so the divider dismissal still
            // works once the user reaches the bottom.
            atBottom.current = false
            setAtBottomState(false)
            wasNotAtBottom.current = true
        }
        // Keep the feed pinned to the bottom while async media settles. Async
        // images / videos resolve their final dimensions AFTER the initial pin
        // fires; the feed grows under the user and leaves them mid-scroll.
        //
        // The chat-content-loaded CustomEvent (from ChatImage.onLoad /
        // ChatVideo.onLoadedMetadata) was the first attempt at this but it
        // loses cached-image loads that fire synchronously before this effect
        // attaches. The ResizeObserver below catches every media element's
        // intrinsic-size change regardless of cache state, and a
        // MutationObserver attaches it to media added later (pagination, new
        // arrivals) so a single setup covers the whole session. (issue #74)
        useEffect(group.id) {
            val pinIfAtBottom = {
                // Don't pin while the pagination restore is mid-flight — the
                // ResizeObserver fires for images loading inside freshly-prepended
                // messages (above the viewport), and we'd otherwise yank the
                // user from their read position down to the bottom.
                if (atBottom.current == true &&
                    props.scrollToMessageId == null &&
                    loadingOlder.current != true
                ) {
                    val el = document.getElementById(ElementId("chat-messages"))
                    if (el != null) el.scrollTop = el.scrollHeight.toDouble()
                }
                Unit
            }
            document.asDynamic().addEventListener("chat-content-loaded", { _: dynamic -> pinIfAtBottom() })
            // Inline JS: kotlin-react has no first-class ResizeObserver /
            // MutationObserver bindings and writing it this way matches the
            // pattern already used by installGlobalModalFocusTrap.
            val cleanup =
                js(
                    """
                    (function(onResize) {
                        var container = document.getElementById('chat-messages');
                        if (!container) return function() {};
                        var ro = new ResizeObserver(function() { onResize(); });
                        function observeIn(node) {
                            if (!node || node.nodeType !== 1) return;
                            if (node.tagName === 'IMG' || node.tagName === 'VIDEO') {
                                ro.observe(node);
                            }
                            if (node.querySelectorAll) {
                                var media = node.querySelectorAll('img, video');
                                for (var i = 0; i < media.length; i++) ro.observe(media[i]);
                            }
                        }
                        // Seed with what's already mounted (covers cached images
                        // whose load event already fired before this ran).
                        observeIn(container);
                        var mo = new MutationObserver(function(records) {
                            for (var i = 0; i < records.length; i++) {
                                var added = records[i].addedNodes;
                                for (var j = 0; j < added.length; j++) observeIn(added[j]);
                            }
                        });
                        mo.observe(container, { childList: true, subtree: true });
                        return function() { ro.disconnect(); mo.disconnect(); };
                    })
                """,
                )
            val disconnect = cleanup(pinIfAtBottom)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                disconnect()
            }
        }
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
                val el = document.getElementById(ElementId("msg-$target")) ?: return@useEffect
                // Instant centering — smooth gets interrupted by the pagination re-renders. Pin
                // atBottom off so the auto-scroll effect can't yank the view back to the bottom
                // once the target is consumed and a late page lands.
                val center = js("({ behavior: 'auto', block: 'center' })")
                el.asDynamic().scrollIntoView(center)
                atBottom.current = false
                setHighlightId(target)
                props.onScrolledToMessage()
                // Re-center after late avatars/images above the target shift the layout.
                window.setTimeout({
                    document.getElementById(ElementId("msg-$target"))?.asDynamic()?.scrollIntoView(center)
                }, 400)
                window.setTimeout({ setHighlightId(null) }, 3_000)
            } else if (messages.isNotEmpty() && hasMore && !isLoadingMore) {
                launchApp { repo.loadMoreMessages(group.id) }
            }
        }

        fun send() {
            val text = draft.trim()
            if (text.isEmpty() || sending) return
            val replyId = replyingToId
            setSending(true)
            launchApp {
                val result = repo.sendMessage(group.id, text, replyToMessageId = replyId)
                setSending(false)
                if (result is Result.Success) {
                    // Clear only after publish succeeded. NIP-07 cancel / signer
                    // failure / relay reject all return Result.Error and the draft
                    // stays so the user can retry without retyping.
                    setDraft("")
                    setReplyingToId(null)
                }
            }
        }

        fun join() {
            launchApp { repo.joinGroup(group.id) }
        }

        // Scroll a loaded message into view and flash it (used by reply-preview clicks).
        fun scrollToMessage(id: String) {
            val el = document.getElementById(ElementId("msg-$id")) ?: return
            el.asDynamic().scrollIntoView(js("({ behavior: 'smooth', block: 'center' })"))
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

                // Messages
                div {
                    className = ClassName("chat-messages")
                    id = ElementId("chat-messages")
                    onScroll = { event ->
                        val el = event.currentTarget
                        val sh = el.scrollHeight.toDouble()
                        val isAtBottom = (sh - el.scrollTop - el.clientHeight.toDouble()) < 120.0
                        // Only push to React state on the transition so the FAB
                        // doesn't trigger a re-render on every scroll tick.
                        if (atBottom.current != isAtBottom) {
                            atBottom.current = isAtBottom
                            setAtBottomState(isAtBottom)
                        }
                        // "New messages" divider: gate dismissal on a round-trip — only
                        // clear once the user has scrolled away from the bottom AND back.
                        // Without the round-trip check, the entry auto-pin-to-bottom would
                        // wipe the divider on first paint and the user would never see it.
                        // (issue #83)
                        if (!isAtBottom) {
                            wasNotAtBottom.current = true
                        } else if (wasNotAtBottom.current == true && lastReadSnapshot != null) {
                            setLastReadSnapshot(null)
                        }
                        // Partial-read tracking (Gap 3): on scroll settle, find the
                        // largest createdAt among messages whose bottom is at or
                        // above the viewport bottom (i.e., fully read) and advance
                        // lastReadTimestamp to that. Mirrors native's per-visible
                        // snapshotFlow — fixes "scroll one, mark all read".
                        markReadDebounce.current?.let { window.clearTimeout(it) }
                        markReadDebounce.current =
                            window.setTimeout(
                                {
                                    val viewportBottom = el.scrollTop + el.clientHeight.toDouble()
                                    // .asDynamic() makes the chain dynamic — calling
                                    // .asDynamic() again on item(i) compiles into a JS
                                    // method invocation and blows up at runtime.
                                    val msgEls = el.asDynamic().querySelectorAll("[id^='msg-']")
                                    val len = (msgEls.length as Int)
                                    var maxSeen = 0L
                                    for (i in 0 until len) {
                                        val m = msgEls.item(i)
                                        val msgBottom =
                                            (m.offsetTop as Number).toDouble() +
                                                (m.offsetHeight as Number).toDouble()
                                        if (msgBottom > viewportBottom) continue
                                        val msgId = (m.id as String).removePrefix("msg-")
                                        val msg = messagesById[msgId] ?: continue
                                        if (msg.createdAt > maxSeen) maxSeen = msg.createdAt
                                    }
                                    if (maxSeen > 0L) {
                                        repo.markGroupAsReadUpTo(group.id, maxSeen)
                                    }
                                },
                                500,
                            )
                        // Trigger pagination well BEFORE the user reaches the top
                        // (~2 screens worth of headroom) so the older messages
                        // arrive and the scroll position is restored before the
                        // user's reading area is visibly affected.
                        val prefetchTrigger = el.clientHeight.toDouble() * 2.0
                        if (el.scrollTop < prefetchTrigger && hasMore && !isLoadingMore && loadingOlder.current != true) {
                            loadingOlder.current = true
                            // Pick the first child whose top is at or below the
                            // container's top — that's the row anchored to the
                            // viewport's top edge. Record its id and the offset
                            // from the container top; the layout effect re-finds
                            // it post-prepend and aligns scrollTop accordingly.
                            // Falls back to math if no suitable anchor is found
                            // (e.g., only skeletons in the DOM, no message id'd).
                            val containerTop = el.getBoundingClientRect().top
                            val children = el.asDynamic().children
                            val childCount = (children.length as Int)
                            var foundAnchor = false
                            for (i in 0 until childCount) {
                                val child = children[i]
                                val childId = (child.id as? String) ?: continue
                                if (childId.isBlank()) continue
                                val childTop = (child.getBoundingClientRect().top as Number).toDouble()
                                if (childTop >= containerTop - 1.0) {
                                    anchorElementId.current = childId
                                    anchorOffsetFromTop.current = childTop - containerTop
                                    foundAnchor = true
                                    break
                                }
                            }
                            if (!foundAnchor) anchorElementId.current = null
                            launchApp { repo.loadMoreMessages(group.id) }
                        }
                    }
                    if (isLoadingMore && messages.isNotEmpty()) {
                        div {
                            className = ClassName("chat-loading-more")
                            +"Loading earlier messages…"
                        }
                    }
                    // Empty-state gating: "No messages yet" is the *confirmed* empty signal,
                    // and we only know the group is empty after the historical REQ EOSE'd with
                    // nothing — that's when GroupManager flips `hasMore` to false. Until then
                    // (initial mount, awaiting AUTH/connection, REQ in flight) render skeletons
                    // so the user never sees the friendly empty-state flicker before the load
                    // has even started.
                    if (messages.isEmpty() && (isLoadingMore || hasMore)) {
                        repeat(8) { messageSkeleton() }
                    } else if (messages.isEmpty()) {
                        div {
                            className = ClassName("chat-empty")
                            +"No messages yet. Say hello 👋"
                        }
                    } else {
                        buildWebChatItems(messages, lastReadSnapshot, myPubkey).forEach { item ->
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
                                            launchApp { repo.sendReaction(group.id, message.id, message.pubkey, emoji) }
                                        }
                                        onDelete = { setMessageToDelete(message.id) }
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
                        className = ClassName("chat-jump-bottom")
                        title = "Jump to latest message"
                        onClick = {
                            val el = document.getElementById(ElementId("chat-messages"))
                            if (el != null) {
                                el.scrollTop = el.scrollHeight.toDouble()
                                // Tapping the FAB is an explicit "I've seen everything"
                                // intent — dismiss the divider for this session.
                                if (lastReadSnapshot != null) setLastReadSnapshot(null)
                            }
                        }
                        icon(Ic.ArrowDownward)
                        if (unreadCount > 0) {
                            span {
                                className = ClassName("chat-jump-badge")
                                +unreadCount.toString()
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
                        textarea {
                            ref = composerInputRef
                            className = ClassName("composer-input")
                            placeholder = "Message $groupName"
                            value = draft
                            rows = 1
                            onChange = { event ->
                                val v = event.currentTarget.value
                                setDraft(v)
                                val cursor = (event.currentTarget.asDynamic().selectionStart as? Int) ?: v.length
                                setMention(detectMention(v, cursor))
                            }
                            onKeyDown = { event ->
                                when {
                                    mention != null && mentionMatches.isNotEmpty() && event.key == "Enter" -> {
                                        event.preventDefault()
                                        insertMention(mentionMatches.first().ref)
                                    }
                                    mention != null && event.key == "Escape" -> setMention(null)
                                    // Plain Enter sends; Shift+Enter inserts a newline
                                    // (default textarea behaviour, no preventDefault).
                                    event.key == "Enter" && !event.shiftKey -> {
                                        event.preventDefault()
                                        send()
                                    }
                                }
                            }
                            onBlur = { window.setTimeout({ setMention(null) }, 150) }
                            // Ctrl/Cmd+V of any image/video/audio file: upload to
                            // nostr.build and append the URL to the draft. Matches
                            // native ClipboardImageReader, which handles both raw
                            // image bytes AND file references from the file manager.
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
                            // Drag a file from the OS file manager onto the composer:
                            // same upload path as paste. dragover.preventDefault is
                            // required to make the textarea a valid drop target —
                            // browsers reject the drop otherwise. (Native gets this
                            // via the OS clipboard; web needs the explicit gesture.)
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
                        if (mention != null && mentionMatches.isNotEmpty()) {
                            div {
                                className = ClassName("mention-popup")
                                mentionMatches.forEach { mm ->
                                    div {
                                        key = mm.ref
                                        className = ClassName("mention-row")
                                        // mousedown (before blur) + preventDefault keeps input focus.
                                        onMouseDown = { e ->
                                            e.preventDefault()
                                            insertMention(mm.ref)
                                        }
                                        WebAvatar {
                                            url = mm.picture
                                            seed = mm.seed
                                            this.name = mm.label
                                            kind = if (mm.group) AvatarKind.GROUP else AvatarKind.USER
                                            cls = "mention-avatar"
                                        }
                                        span {
                                            className = ClassName("mention-name")
                                            +(if (mm.group) "%${mm.label}" else "@${mm.label}")
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
                            // Treat the button as "ready" while uploading too — the
                            // spinner is visible only against the active background.
                            className = ClassName(
                                if (draft.isNotBlank() || uploading) "composer-send active" else "composer-send",
                            )
                            disabled = (draft.isBlank() && !uploading) || sending || uploading
                            onClick = { send() }
                            if (uploading) {
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
                    }
                }
                div {
                    className = ClassName("member-scroll")
                    val memberQ = memberQuery.trim().lowercase()
                    val shownAdmins =
                        if (memberQ.isEmpty()) {
                            adminMembers
                        } else {
                            adminMembers.filter { displayName(it, userMetadata[it]).lowercase().contains(memberQ) }
                        }
                    val shownPlain =
                        if (memberQ.isEmpty()) {
                            plainMembers
                        } else {
                            plainMembers.filter { displayName(it, userMetadata[it]).lowercase().contains(memberQ) }
                        }
                    if (membersLoading && members.isEmpty()) {
                        repeat(6) { memberSkeleton() }
                    } else {
                        if (shownAdmins.isNotEmpty()) {
                            memberSection("Admins", shownAdmins.size)
                            shownAdmins.forEach { pubkey ->
                                memberRow(
                                    pubkey,
                                    displayName(pubkey, userMetadata[pubkey]),
                                    userMetadata[pubkey]?.picture,
                                    isAdmin = true,
                                    isOnline = pubkey in recentlyActiveMembers,
                                ) { setProfilePubkey(it) }
                            }
                        }
                        if (shownPlain.isNotEmpty()) {
                            memberSection("Members", shownPlain.size)
                            shownPlain.forEach { pubkey ->
                                memberRow(
                                    pubkey,
                                    displayName(pubkey, userMetadata[pubkey]),
                                    userMetadata[pubkey]?.picture,
                                    isAdmin = false,
                                    isOnline = pubkey in recentlyActiveMembers,
                                ) { setProfilePubkey(it) }
                            }
                        }
                        if (shownAdmins.isEmpty() && shownPlain.isEmpty()) {
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
    var firstInGroup: Boolean
    var isAuthorAdmin: Boolean
    var reactions: Map<String, GroupManager.ReactionInfo>
    var myPubkey: String?
    var userMetadata: Map<String, UserMetadata>
    var messagesById: Map<String, NostrGroupClient.NostrMessage>
    var onEventRef: (String) -> Unit
    var onGroupRef: (String, String?) -> Unit
    var replyTo: Pair<String, String>?
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
    var onReact: (String) -> Unit
    var onZap: () -> Unit
    var onDelete: () -> Unit
}

private val MessageRow =
    FC<MessageRowProps> { props ->
        val (menuOpen, setMenuOpen) = useState { false }
        val (reactOpen, setReactOpen) = useState { false }
        // Anchor (viewport x,y) for the context menu; positioned/flipped by the effect below.
        val (menuAt, setMenuAt) = useState<Pair<Int, Int>?> { null }
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

        // Place the fixed context menu at its anchor, flipping left/up when it would overflow.
        useEffect(menuOpen) {
            if (!menuOpen) return@useEffect
            val el = menuRef.current?.asDynamic() ?: return@useEffect
            val anchor = menuAt ?: return@useEffect
            val w = el.offsetWidth as Int
            val h = el.offsetHeight as Int
            val vw = window.innerWidth
            val vh = window.innerHeight
            var left = anchor.first - w
            if (left < 8) left = 8
            if (left + w > vw - 8) left = (vw - 8 - w).coerceAtLeast(8)
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
            // Right-click is left to the browser's default menu (copy / inspect /
            // etc.) — feels foreign to override it on a web page. The .msg-actions
            // toolbar's ⋯ More button still opens the same .ctx-menu, so the
            // moderation actions stay reachable from the hover toolbar.
            onTouchStart = { event ->
                val t = event.asDynamic().touches[0]
                touchStartX.current = t.clientX as Double
                touchStartY.current = t.clientY as Double
                swiping.current = false
                swipeArmed.current = false
            }
            onTouchMove = { event ->
                val t = event.asDynamic().touches[0]
                val dx = (t.clientX as Double) - (touchStartX.current ?: 0.0)
                val dy = (t.clientY as Double) - (touchStartY.current ?: 0.0)
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
            onTouchEnd = {
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
                if (swipeArmed.current == true) props.onReply()
                swiping.current = false
                swipeArmed.current = false
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
                props.replyTo?.let { reply ->
                    div {
                        className = ClassName("msg-reply clickable")
                        onClick = { props.onReplyClick() }
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
                    renderMessageContent(
                        props.content,
                        props.userMetadata,
                        props.messagesById,
                        props.onUser,
                        props.onEventRef,
                        props.onGroupRef,
                    )
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
                    onClick = { setReactOpen(true) }
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
                    onClick = { event ->
                        val r = event.currentTarget.getBoundingClientRect()
                        setMenuAt(r.right.toInt() to r.bottom.toInt())
                        setMenuOpen(!menuOpen)
                    }
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
                    ref = menuRef
                    className = ClassName("ctx-menu")
                    ctxItem(Ic.EmojiEmotions, "Add Reaction") {
                        setMenuOpen(false)
                        setReactOpen(true)
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

            // Reaction emoji picker (opened from the 😊 hover action or the context menu).
            if (reactOpen) {
                div {
                    className = ClassName("emoji-overlay")
                    onClick = { setReactOpen(false) }
                    EmojiPicker {
                        onPick = { emoji ->
                            props.onReact(emoji)
                            setReactOpen(false)
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

private val CODE_BLOCK_REGEX = Regex("```(?:([A-Za-z0-9_+-]*)\n)?([\\s\\S]*?)```")
private val INLINE_CODE_REGEX = Regex("`([^`\n]+)`")

/**
 * Render message text: fenced code blocks (```), then inline `code`, then links / images /
 * videos / NIP-27 mentions / event & group refs in the remaining text — mirroring native.
 */
private fun ChildrenBuilder.renderMessageContent(
    content: String,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (block in CODE_BLOCK_REGEX.findAll(content)) {
        if (block.range.first > last) {
            renderInline(content.substring(last, block.range.first), userMetadata, messagesById, onUser, onEventRef, onGroupRef)
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
        renderInline(content.substring(last), userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

/** Render a non-code text span: inline `code` as monospace, the rest through [renderEntities]. */
private fun ChildrenBuilder.renderInline(
    text: String,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (m in INLINE_CODE_REGEX.findAll(text)) {
        if (m.range.first > last) {
            renderEntities(text.substring(last, m.range.first), userMetadata, messagesById, onUser, onEventRef, onGroupRef)
        }
        code {
            className = ClassName("msg-code")
            +m.groupValues[1]
        }
        last = m.range.last + 1
    }
    if (last < text.length) {
        renderEntities(text.substring(last), userMetadata, messagesById, onUser, onEventRef, onGroupRef)
    }
}

private fun ChildrenBuilder.renderEntities(
    content: String,
    userMetadata: Map<String, UserMetadata>,
    messagesById: Map<String, NostrGroupClient.NostrMessage>,
    onUser: (String) -> Unit,
    onEventRef: (String) -> Unit,
    onGroupRef: (String, String?) -> Unit,
) {
    var last = 0
    for (match in URL_REGEX.findAll(content)) {
        if (match.range.first > last) +content.substring(last, match.range.first)
        val token = match.value
        if (token.startsWith("data:image/")) {
            ChatImage { imageUrl = token }
        } else if (token.startsWith("http")) {
            val url = token.trimEnd('.', ',', ')', '!', '?', ';', ':')
            if (IMAGE_EXT.containsMatchIn(url)) {
                ChatImage { imageUrl = url }
            } else if (VIDEO_EXT.containsMatchIn(url)) {
                ChatVideo { videoUrl = url }
            } else {
                a {
                    className = ClassName("msg-link")
                    href = url
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
                            +"[article]"
                        }
                    }
                else -> +token
            }
        }
        last = match.range.last + 1
    }
    if (last < content.length) +content.substring(last)
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
