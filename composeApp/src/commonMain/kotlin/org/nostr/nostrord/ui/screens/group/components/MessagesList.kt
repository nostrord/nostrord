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
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
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

    // Use snapshotFlow for reliable scroll detection
    LaunchedEffect(listState, hasMoreMessages, isLoadingMore) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE
            val totalItems = layoutInfo.totalItemsCount
            // Check if we're near the top and should load more
            Triple(firstVisibleItem, totalItems, hasMoreMessages && !isLoadingMore && totalItems > 0)
        }
            .distinctUntilChanged()
            .filter { (firstVisible, _, canLoad) ->
                // Trigger when first visible item is in the first few items and we can load
                firstVisible <= 5 && canLoad
            }
            .collect {
                currentOnLoadMore()
            }
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
                            key = { index, item ->
                                when (item) {
                                    is ChatItem.DateSeparator -> "date_${index}_${item.date}"
                                    is ChatItem.NewMessagesDivider -> "new_messages_divider_$index"
                                    is ChatItem.SystemEvent -> "system_${item.id}"
                                    is ChatItem.Message -> "msg_${item.message.id}"
                                }
                            },
                            contentType = { _, item ->
                                // Content types for efficient item recycling
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

        // Track if this is initial load vs pagination
        var hasInitiallyScrolled by remember { mutableStateOf(false) }
        var previousSize by remember { mutableStateOf(0) }

        LaunchedEffect(chatItems.size) {
            if (chatItems.isEmpty()) {
                hasInitiallyScrolled = false
                previousSize = 0
                return@LaunchedEffect
            }

            // Initial scroll to bottom
            if (!hasInitiallyScrolled) {
                listState.scrollToItem(chatItems.lastIndex)
                hasInitiallyScrolled = true
                previousSize = chatItems.size
                return@LaunchedEffect
            }

            // Only auto-scroll if new messages arrived at the END (newer messages)
            // and user is already near the bottom. Don't scroll when loading
            // older messages via pagination (which adds to the beginning).
            val newMessages = chatItems.size - previousSize
            if (newMessages > 0) {
                val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isNearBottom = lastVisibleItem >= previousSize - 5

                // If user is near the bottom and new messages arrived, scroll to see them
                if (isNearBottom) {
                    listState.scrollToItem(chatItems.lastIndex)
                }
            }
            previousSize = chatItems.size
        }
        }
    }
}
