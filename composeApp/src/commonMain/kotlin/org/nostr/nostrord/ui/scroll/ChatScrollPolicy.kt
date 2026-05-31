package org.nostr.nostrord.ui.scroll

/**
 * Platform-agnostic decision machine for chat-feed scroll behaviour, shared by the
 * native (Compose LazyColumn) and web (React/DOM) chat views.
 *
 * It holds ONLY the latch state and the transition rules; it performs no actual
 * scrolling. Callers translate the returned decisions into platform calls
 * (`LazyListState.scrollToItem` on native, `el.scrollTop` / `scrollIntoView` on
 * web). Keeping the rules here means the "open at divider vs bottom", "pin to
 * bottom", and "round-trip before re-pinning" policy is defined once and unit
 * tested in `commonTest`, instead of being re-derived per platform.
 *
 * State is immutable; every transition returns a fresh [ChatScrollState]. The
 * native holder wraps it in Compose `mutableStateOf` so the FAB recomposes.
 */
data class ChatScrollState(
    /**
     * Single source of truth for "is the viewport pinned to the bottom". A LATCH,
     * not derived continuously from layout: entry / divider alignment set it, and
     * only a real user scroll back to the bottom flips it true again.
     */
    val atBottom: Boolean = true,
    /** Latched once the one-shot entry alignment to the divider has run. */
    val openedAtDivider: Boolean = false,
    /** Items have appeared at least once; entry positioning is resolved. */
    val restored: Boolean = false,
    /**
     * Whether a genuine scroll-away from the bottom has been seen this entry. Gates
     * the promotion back to [atBottom] so a divider entry whose target sits within
     * the bottom tolerance is not spuriously re-pinned (the web's `wasNotAtBottom`).
     */
    val sawNotBottom: Boolean = false,
)

/** Where the chat should position itself on group entry. */
enum class ScrollEntryTarget { Bottom, Divider }

/** Result of an entry-alignment decision: the next state plus the action to take. */
data class EntryDecision(
    val state: ChatScrollState,
    val target: ScrollEntryTarget?,
)

object ChatScrollPolicy {
    /** Fresh latch state for a group entry. */
    fun onEnterGroup(): ChatScrollState = ChatScrollState()

    /** Items first appeared; open the gate so user-scroll tracking starts applying. */
    fun onItemsReady(state: ChatScrollState): ChatScrollState = state.copy(restored = true)

    /**
     * One-shot entry alignment. Returns [ScrollEntryTarget.Divider] exactly once,
     * when a divider is present, we are not seeking a deep-link target, and we have
     * not already aligned. On Divider it latches [ChatScrollState.openedAtDivider]
     * and clears [ChatScrollState.atBottom] so later streaming chunks don't yank the
     * view to the bottom. Returns a null target (and unchanged state) otherwise; the
     * default `atBottom = true` then lets the pin decision open the group at bottom.
     */
    fun onItemsChanged(
        state: ChatScrollState,
        hasDivider: Boolean,
        isSeeking: Boolean,
    ): EntryDecision {
        if (state.openedAtDivider || isSeeking || !hasDivider) return EntryDecision(state, null)
        return EntryDecision(
            state.copy(openedAtDivider = true, atBottom = false),
            ScrollEntryTarget.Divider,
        )
    }

    /**
     * Should the view pin to the newest item right now? True only while the latch
     * says we are at the bottom and we are not seeking a deep-link target.
     */
    fun shouldPinToBottom(
        state: ChatScrollState,
        isSeeking: Boolean,
    ): Boolean = state.atBottom && !isSeeking

    /**
     * Feed a layout-derived "is the last item visible" reading from real user
     * scrolling. Leaving the bottom clears [ChatScrollState.atBottom]; returning
     * re-pins only once a genuine scroll-away has been seen this entry. A no-op until
     * [ChatScrollState.restored], so entry positioning is never clobbered.
     */
    fun onBottomReadingChanged(
        state: ChatScrollState,
        reachedBottom: Boolean,
    ): ChatScrollState {
        if (!state.restored) return state
        return when {
            !reachedBottom -> state.copy(sawNotBottom = true, atBottom = false)
            state.sawNotBottom -> state.copy(atBottom = true)
            else -> state
        }
    }

    /** Jump-to-bottom affordance (FAB) visibility. */
    fun isScrolledAway(state: ChatScrollState): Boolean = !state.atBottom
}
