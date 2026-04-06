package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * Scroll position data using stable keys instead of indices.
 * Keys survive message list changes (prepending, removing), indices don't.
 */
data class ScrollPosition(
    val anchorKey: String,
    val offset: Int
)

/**
 * In-memory cache for scroll positions per group.
 * Survives navigation but not process death.
 */
object ScrollPositionCache {
    private val positions = mutableMapOf<String, ScrollPosition>()

    fun save(groupId: String, position: ScrollPosition) {
        positions[groupId] = position
    }

    fun get(groupId: String): ScrollPosition? = positions[groupId]

    fun remove(groupId: String) {
        positions.remove(groupId)
    }

    fun clear() {
        positions.clear()
    }
}

/**
 * State holder for scroll position management per group.
 */
@Stable
class ScrollStateHolder(
    val groupId: String,
    initialPosition: ScrollPosition? = null
) {
    var savedPosition by mutableStateOf(initialPosition)
        private set

    var isRestored by mutableStateOf(false)
        private set

    var isRestorationPending by mutableStateOf(initialPosition != null)
        private set

    fun savePosition(anchorKey: String, offset: Int) {
        val position = ScrollPosition(anchorKey, offset)
        savedPosition = position
        ScrollPositionCache.save(groupId, position)
    }

    fun markRestored() {
        isRestored = true
        isRestorationPending = false
    }

    fun markRestorationFailed() {
        isRestorationPending = false
        isRestored = true
    }

    companion object {
        fun saver(): Saver<ScrollStateHolder, List<Any?>> = Saver(
            save = { holder ->
                listOf(
                    holder.groupId,
                    holder.savedPosition?.anchorKey,
                    holder.savedPosition?.offset
                )
            },
            restore = { saved ->
                val groupId = saved[0] as String
                val anchorKey = saved[1] as? String
                val offset = saved[2] as? Int
                val position = if (anchorKey != null && offset != null) {
                    ScrollPosition(anchorKey, offset)
                } else {
                    ScrollPositionCache.get(groupId)
                }
                ScrollStateHolder(groupId, position)
            }
        )
    }
}

@Composable
fun rememberScrollStateHolder(groupId: String): ScrollStateHolder {
    return rememberSaveable(
        inputs = arrayOf(groupId),
        saver = ScrollStateHolder.saver()
    ) {
        val cached = ScrollPositionCache.get(groupId)
        ScrollStateHolder(groupId, cached)
    }
}

/**
 * Scroll-to-bottom on load, stick-to-bottom during initial delivery,
 * and continuous position save for group-switching restore.
 */
@Composable
fun <T> ScrollPositionEffect(
    groupId: String,
    listState: LazyListState,
    items: List<T>,
    stateHolder: ScrollStateHolder,
    getItemKey: (T) -> String,
    initialScrollToEnd: Boolean = true
) {
    val currentItems by rememberUpdatedState(items)
    var userScrolledAway by remember(groupId) { mutableStateOf(false) }

    // Scroll to bottom once items appear, keep scrolling while load delivers more.
    LaunchedEffect(groupId) {
        if (!initialScrollToEnd) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }

        val lastIdx = currentItems.lastIndex
        if (lastIdx >= 0) {
            listState.scrollToItem(lastIdx, Int.MAX_VALUE)
        }
        stateHolder.markRestored()

        snapshotFlow { currentItems.size to userScrolledAway }
            .distinctUntilChanged()
            .collect { (_, scrolledAway) ->
                if (!scrolledAway) {
                    val idx = currentItems.lastIndex
                    if (idx >= 0) {
                        listState.scrollToItem(idx, Int.MAX_VALUE)
                    }
                }
            }
    }

    // Track when user scrolls away from bottom.
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to currentItems.size }
            .distinctUntilChanged()
            .collect { (firstVisible, size) ->
                if (size > 0) {
                    val nearBottom = firstVisible >= size - 5
                    userScrolledAway = !nearBottom
                }
            }
    }

    // Stick-to-bottom when content height grows (e.g. images loading).
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow { listState.canScrollForward to listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { (canScrollFwd, firstVisible) ->
                val curItems = currentItems
                val nearBottom = curItems.isNotEmpty() && firstVisible >= curItems.size - 5
                if (canScrollFwd && nearBottom && !userScrolledAway) {
                    listState.scrollToItem(curItems.lastIndex, Int.MAX_VALUE)
                }
            }
    }

    // Continuously save position for group-switch restore.
    LaunchedEffect(groupId, listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                val curItems = currentItems
                if (index < curItems.size && curItems.isNotEmpty()) {
                    val key = getItemKey(curItems[index])
                    stateHolder.savePosition(key, offset)
                }
            }
    }

    // Save position when leaving group.
    DisposableEffect(groupId) {
        onDispose {
            val index = listState.firstVisibleItemIndex
            if (index < items.size && items.isNotEmpty()) {
                val key = getItemKey(items[index])
                stateHolder.savePosition(key, listState.firstVisibleItemScrollOffset)
            }
        }
    }
}

/**
 * Auto-scroll to new messages when user is near the bottom.
 */
@Composable
fun <T> AutoScrollEffect(
    listState: LazyListState,
    items: List<T>,
    getItemKey: ((T) -> String)? = null,
    enabled: Boolean = true,
    nearBottomThreshold: Int = 3
) {
    var previousLastKey by remember { mutableStateOf<String?>(null) }
    var previousSize by remember { mutableStateOf(items.size) }

    LaunchedEffect(items.size) {
        if (!enabled || items.isEmpty()) {
            previousSize = items.size
            previousLastKey = items.lastOrNull()?.let { getItemKey?.invoke(it) }
            return@LaunchedEffect
        }

        val currentLastKey = items.lastOrNull()?.let { getItemKey?.invoke(it) }

        // Only for appended messages, not pagination prepends.
        val newMessageAppended = getItemKey != null &&
            currentLastKey != null &&
            currentLastKey != previousLastKey &&
            previousSize > 0

        if (newMessageAppended) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val wasNearBottom = lastVisibleIndex >= previousSize - nearBottomThreshold
            if (wasNearBottom) {
                listState.scrollToItem(items.lastIndex)
            }
        }

        previousSize = items.size
        previousLastKey = currentLastKey
    }
}
