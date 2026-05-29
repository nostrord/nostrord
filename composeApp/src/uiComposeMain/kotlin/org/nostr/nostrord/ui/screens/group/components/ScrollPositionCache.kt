package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.nostr.nostrord.ui.scroll.ChatScrollPolicy
import org.nostr.nostrord.ui.scroll.ScrollEntryTarget

/**
 * Scroll position data using stable keys instead of indices.
 * Keys survive message list changes (prepending, removing), indices don't.
 */
data class ScrollPosition(
    val anchorKey: String,
    val offset: Int,
)

/**
 * In-memory cache for scroll positions per group.
 * Survives navigation but not process death.
 */
object ScrollPositionCache {
    private val positions = mutableMapOf<String, ScrollPosition>()

    fun save(
        groupId: String,
        position: ScrollPosition,
    ) {
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
    initialPosition: ScrollPosition? = null,
) {
    var savedPosition by mutableStateOf(initialPosition)
        private set

    var isRestored by mutableStateOf(false)
        private set

    var isRestorationPending by mutableStateOf(initialPosition != null)
        private set

    // Latch state for the bottom-pin / divider-entry behaviour. The transition
    // RULES live in commonMain (ChatScrollPolicy) so they are unit-tested and shared
    // with web; this holder only stores the state in Compose-observable form (so the
    // FAB recomposes) and applies the policy's transitions.
    private var scroll by mutableStateOf(ChatScrollPolicy.onEnterGroup())

    /** True while the viewport is pinned to the bottom (single latch authority). */
    val atBottom: Boolean get() = scroll.atBottom

    /** Latched once the entry alignment to the "New messages" divider has run. */
    val openedAtDivider: Boolean get() = scroll.openedAtDivider

    /** Jump-to-bottom FAB visibility. */
    val isScrolledAway: Boolean get() = ChatScrollPolicy.isScrolledAway(scroll)

    /** Apply the one-shot entry alignment decision; returns the target to scroll to. */
    fun applyEntryChange(
        hasDivider: Boolean,
        isSeeking: Boolean,
    ): ScrollEntryTarget? {
        val decision = ChatScrollPolicy.onItemsChanged(scroll, hasDivider, isSeeking)
        scroll = decision.state
        return decision.target
    }

    /** Feed a user-scroll "is the last item visible" reading into the latch. */
    fun applyBottomReading(reachedBottom: Boolean) {
        scroll = ChatScrollPolicy.onBottomReadingChanged(scroll, reachedBottom)
    }

    fun savePosition(
        anchorKey: String,
        offset: Int,
    ) {
        val position = ScrollPosition(anchorKey, offset)
        savedPosition = position
        ScrollPositionCache.save(groupId, position)
    }

    fun markRestored() {
        isRestored = true
        isRestorationPending = false
        scroll = ChatScrollPolicy.onItemsReady(scroll)
    }

    fun markRestorationFailed() {
        isRestorationPending = false
        isRestored = true
        scroll = ChatScrollPolicy.onItemsReady(scroll)
    }

    companion object {
        fun saver(): Saver<ScrollStateHolder, List<Any?>> = Saver(
            save = { holder ->
                listOf(
                    holder.groupId,
                    holder.savedPosition?.anchorKey,
                    holder.savedPosition?.offset,
                )
            },
            restore = { saved ->
                val groupId = saved[0] as String
                val anchorKey = saved[1] as? String
                val offset = saved[2] as? Int
                val position =
                    if (anchorKey != null && offset != null) {
                        ScrollPosition(anchorKey, offset)
                    } else {
                        ScrollPositionCache.get(groupId)
                    }
                ScrollStateHolder(groupId, position)
            },
        )
    }
}

@Composable
fun rememberScrollStateHolder(groupId: String): ScrollStateHolder = rememberSaveable(
    inputs = arrayOf(groupId),
    saver = ScrollStateHolder.saver(),
) {
    val cached = ScrollPositionCache.get(groupId)
    ScrollStateHolder(groupId, cached)
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
    initialScrollToEnd: Boolean = true,
) {
    val currentItems by rememberUpdatedState(items)

    // Resolve the restoration gate once items first appear. The authoritative entry
    // positioning (divider vs bottom) is decided by the entry effect in
    // MessagesList; here we only flip isRestored so the user-scroll tracker and the
    // auto-pin start applying. Default latch (atBottom = true) means a group with no
    // unread divider simply opens at the bottom via the pin effect below.
    //
    // Keyed on listState (not just groupId): on a cold load the list starts empty so
    // MessagesList builds a throwaway LazyListState, then swaps in a new one anchored
    // at the last item once messages arrive. If this effect stayed keyed on groupId
    // it would keep awaiting totalItemsCount > 0 on the discarded (detached) state
    // forever, so markRestored never fires, the tracker's readings are dropped, and
    // the jump-to-bottom FAB never appears until the group is re-entered.
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { it > 0 }
        stateHolder.markRestored()
    }

