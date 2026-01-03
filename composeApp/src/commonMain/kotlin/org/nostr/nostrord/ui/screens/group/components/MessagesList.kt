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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.chat.DateSeparator
import org.nostr.nostrord.ui.components.chat.MessageItem
import org.nostr.nostrord.ui.components.chat.NewMessagesDivider
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.components.loading.MessagesListSkeleton
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Messages list with pull-to-refresh and infinite scroll.
 *
 * Pull-to-refresh: Swipe down from top to refresh messages
 * Infinite scroll: Automatically loads older messages when scrolling up
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesList(
    groupId: String,
    chatItems: List<ChatItem>,
    userMetadata: Map<String, UserMetadata>,
    isJoined: Boolean,
    isInitialLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onUsernameClick: (String) -> Unit = {}
) {
    // Use rememberUpdatedState to prevent unnecessary recompositions when callback references change
    val currentOnUsernameClick by rememberUpdatedState(onUsernameClick)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentOnRefresh by rememberUpdatedState(onRefresh)

    val listState = rememberLazyListState()

    // Helper to get stable key for a chat item (must not depend on index for anchor tracking)
    fun getItemKey(item: ChatItem): String = when (item) {
        is ChatItem.DateSeparator -> "date_${item.date}"
        is ChatItem.NewMessagesDivider -> "new_messages_divider"
        is ChatItem.SystemEvent -> "system_${item.id}"
        is ChatItem.Message -> "msg_${item.message.id}"
    }

    // Track scroll position by key for pagination
    var anchorKey by remember { mutableStateOf<String?>(null) }
    var anchorOffset by remember { mutableStateOf(0) }
    var previousItemCount by remember { mutableStateOf(0) }
    var hasInitiallyScrolled by remember { mutableStateOf(false) }
    var pendingScrollRestore by remember { mutableStateOf(false) }
    var restoredFromCache by remember(groupId) { mutableStateOf(false) }

    // Save scroll position when leaving this group
    DisposableEffect(groupId) {
        onDispose {
            // Save current scroll position for this group
            val firstVisibleIndex = listState.firstVisibleItemIndex
            if (firstVisibleIndex < chatItems.size && chatItems.isNotEmpty()) {
                val key = getItemKey(chatItems[firstVisibleIndex])
                ScrollPositionCache.save(groupId, key, listState.firstVisibleItemScrollOffset)
            }
        }
    }

    // Restore scroll position from cache when entering a group
    LaunchedEffect(groupId, chatItems.size) {
        if (!restoredFromCache && chatItems.isNotEmpty()) {
            val cached = ScrollPositionCache.get(groupId)
            if (cached != null) {
                val index = chatItems.indexOfFirst { getItemKey(it) == cached.anchorKey }
                if (index >= 0) {
                    listState.scrollToItem(index, cached.offset)
                    hasInitiallyScrolled = true
                    restoredFromCache = true
                    previousItemCount = chatItems.size
                }
            }
        }
    }

    // Trigger load more when scrolling near top
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
                // Save anchor before triggering load
                val firstVisibleIndex = listState.firstVisibleItemIndex
                if (firstVisibleIndex < chatItems.size) {
                    anchorKey = getItemKey(chatItems[firstVisibleIndex])
                    anchorOffset = listState.firstVisibleItemScrollOffset
                    pendingScrollRestore = true
                }
                currentOnLoadMore()
            }
    }

    // Restore scroll position after items are added
    LaunchedEffect(chatItems.size) {
        if (chatItems.isEmpty()) {
            hasInitiallyScrolled = false
            previousItemCount = 0
            anchorKey = null
            pendingScrollRestore = false
            return@LaunchedEffect
        }

        // Initial scroll to bottom
        if (!hasInitiallyScrolled) {
            listState.scrollToItem(chatItems.lastIndex)
            hasInitiallyScrolled = true
            previousItemCount = chatItems.size
            return@LaunchedEffect
        }

        val itemsAdded = chatItems.size - previousItemCount

        if (itemsAdded > 0) {
            val savedKey = anchorKey
            if (savedKey != null && pendingScrollRestore) {
                // Find the anchor item's new index by its stable key
                val newIndex = chatItems.indexOfFirst { item -> getItemKey(item) == savedKey }
                if (newIndex >= 0) {
                    listState.scrollToItem(newIndex, anchorOffset)
                }
                anchorKey = null
                pendingScrollRestore = false
            } else {
                // New messages at bottom - auto-scroll if near bottom
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val wasNearBottom = lastVisibleIndex >= previousItemCount - 3
                if (wasNearBottom) {
                    listState.scrollToItem(chatItems.lastIndex)
                }
            }
        }

        previousItemCount = chatItems.size
    }

    when {
        isInitialLoading && chatItems.isEmpty() -> {
            // Show skeleton loaders during initial loading
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
            // Pull-to-refresh wrapper for mobile interactions
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = currentOnRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                // SelectionContainer enables website-like text selection across messages
                SelectionContainer {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            // Loading indicator at top for older messages
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
                                        isFirstInGroup = item.isFirstInGroup,
                                        isLastInGroup = item.isLastInGroup,
                                        onUsernameClick = currentOnUsernameClick
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
