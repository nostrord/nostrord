package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.MessageItem
import org.nostr.nostrord.ui.components.chat.NewMessagesDivider
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.components.chat.ZapEventItem
import org.nostr.nostrord.ui.components.loading.MessagesListSkeleton
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Messages list with pull-to-refresh and infinite scroll.
 *
 * Features:
 * - Pull-to-refresh: Swipe down from top to refresh messages
 * - Infinite scroll: Automatically loads older messages when scrolling up
 * - Scroll position preservation: Saves/restores position per group
 * - Auto-scroll: Scrolls to new messages when user is near bottom
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {},
    onReplyClick: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onScrollToMessage: (String) -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> }
) {
    // Stable callback references
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnReplyClick by rememberUpdatedState(onReplyClick)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    // Get current relay URL for forwarded message detection
    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()

    // Clipboard manager for copy operations
    val copyToClipboard = rememberClipboardWriter()

    val listState = rememberLazyListState()

    // Scroll state holder for this group (survives recomposition & config changes)
    val scrollStateHolder = rememberScrollStateHolder(groupId)

    // Get stable key for a chat item
    fun getItemKey(item: ChatItem): String = when (item) {
        is ChatItem.DateSeparator -> "date_${item.date}"
        is ChatItem.NewMessagesDivider -> "new_messages_divider"
        is ChatItem.SystemEvent -> "system_${item.id}"
        is ChatItem.Message -> "msg_${item.message.id}"
        is ChatItem.ZapEvent -> "zap_${item.id}"
    }

    // === SCROLL POSITION MANAGEMENT ===
    ScrollPositionEffect(
        groupId = groupId,
        listState = listState,
        items = chatItems,
        stateHolder = scrollStateHolder,
        getItemKey = ::getItemKey,
        initialScrollToEnd = true
    )

    // === AUTO-SCROLL FOR NEW MESSAGES ===
    AutoScrollEffect(
        listState = listState,
        items = chatItems,
        enabled = scrollStateHolder.isRestored || !scrollStateHolder.isRestorationPending
    )

    // === INFINITE SCROLL: Load more when near top ===
    LaunchedEffect(listState, hasMoreMessages, isLoadingMore) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
            val totalItems = layoutInfo.totalItemsCount
            Triple(firstVisibleItem, totalItems, hasMoreMessages && !isLoadingMore && totalItems > 0)
        }
            .distinctUntilChanged()
            .filter { (firstVisible, _, canLoad) ->
                firstVisible <= 5 && canLoad
            }
            .collect {
                currentOnLoadMore()
            }
    }

    // === UI ===
    when {
        isInitialLoading && chatItems.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                MessagesListSkeleton(
                    count = 8,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
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
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = currentOnRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                SelectionContainer {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Loading indicator at top
                            if (isLoadingMore) {
                                item(key = "loading_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(Spacing.sm),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(Spacing.iconMd),
                                            color = NostrordColors.Primary,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }

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
                                        allMessages = messages,
                                        allUserMetadata = userMetadata,
                                        isFirstInGroup = item.isFirstInGroup,
                                        isLastInGroup = item.isLastInGroup,
                                        reactions = reactions[item.message.id] ?: emptyMap(),
                                        currentUserPubkey = currentUserPubkey,
                                        currentGroupId = groupId,
                                        currentRelayUrl = currentRelayUrl,
                                        onUsernameClick = currentOnUsernameClick,
                                        onReplyClick = { currentOnReplyClick(item.message) },
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
                    }
                }
            }
        }
    }
}