    // SINGLE bottom authority. Pins to the newest item whenever the latch says we
    // are at the bottom. Re-runs on list growth AND height growth (canScrollForward
    // flips as media resolves), so it subsumes the three former pinners. Gated
    // purely by stateHolder.atBottom — never derived from layout here — so the entry
    // divider alignment (which sets atBottom = false) is never overridden by a
    // streaming chunk. This is the fix for the "jumps to bottom / have to re-enter"
    // jank: there is now exactly one thing that scrolls to the bottom.
    //
    // Keyed on listState too: it must rebind to the live LazyListState after the
    // cold-load swap described above, otherwise stick-to-bottom would drive the
    // discarded state and never move the visible list.
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow { currentItems.size to listState.canScrollForward }
            .distinctUntilChanged()
            .collect {
                if (stateHolder.atBottom) {
                    val idx = currentItems.lastIndex
                    if (idx >= 0) listState.scrollToItem(idx, Int.MAX_VALUE)
                }
            }
    }

    // Promote / demote the atBottom latch from REAL user scrolling, and only after
    // entry positioning has resolved (isRestored). Leaving the bottom sets the latch
    // false; returning to the bottom sets it true again — but only once we have
    // actually seen a scroll-away this session (sawNotBottom), so a divider entry
    // whose target happens to sit within the bottom tolerance is not spuriously
    // promoted back to bottom (web's `wasNotAtBottom` round-trip gate). The pin
    // effect reads the latch, not the layout, so this can never race it.
    LaunchedEffect(groupId, listState) {
        if (!initialScrollToEnd) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = layoutInfo.totalItemsCount
            val reachedBottom = lastVisible >= 0 && total > 0 && lastVisible >= total - 2
            // Pair with isRestored so the latch re-evaluates the CURRENT position the
            // moment restoration completes. Without it, a user who scrolls up during
            // the initial load (before isRestored) has that reading dropped by the
            // policy's restored-gate, and distinctUntilChanged then never re-delivers
            // the unchanged "not at bottom" value — leaving atBottom stuck true and
            // the jump-to-bottom FAB hidden until the group is re-entered.
            stateHolder.isRestored to reachedBottom
        }.distinctUntilChanged()
            .collect { (_, reachedBottom) ->
                stateHolder.applyBottomReading(reachedBottom)
            }
    }

    // Continuously save position for group-switch restore.
    LaunchedEffect(groupId, listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.distinctUntilChanged()
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
 * Always scrolls when the appended item is from the current user (their own send).
 */
@Composable
fun <T> AutoScrollEffect(
    listState: LazyListState,
    items: List<T>,
    getItemKey: ((T) -> String)? = null,
    enabled: Boolean = true,
    nearBottomThreshold: Int = 3,
    isFromCurrentUser: ((T) -> Boolean)? = null,
) {
    var previousLastKey by remember { mutableStateOf<String?>(null) }
    var previousSize by remember { mutableStateOf(items.size) }

    LaunchedEffect(items.size) {
        if (!enabled || items.isEmpty()) {
            previousSize = items.size
            previousLastKey = items.lastOrNull()?.let { getItemKey?.invoke(it) }
            return@LaunchedEffect
        }

        val lastItem = items.last()
        val currentLastKey = getItemKey?.invoke(lastItem)

        // Only for appended messages, not pagination prepends.
        val newMessageAppended =
            getItemKey != null &&
                currentLastKey != null &&
                currentLastKey != previousLastKey &&
                previousSize > 0

        if (newMessageAppended) {
            val ownAppend = isFromCurrentUser?.invoke(lastItem) == true
            val lastVisibleIndex =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            val wasNearBottom = lastVisibleIndex >= previousSize - nearBottomThreshold
            if (ownAppend || wasNearBottom) {
                listState.scrollToItem(items.lastIndex)
            }
        }

        previousSize = items.size
        previousLastKey = currentLastKey
    }
}
