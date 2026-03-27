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
 *
 * For process death survival, combine with rememberSaveable at the UI layer.
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
 * Handles saving, restoring, and preventing scroll jumps.
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
        // Key not found - message may have been deleted
        isRestorationPending = false
        isRestored = true
    }

    companion object {
        /**
         * Custom Saver for rememberSaveable support.
         * Saves groupId and position to survive configuration changes.
         */
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
                    // Try to get from cache if saveable didn't have it
                    ScrollPositionCache.get(groupId)
                }
                ScrollStateHolder(groupId, position)
            }
        )
    }
}

/**
 * Remember a ScrollStateHolder that survives recomposition and configuration changes.
 * Each groupId gets its own independent holder.
 */
@Composable
fun rememberScrollStateHolder(groupId: String): ScrollStateHolder {
    return rememberSaveable(
        inputs = arrayOf(groupId),
        saver = ScrollStateHolder.saver()
    ) {
        // Initialize from cache if available
        val cached = ScrollPositionCache.get(groupId)
        ScrollStateHolder(groupId, cached)
    }
}

/**
 * Manages scroll position saving and restoring for a LazyColumn.
 *
 * Usage:
 * ```
 * @Composable
 * fun MessagesList(groupId: String, items: List<Item>) {
 *     val listState = rememberLazyListState()
 *     val scrollStateHolder = rememberScrollStateHolder(groupId)
 *
 *     ScrollPositionEffect(
 *         groupId = groupId,
 *         listState = listState,
 *         items = items,
 *         stateHolder = scrollStateHolder,
 *         getItemKey = { item -> item.id }
 *     )
 *
 *     LazyColumn(state = listState) { ... }
 * }
 * ```
 */
@Composable
fun <T> ScrollPositionEffect(
    groupId: String,
    listState: LazyListState,
    items: List<T>,
    stateHolder: ScrollStateHolder,
    getItemKey: (T) -> String,
    initialScrollToEnd: Boolean = true // For chat: start at bottom (newest)
) {
    // Track if we've done initial scroll
    var hasInitiallyScrolled by remember(groupId) { mutableStateOf(false) }

    // Whether the user has manually scrolled away from the bottom
    var userScrolledAway by remember(groupId) { mutableStateOf(false) }

    // === INITIAL SCROLL + STICK-TO-BOTTOM ===
    LaunchedEffect(groupId, items.size) {
        if (items.isEmpty()) return@LaunchedEffect

        // For chat mode (initialScrollToEnd = true): ALWAYS go to the bottom on open.
        // Skip any saved position — restoring a mid-conversation position is confusing
        // because there are no unread markers to guide the user back.
        if (initialScrollToEnd) {
            if (!hasInitiallyScrolled) {
                // Wait until the LazyColumn has laid out at least one item before scrolling,
                // so the scroll target is calculated against real measured heights.
                snapshotFlow { listState.layoutInfo.totalItemsCount }
                    .first { it > 0 }
                listState.scrollToItem(items.lastIndex, Int.MAX_VALUE)
                stateHolder.markRestored()
                hasInitiallyScrolled = true
            }
            return@LaunchedEffect
        }

        // Case 1 (non-chat mode): Restore from saved position
        if (stateHolder.isRestorationPending) {
            val saved = stateHolder.savedPosition
            if (saved != null) {
                val index = items.indexOfFirst { getItemKey(it) == saved.anchorKey }
                if (index >= 0) {
                    listState.scrollToItem(index, saved.offset)
                    stateHolder.markRestored()
                    hasInitiallyScrolled = true
                } else {
                    if (hasInitiallyScrolled) stateHolder.markRestorationFailed()
                }
            }
            return@LaunchedEffect
        }

        // Case 2 (non-chat mode): Scroll to end if requested
        if (!hasInitiallyScrolled) {
            listState.scrollToItem(items.lastIndex, Int.MAX_VALUE)
            hasInitiallyScrolled = true
        }
    }

    // === STICK-TO-BOTTOM after content height grows (e.g. images loading) ===
    // When a visible item's image loads, total content height increases and
    // canScrollForward flips true even though the user didn't scroll away.
    // Re-anchor to the true bottom whenever that happens while near the end.
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow {
            listState.canScrollForward to listState.firstVisibleItemIndex
        }
            .distinctUntilChanged()
            .collect { (canScrollFwd, firstVisible) ->
                if (!hasInitiallyScrolled) return@collect
                val nearBottom = items.isNotEmpty() && firstVisible >= items.size - 5
                if (canScrollFwd && nearBottom && !userScrolledAway) {
                    listState.scrollToItem(items.lastIndex, Int.MAX_VALUE)
                }
            }
    }

    // Track when the user intentionally scrolls away from the bottom
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { firstVisible ->
                val nearBottom = items.isNotEmpty() && firstVisible >= items.size - 5
                if (hasInitiallyScrolled) {
                    userScrolledAway = !nearBottom
                }
            }
    }

    // === SAVE: Continuously track position while viewing ===
    LaunchedEffect(groupId, listState) {
        snapshotFlow {
            val firstVisible = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            Pair(firstVisible, offset)
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                // Only save valid positions
                if (index < items.size && items.isNotEmpty()) {
                    val key = getItemKey(items[index])
                    stateHolder.savePosition(key, offset)
                }
            }
    }

    // === CLEANUP: Save position when leaving group ===
    DisposableEffect(groupId) {
        onDispose {
            // Position is already being saved continuously,
            // but ensure final position is captured
            val index = listState.firstVisibleItemIndex
            if (index < items.size && items.isNotEmpty()) {
                val key = getItemKey(items[index])
                stateHolder.savePosition(key, listState.firstVisibleItemScrollOffset)
            }
        }
    }
}

/**
 * Handles auto-scroll behavior for new messages in a chat.
 * Only scrolls to bottom when a genuinely new message is APPENDED (not when
 * older messages are prepended during pagination).
 *
 * @param getItemKey stable key function — used to distinguish append vs. prepend
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

        // Only auto-scroll when the last item changed — that means a new message
        // was appended.  If the last item is the same (pagination prepended older
        // messages), do NOT scroll, so the user stays where they were reading.
        val newMessageAppended = getItemKey != null &&
            currentLastKey != null &&
            currentLastKey != previousLastKey &&
            previousSize > 0

        if (newMessageAppended) {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val wasNearBottom = lastVisibleIndex >= previousSize - nearBottomThreshold
            if (wasNearBottom) {
                listState.animateScrollToItem(items.lastIndex)
            }
        } else if (getItemKey == null) {
            // Fallback for callers that don't provide a key function (legacy)
            val newItemsCount = items.size - previousSize
            if (newItemsCount > 0 && previousSize > 0) {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val wasNearBottom = lastVisibleIndex >= previousSize - nearBottomThreshold
                if (wasNearBottom) {
                    listState.animateScrollToItem(items.lastIndex)
                }
            }
        }

        previousSize = items.size
        previousLastKey = currentLastKey
    }
}
