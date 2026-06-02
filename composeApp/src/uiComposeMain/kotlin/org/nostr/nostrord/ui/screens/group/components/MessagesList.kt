package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.ImageViewerModal
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.chat.LocalImageViewerUrl
import org.nostr.nostrord.ui.components.chat.MessageItem
import org.nostr.nostrord.ui.components.chat.MessageSelectionContainer
import org.nostr.nostrord.ui.components.chat.NewMessagesDivider
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.components.chat.ZapEventItem
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.scroll.ScrollEntryTarget
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.buildShareMessageLink
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.rememberTextSharer
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Messages list with infinite scroll pagination.
 * LazyListState starts at the last item; key-based anchor correction
 * keeps the viewport stable when older messages are prepended.
 */
@Composable
fun MessagesList(
    groupId: String,
    chatItems: List<ChatItem>,
    messages: List<NostrGroupClient.NostrMessage> = emptyList(),
    userMetadata: Map<String, UserMetadata>,
    reactions: Map<String, Map<String, GroupManager.ReactionInfo>> = emptyMap(),
    // In-flight reactions keyed "$messageId|$emoji"; rendered as spinner placeholders.
    pendingReactions: Set<String> = emptySet(),
    currentUserPubkey: String? = null,
    isJoined: Boolean,
    isInitialLoading: Boolean = false,
    isPendingApproval: Boolean = false,
    isGroupRestricted: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {},
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onScrollToMessage: (String) -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> },
    onReachedBottom: () -> Unit = {},
    // Fired when the user scrolls up away from the bottom. Used by the
    // "New messages" divider dismissal (issue #83).
    onLeftBottom: () -> Unit = {},
    // Fired with the createdAt of the latest fully-visible message — drives
    // partial-read tracking (mark-as-read up to the message the user has
    // actually reached, instead of the binary "all or nothing" of
    // onReachedBottom).
    onSeenUpTo: (Long) -> Unit = {},
    // Count of unread messages from other users — drives the FAB badge
    // (Telegram pattern: when there are unread messages and the user has
    // scrolled away, the jump-to-bottom button shows a count badge).
    unreadFromOthersCount: Int = 0,
    targetMessageId: String? = null,
    onTargetConsumed: () -> Unit = {},
    onFetchTargetById: (String) -> Unit = {},
    swipeToReplyEnabled: Boolean = false,
) {
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnReplyClick by rememberUpdatedState(onReplyClick)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentOnReachedBottom by rememberUpdatedState(onReachedBottom)
    val currentOnLeftBottom by rememberUpdatedState(onLeftBottom)
    val currentOnSeenUpTo by rememberUpdatedState(onSeenUpTo)
    val currentOnFetchTargetById by rememberUpdatedState(onFetchTargetById)
    val currentChatItems by rememberUpdatedState(chatItems)

    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var reactingToMessageId by remember { mutableStateOf<String?>(null) }
    // Hoisted so only one message context menu can be open at a time
    var openContextMenuId by remember { mutableStateOf<String?>(null) }
    // Guard against the web-mobile "blink": a single tap on the source message can both
    // dismiss the menu and re-fire the open gesture, reopening it. After a menu closes,
    // briefly ignore an open request for that same message so it does not flash back open.
    var lastClosedMenuId by remember { mutableStateOf<String?>(null) }
    var lastClosedMark by remember { mutableStateOf<TimeMark?>(null) }
    val closeContextMenu = {
        lastClosedMenuId = openContextMenuId
        lastClosedMark = TimeSource.Monotonic.markNow()
        openContextMenuId = null
    }
    val imageViewerUrl = remember { mutableStateOf<String?>(null) }
    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
    val copyToClipboard = rememberClipboardWriter()
    val shareText = rememberTextSharer()

    // Hide ALL animated HTML overlays when the image viewer modal is open
    val parentHidden = LocalAnimatedImageHidden.current

    // Initialize at the bottom so the chat opens at the newest messages. The
    // entry-time alignment to the "New messages" divider (Telegram pattern)
    // happens via a one-shot LaunchedEffect below — keying that off the divider
    // index here would re-anchor every time pagination prepended older messages
    // and pushed the index forward.
    val hasItems = chatItems.isNotEmpty()
    val listState =
        remember(groupId, hasItems) {
            if (hasItems) {
                LazyListState(firstVisibleItemIndex = chatItems.lastIndex)
            } else {
                LazyListState()
            }
        }
    val scrollStateHolder = rememberScrollStateHolder(groupId)
    val isSeekingTarget = targetMessageId != null

    // One-shot entry alignment to the "New messages" divider (Telegram pattern).
    // Fires once when a divider first appears after entering the group, then
    // latches openedAtDivider so streaming chunks / pagination don't re-anchor.
    // Setting atBottom = false here is what suppresses the bottom-pin from yanking
    // the view down on later chunks — the single authority the whole scroll system
    // now reads. No divider (everything already read) leaves the latch at its
    // default atBottom = true, so the group simply opens at the bottom.
    LaunchedEffect(groupId, chatItems) {
        if (scrollStateHolder.openedAtDivider || chatItems.isEmpty()) return@LaunchedEffect
        val idx = chatItems.indexOfFirst { it is ChatItem.NewMessagesDivider }
        val target = scrollStateHolder.applyEntryChange(hasDivider = idx >= 0, isSeeking = isSeekingTarget)
        if (target == ScrollEntryTarget.Divider && idx >= 0) {
            listState.scrollToItem(idx)
        }
    }

    fun getItemKey(item: ChatItem): String = when (item) {
        is ChatItem.DateSeparator -> "date_${item.date}"
        is ChatItem.NewMessagesDivider -> "new_messages_divider"
        is ChatItem.SystemEvent -> "system_${item.id}"
        is ChatItem.Message -> "msg_${item.message.id}"
        is ChatItem.ZapEvent -> "zap_${item.id}"
    }

    val currentOnTargetConsumed by rememberUpdatedState(onTargetConsumed)

    // Correct scroll position after pagination prepends items.
    val currentHasMore by rememberUpdatedState(hasMoreMessages)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)

    ScrollPositionEffect(
        groupId = groupId,
        listState = listState,
        items = chatItems,
        stateHolder = scrollStateHolder,
        getItemKey = ::getItemKey,
        initialScrollToEnd = !isSeekingTarget,
    )

    AutoScrollEffect(
        listState = listState,
        items = chatItems,
        getItemKey = ::getItemKey,
        enabled = (scrollStateHolder.isRestored || !scrollStateHolder.isRestorationPending) && !isSeekingTarget,
        isFromCurrentUser = { item ->
            item is ChatItem.Message &&
                currentUserPubkey != null &&
                item.message.pubkey == currentUserPubkey
        },
    )

    // Fetch by ID immediately — covers cursor-drift misses independently of pagination.
    LaunchedEffect(groupId, targetMessageId) {
        val id = targetMessageId ?: return@LaunchedEffect
        currentOnFetchTargetById(id)
    }

    var seekScrollApplied by remember(groupId) { mutableStateOf(false) }
    var highlightedMessageId by remember(groupId) { mutableStateOf<String?>(null) }
    var internalScrollTarget by remember(groupId) { mutableStateOf<String?>(null) }

    LaunchedEffect(internalScrollTarget, chatItems.size, hasMoreMessages, isLoadingMore) {
        val id = internalScrollTarget ?: return@LaunchedEffect
        val idx = chatItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        when {
            idx >= 0 -> {
                highlightedMessageId = id
                listState.animateScrollToItem(idx)
                internalScrollTarget = null
            }
            chatItems.isNotEmpty() && hasMoreMessages && !isLoadingMore -> {
                currentOnFetchTargetById(id)
                currentOnLoadMore()
            }
        }
    }

    LaunchedEffect(internalScrollTarget, hasMoreMessages, isLoadingMore) {
        val id = internalScrollTarget ?: return@LaunchedEffect
        if (hasMoreMessages || isLoadingMore) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        val snapshot = currentChatItems
        val idx = snapshot.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (idx >= 0) {
            highlightedMessageId = id
            listState.animateScrollToItem(idx)
        }
        internalScrollTarget = null
    }

    // hasMoreMessages and isLoadingMore are keys so the effect re-fires on the
    // InitialLoading→HasMore transition (state change without chatItems.size changing).
    LaunchedEffect(chatItems.size, targetMessageId, hasMoreMessages, isLoadingMore) {
        val id =
            targetMessageId ?: run {
                seekScrollApplied = false
                return@LaunchedEffect
            }
        val idx = chatItems.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        when {
            idx >= 0 -> {
                seekScrollApplied = true
                highlightedMessageId = id
                listState.scrollToItem(idx)
                currentOnTargetConsumed()
            }
            chatItems.isNotEmpty() && hasMoreMessages && !isLoadingMore -> currentOnLoadMore()
        }
    }

    // Fallback after exhaustion: delay 500ms to let the eventOrderingBuffer (300ms debounce)
    // flush the last page, then check once more before giving up.
    // Empty snapshot means the relay served nothing (relay switch in progress) — preserve target.
    LaunchedEffect(targetMessageId, hasMoreMessages, isLoadingMore) {
        val id = targetMessageId ?: return@LaunchedEffect
        if (hasMoreMessages || isLoadingMore) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        val snapshot = currentChatItems
        val idx = snapshot.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (idx >= 0) {
            seekScrollApplied = true
            highlightedMessageId = id
            listState.scrollToItem(idx)
            currentOnTargetConsumed()
            return@LaunchedEffect
        }
        if (snapshot.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(snapshot.lastIndex)
        currentOnTargetConsumed()
    }

    // Correct scroll position after pagination prepends items.
    var previousFirstKey by remember(groupId) { mutableStateOf<String?>(null) }
    LaunchedEffect(groupId, chatItems.size) {
        if (chatItems.isEmpty()) return@LaunchedEffect
        val currentFirstKey = getItemKey(chatItems.first())
        val prevKey = previousFirstKey
        previousFirstKey = currentFirstKey

        if (seekScrollApplied) {
            seekScrollApplied = false
            return@LaunchedEffect
        }

        if (prevKey != null && currentFirstKey != prevKey) {
            val saved = scrollStateHolder.savedPosition
            val newIndex = saved?.let { chatItems.indexOfFirst { getItemKey(it) == saved.anchorKey } } ?: -1
            if (newIndex >= 0) {
                listState.scrollToItem(newIndex, saved!!.offset)
            }
        }
    }

    // Load more when scrolled near top.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
            val totalItems = layoutInfo.totalItemsCount
            Triple(firstVisibleItem, totalItems, currentHasMore && !currentIsLoadingMore && totalItems > 0)
        }.distinctUntilChanged()
            .filter { (firstVisible, _, canLoad) ->
                firstVisible <= 5 && canLoad
            }.collect {
                currentOnLoadMore()
            }
    }

    // Mark as read when the user has scrolled to (or is pinned at) the bottom.
    // Debounced so rapid scrolls don't hammer storage. Also fires onLeftBottom on
    // the opposite transition so callers can detect a round-trip (used by the
    // "New messages" divider dismissal — issue #83).
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            lastVisible >= 0 && total > 0 && lastVisible >= total - 2
        }.distinctUntilChanged()
            .debounce(500)
            .collect { atBottom ->
                if (atBottom) currentOnReachedBottom() else currentOnLeftBottom()
            }
    }

    // Partial-read tracking: emit the createdAt of the latest *fully-visible*
    // message message so the screen can advance lastReadTimestamp incrementally.
    // Fixes the Telegram bug where scrolling one of ten unread messages marked
    // all ten as read — the user only gets credit for what they actually saw.
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val viewportEnd = layoutInfo.viewportEndOffset
            layoutInfo.visibleItemsInfo
                .filter { it.offset + it.size <= viewportEnd }
                .mapNotNull { info ->
                    (currentChatItems.getOrNull(info.index) as? ChatItem.Message)
                        ?.message?.createdAt
                }
                .maxOrNull() ?: 0L
        }.debounce(500)
            .filter { it > 0L }
            .distinctUntilChanged()
            .collect { currentOnSeenUpTo(it) }
    }

    // Compensate the LazyColumn's scroll as the IME animates so visible content rides
    // with the input bar. The IME absorbs the navigation bar inset on Android, so the
    // viewport actually shrinks by (ime - navBars), not by ime alone. Skip while any
    // overlay is showing — opening an image viewer or modal closes the IME as a focus
    // side-effect and we don't want the chat to scroll behind the overlay. Negative
    // deltas are also skipped at the end of the list because LazyColumn auto-clamps.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val overlayObscured by rememberUpdatedState(parentHidden || imageViewerUrl.value != null)
    LaunchedEffect(listState, density) {
        var previous: Int? = null
        snapshotFlow {
            maxOf(0, imeInsets.getBottom(density) - navInsets.getBottom(density))
        }
            .distinctUntilChanged()
            .collect { current ->
                val prev = previous
                previous = current
                if (prev == null) return@collect
                if (overlayObscured) return@collect
                val delta = current - prev
                if (delta > 0 || listState.canScrollForward) {
                    listState.scrollBy(delta.toFloat())
                }
            }
    }

    CompositionLocalProvider(
        LocalAnimatedImageHidden provides (parentHidden || imageViewerUrl.value != null || reactingToMessageId != null),
        LocalImageViewerUrl provides imageViewerUrl,
    ) {
        when {
            isPendingApproval || isGroupRestricted -> {
                Column(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (isPendingApproval) "Awaiting admin approval" else "Private group",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isPendingApproval) {
                            "Messages will appear once an admin approves your request."
                        } else {
                            "You need an invite code or admin approval to see messages."
                        },
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            isInitialLoading && chatItems.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = NostrordColors.Primary,
                            strokeWidth = 2.5.dp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading messages…",
                            color = NostrordColors.TextMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            chatItems.isEmpty() -> {
                Column(
                    modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "No messages yet",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isJoined) "Be the first to send a message!" else "Join the group to participate!",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> {
                MessageSelectionContainer {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            itemsIndexed(
                                items = chatItems,
                                key = { _, item -> getItemKey(item) },
                                contentType = { _, item ->
                                    when (item) {
                                        is ChatItem.DateSeparator -> "date_separator"
                                        is ChatItem.NewMessagesDivider -> "new_messages_divider"
                                        is ChatItem.SystemEvent -> "system_event"
                                        is ChatItem.Message -> "message"
                                        is ChatItem.ZapEvent -> "zap_event"
                                    }
                                },
                            ) { _, item ->
                                when (item) {
                                    is ChatItem.DateSeparator -> DateSeparator(item.date)
                                    is ChatItem.NewMessagesDivider -> NewMessagesDivider()
                                    is ChatItem.SystemEvent ->
                                        SystemEventItem(
                                            pubkey = item.pubkey,
                                            action = item.action,
                                            createdAt = item.createdAt,
                                            type = item.type,
                                            metadata = userMetadata[item.pubkey],
                                            additionalUsers = item.additionalUsers,
                                            allUserMetadata = userMetadata,
                                            onAvatarClick = currentOnUsernameClick,
                                        )
                                    is ChatItem.Message ->
                                        MessageItem(
                                            message = item.message,
                                            metadata = userMetadata[item.message.pubkey],
                                            resolveReplyMessage = { id -> messages.find { it.id == id } },
                                            resolveMetadata = { pubkey -> userMetadata[pubkey] },
                                            isFirstInGroup = item.isFirstInGroup,
                                            isLastInGroup = item.isLastInGroup,
                                            reactions = reactions[item.message.id] ?: emptyMap(),
                                            pendingReactionEmojis =
                                            pendingReactions
                                                .asSequence()
                                                .filter { it.startsWith("${item.message.id}|") }
                                                .map { it.substringAfter('|') }
                                                .toSet(),
                                            isAuthor = currentUserPubkey != null && item.message.pubkey == currentUserPubkey,
                                            currentUserPubkey = currentUserPubkey,
                                            currentGroupId = groupId,
                                            currentRelayUrl = currentRelayUrl,
                                            swipeToReplyEnabled = swipeToReplyEnabled,
                                            onUsernameClick = currentOnUsernameClick,
                                            onReplyClick = { currentOnReplyClick(item.message) },
                                            onReactionClick = { reactingToMessageId = item.message.id },
                                            onDeleteMessage = { onDeleteMessage(item.message) },
                                            onReactionBadgeClick = { emoji ->
                                                onReactionBadgeClick(item.message.id, emoji)
                                            },
                                            onScrollToMessage = { id -> internalScrollTarget = id },
                                            onNavigateToGroup = onNavigateToGroup,
                                            isHighlighted = item.message.id == highlightedMessageId,
                                            isContextMenuOpen = openContextMenuId == item.message.id,
                                            onContextMenuOpenChange = { open ->
                                                val id = item.message.id
                                                if (open) {
                                                    val mark = lastClosedMark
                                                    val recentlyClosed =
                                                        id == lastClosedMenuId &&
                                                            mark != null &&
                                                            mark.elapsedNow() < 350.milliseconds
                                                    if (!recentlyClosed) {
                                                        // Drop input focus before the focusable
                                                        // menu Popup opens. Otherwise dismissing
                                                        // it (e.g. Android back) returns focus to
                                                        // the message field and re-shows the
                                                        // keyboard instead of just closing the menu.
                                                        focusManager.clearFocus(force = true)
                                                        openContextMenuId = id
                                                    }
                                                } else if (openContextMenuId == id) {
                                                    closeContextMenu()
                                                }
                                            },
                                            onCopyText = { copyToClipboard(item.message.content) },
                                            onCopyLink = {
                                                val relay = currentRelayUrl ?: return@MessageItem
                                                copyToClipboard(buildShareMessageLink(relay, groupId, item.message.id))
                                            },
                                            onShareLink = {
                                                val relay = currentRelayUrl ?: return@MessageItem
                                                shareText(buildShareMessageLink(relay, groupId, item.message.id))
                                            },
                                            onCopyJson = {
                                                val msg = item.message
                                                val json =
                                                    buildJsonObject {
                                                        put("id", msg.id)
                                                        put("pubkey", msg.pubkey)
                                                        put("created_at", msg.createdAt)
                                                        put("kind", msg.kind)
                                                        put(
                                                            "tags",
                                                            buildJsonArray {
                                                                msg.tags.forEach { tag ->
                                                                    add(
                                                                        buildJsonArray {
                                                                            tag.forEach { add(JsonPrimitive(it)) }
                                                                        },
                                                                    )
                                                                }
                                                            },
                                                        )
                                                        put("content", msg.content)
                                                    }.toString()
                                                copyToClipboard(json)
                                            },
                                        )
                                    is ChatItem.ZapEvent ->
                                        ZapEventItem(
                                            senderPubkey = item.senderPubkey,
                                            recipientPubkey = item.recipientPubkey,
                                            amount = item.amount,
                                            content = item.content,
                                            senderMetadata = userMetadata[item.senderPubkey],
                                            recipientMetadata = userMetadata[item.recipientPubkey],
                                            onSenderClick = currentOnUsernameClick,
                                            onRecipientClick = currentOnUsernameClick,
                                        )
                                }
                            }
                        }

                        VerticalScrollbarWrapper(
                            listState = listState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )

                        AnimatedVisibility(
                            visible = isLoadingMore && hasMoreMessages,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopCenter),
                        ) {
                            Row(
                                modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .background(NostrordColors.Background.copy(alpha = 0.85f))
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = NostrordColors.TextMuted,
                                    strokeWidth = 1.5.dp,
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Loading messages…",
                                    color = NostrordColors.TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = scrollStateHolder.isScrolledAway,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
                        ) {
                            // FAB + badge overlay (Telegram pattern). The badge sits
                            // at the top-right corner of the FAB and only renders when
                            // there's at least one unread message from someone else.
                            Box(contentAlignment = Alignment.TopEnd) {
                                SmallFloatingActionButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            val lastIndex = chatItems.lastIndex
                                            val distance = lastIndex - listState.firstVisibleItemIndex
                                            if (distance <= 30) {
                                                listState.animateScrollToItem(lastIndex)
                                            } else {
                                                listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                                            }
                                        }
                                    },
                                    containerColor = NostrordColors.Primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Jump to latest message",
                                    )
                                }
                                if (unreadFromOthersCount > 0) {
                                    UnreadBadge(
                                        count = unreadFromOthersCount,
                                        size = 18.dp,
                                        modifier = Modifier.offset(x = 6.dp, y = (-4).dp),
                                    )
                                }
                            }
                        }

                        // Dismiss the open message context menu when tapping anywhere
                        // outside it. Popup.dismissOnClickOutside is unreliable for touch on
                        // web; the menu Popup still renders above this overlay.
                        if (openContextMenuId != null) {
                            Box(
                                modifier =
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTapGestures { closeContextMenu() }
                                    },
                            )
                        }
                    }
                }
            }
        }
    } // CompositionLocalProvider

    // Image viewer modal — rendered at MessagesList level so ALL animated images are hidden
    imageViewerUrl.value?.let { url ->
        ImageViewerModal(
            imageUrl = url,
            onDismiss = { imageViewerUrl.value = null },
        )
    }

    // Reaction emoji picker popup
    if (reactingToMessageId != null) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { reactingToMessageId = null },
            properties =
            PopupProperties(
                focusable = true,
                dismissOnClickOutside = false,
                dismissOnBackPress = true,
            ),
        ) {
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { reactingToMessageId = null },
                    ),
            ) {
                EmojiPicker(
                    onEmojiSelect = { emoji ->
                        val msgId = reactingToMessageId
                        if (msgId != null) {
                            onReactionBadgeClick(msgId, emoji)
                        }
                        reactingToMessageId = null
                    },
                    onDismiss = { reactingToMessageId = null },
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}
