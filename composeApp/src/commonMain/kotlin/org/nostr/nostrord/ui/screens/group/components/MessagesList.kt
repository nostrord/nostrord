package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.nostr.nostrord.ui.components.chat.SystemEventItem
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun MessagesList(
    chatItems: List<ChatItem>,
    userMetadata: Map<String, UserMetadata>,
    isJoined: Boolean,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {}
) {
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
                onLoadMore()
            }
    }

    if (chatItems.isEmpty()) {
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
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // Loading indicator at top for older messages
                if (isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = NostrordColors.Primary,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                items(chatItems, key = { item ->
                    when (item) {
                        is ChatItem.DateSeparator -> "date_${item.date}"
                        is ChatItem.SystemEvent -> "system_${item.id}"
                        is ChatItem.Message -> "msg_${item.message.id}"
                    }
                }) { item ->
                    when (item) {
                        is ChatItem.DateSeparator -> DateSeparator(item.date)
                        is ChatItem.SystemEvent -> SystemEventItem(
                            pubkey = item.pubkey,
                            action = item.action,
                            createdAt = item.createdAt,
                            metadata = userMetadata[item.pubkey]
                        )
                        is ChatItem.Message -> MessageItem(
                            message = item.message,
                            metadata = userMetadata[item.message.pubkey],
                            isFirstInGroup = item.isFirstInGroup,
                            isLastInGroup = item.isLastInGroup
                        )
                    }
                }
            }

            VerticalScrollbarWrapper(
                listState = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
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
