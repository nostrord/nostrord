package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.nostr.nostrord.utils.rememberClipboardWriter
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.GroupManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.ImageViewerModal
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.chat.LocalImageViewerUrl
import org.nostr.nostrord.ui.components.chat.MessageItem
import org.nostr.nostrord.ui.components.chat.NewMessagesDivider
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.components.chat.ZapEventItem
import org.nostr.nostrord.ui.components.emoji.EmojiPicker
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

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
    currentUserPubkey: String? = null,
    isJoined: Boolean,
    isInitialLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {},
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onScrollToMessage: (String) -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> }
) {
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnReplyClick by rememberUpdatedState(onReplyClick)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)

    var reactingToMessageId by remember { mutableStateOf<String?>(null) }
    val imageViewerUrl = remember { mutableStateOf<String?>(null) }
    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
    val copyToClipboard = rememberClipboardWriter()

    // Hide ALL animated HTML overlays when the image viewer modal is open
    val parentHidden = LocalAnimatedImageHidden.current

    // Initialize at the bottom so the chat opens at the newest messages.
    val hasItems = chatItems.isNotEmpty()
    val listState = remember(groupId, hasItems) {
        if (hasItems) {
            LazyListState(firstVisibleItemIndex = chatItems.lastIndex)
        } else {
            LazyListState()
        }
    }

    val scrollStateHolder = rememberScrollStateHolder(groupId)

    fun getItemKey(item: ChatItem): String = when (item) {
        is ChatItem.DateSeparator -> "date_${item.date}"
        is ChatItem.NewMessagesDivider -> "new_messages_divider"
        is ChatItem.SystemEvent -> "system_${item.id}"
        is ChatItem.Message -> "msg_${item.message.id}"
        is ChatItem.ZapEvent -> "zap_${item.id}"
    }

    ScrollPositionEffect(
        groupId = groupId,
        listState = listState,
        items = chatItems,
        stateHolder = scrollStateHolder,
        getItemKey = ::getItemKey,
        initialScrollToEnd = true
    )

    AutoScrollEffect(
        listState = listState,
        items = chatItems,
        getItemKey = ::getItemKey,
        enabled = scrollStateHolder.isRestored || !scrollStateHolder.isRestorationPending
    )

    // Correct scroll position after pagination prepends items.
    val currentHasMore by rememberUpdatedState(hasMoreMessages)
    val currentIsLoadingMore by rememberUpdatedState(isLoadingMore)
    var previousFirstKey by remember(groupId) { mutableStateOf<String?>(null) }
    LaunchedEffect(groupId, chatItems.size) {
        if (chatItems.isEmpty()) return@LaunchedEffect
        val currentFirstKey = getItemKey(chatItems.first())
        val prevKey = previousFirstKey
        previousFirstKey = currentFirstKey

        if (prevKey != null && currentFirstKey != prevKey) {
            val saved = scrollStateHolder.savedPosition ?: return@LaunchedEffect
            val newIndex = chatItems.indexOfFirst { getItemKey(it) == saved.anchorKey }
            if (newIndex >= 0) {
                listState.scrollToItem(newIndex, saved.offset)
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
        }
            .distinctUntilChanged()
            .filter { (firstVisible, _, canLoad) ->
                firstVisible <= 5 && canLoad
            }
            .collect {
                currentOnLoadMore()
            }
    }

    CompositionLocalProvider(
        LocalAnimatedImageHidden provides (parentHidden || imageViewerUrl.value != null),
        LocalImageViewerUrl provides imageViewerUrl
    ) {
    when {
        isInitialLoading && chatItems.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Loading messages…",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }
        chatItems.isEmpty() -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No messages yet",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (isJoined) "Be the first to send a message!" else "Join the group to participate!",
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        else -> {
            SelectionContainer {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
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
                            }
                        ) { _, item ->
                            when (item) {
                                is ChatItem.DateSeparator -> DateSeparator(item.date)
                                is ChatItem.NewMessagesDivider -> NewMessagesDivider()
                                is ChatItem.SystemEvent -> SystemEventItem(
                                    pubkey = item.pubkey,
                                    action = item.action,
                                    createdAt = item.createdAt,
                                    metadata = userMetadata[item.pubkey],
                                    additionalUsers = item.additionalUsers,
                                    allUserMetadata = userMetadata
                                )
                                is ChatItem.Message -> MessageItem(
                                    message = item.message,
                                    metadata = userMetadata[item.message.pubkey],
                                    resolveReplyMessage = { id -> messages.find { it.id == id } },
                                    resolveMetadata = { pubkey -> userMetadata[pubkey] },
                                    isFirstInGroup = item.isFirstInGroup,
                                    isLastInGroup = item.isLastInGroup,
                                    reactions = reactions[item.message.id] ?: emptyMap(),
                                    isAuthor = currentUserPubkey != null && item.message.pubkey == currentUserPubkey,
                                    currentUserPubkey = currentUserPubkey,
                                    currentGroupId = groupId,
                                    currentRelayUrl = currentRelayUrl,
                                    onUsernameClick = currentOnUsernameClick,
                                    onReplyClick = { currentOnReplyClick(item.message) },
                                    onReactionClick = { reactingToMessageId = item.message.id },
                                    onDeleteMessage = { onDeleteMessage(item.message) },
                                    onReactionBadgeClick = { emoji ->
                                        onReactionBadgeClick(item.message.id, emoji)
                                    },
                                    onScrollToMessage = onScrollToMessage,
                                    onNavigateToGroup = onNavigateToGroup,
                                    onCopyJson = {
                                        val msg = item.message
                                        val json = buildJsonObject {
                                            put("id", msg.id)
                                            put("pubkey", msg.pubkey)
                                            put("created_at", msg.createdAt)
                                            put("kind", msg.kind)
                                            put("tags", buildJsonArray {
                                                msg.tags.forEach { tag ->
                                                    add(buildJsonArray {
                                                        tag.forEach { add(JsonPrimitive(it)) }
                                                    })
                                                }
                                            })
                                            put("content", msg.content)
                                        }.toString()
                                        copyToClipboard(json)
                                    }
                                )
                                is ChatItem.ZapEvent -> ZapEventItem(
                                    senderPubkey = item.senderPubkey,
                                    recipientPubkey = item.recipientPubkey,
                                    amount = item.amount,
                                    content = item.content,
                                    senderMetadata = userMetadata[item.senderPubkey],
                                    recipientMetadata = userMetadata[item.recipientPubkey],
                                    onSenderClick = currentOnUsernameClick,
                                    onRecipientClick = currentOnUsernameClick
                                )
                            }
                        }
                    }

                    VerticalScrollbarWrapper(
                        listState = listState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )

                    AnimatedVisibility(
                        visible = isLoadingMore && hasMoreMessages,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(NostrordColors.Background.copy(alpha = 0.85f))
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = NostrordColors.TextMuted,
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Loading messages…",
                                color = NostrordColors.TextMuted,
                                fontSize = 11.sp
                            )
                        }
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
            onDismiss = { imageViewerUrl.value = null }
        )
    }

    // Reaction emoji picker popup
    if (reactingToMessageId != null) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { reactingToMessageId = null },
            properties = PopupProperties(
                focusable = true,
                dismissOnClickOutside = false,
                dismissOnBackPress = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { reactingToMessageId = null }
                    )
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
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
