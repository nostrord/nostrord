package org.nostr.nostrord.web.components

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffect
import react.useLayoutEffect
import react.useRef
import web.cssom.ClassName
import web.html.HTMLDivElement

external interface ChatMessageListProps : Props {
    /** Opaque rows; index 0 = oldest/top, last = newest/bottom. */
    var items: Array<dynamic>

    /** Render one row directly into the list (the row element carries its own key). */
    var renderRow: (ChildrenBuilder, dynamic) -> Unit

    /** Stable identity per row — used to detect prepend (first row changed) vs a new
     *  message (last row changed), so pagination never gets mistaken for a new arrival. */
    var keyOf: (dynamic) -> String

    /** Changing this (the group id) re-opens the list at the bottom. */
    var resetKey: String

    var hasMore: Boolean
    var isLoadingMore: Boolean

    /** Oldest loaded message timestamp. Pagination stops once this stops decreasing
     *  (no genuinely older history) so pinning at the top can't load forever. */
    var oldestTs: Double

    /** Fired when the user nears the top — caller loads older history (no guards needed). */
    var onStartReached: () -> Unit

    /** Fired on at-bottom transitions — caller drives the FAB / mark-read. */
    var onAtBottomChange: (Boolean) -> Unit

    /** Fired (debounced) with the index of the bottom-most fully-visible row — mark-as-read. */
    var onRangeChange: (Int) -> Unit

    /** A DOM id to scroll into view (deep-link / reply); cleared via [onScrolledToKey]. */
    var scrollToKey: String?
    var onScrolledToKey: () -> Unit

    /** Bump to scroll to the newest row (the FAB). */
    var jumpNonce: Int
}

/**
 * Non-virtualized chat list. After fighting two virtualizers (react-virtuoso's
 * firstItemIndex never positioned reliably here: blank on mount, opened mid-list,
 * lurched to the bottom on scroll-up), this renders EVERY row as real DOM. That
 * removes the entire class of virtualization bugs (blank viewport, wrong mount
 * position, firstItemIndex) and leaves exactly one thing to manage: keep the
 * reading position when older history is prepended. On a plain list that is a
 * deterministic scrollHeight delta — content added above the viewport grows
 * scrollHeight by exactly that amount, so adding the delta to scrollTop holds the
 * view perfectly still. Chat history is bounded (hundreds–thousands of rows), so
 * full DOM rendering is fine.
 */
val ChatMessageList =
    FC<ChatMessageListProps> { props ->
        val el = useRef<HTMLDivElement>(null)
        val innerEl = useRef<HTMLDivElement>(null)
        val items = props.items
        val loadingOlder = useRef(false)
        val prevHeight = useRef(-1.0)
        val prevFirstKey = useRef<String>(null)
        val atBottom = useRef(true)
        val openedFor = useRef<String>(null)
        val firedAtOldestTs = useRef(0.0)
        val noGrowthStreak = useRef(0)
        val stalled = useRef(false)
        val wasLoading = useRef(false)
        val markDebounce = useRef<Int>(null)
        // Always-current oldest timestamp (read by the delayed settle effect, whose
        // captured props are stale after the delay).
        val latestOldestTs = useRef(0.0)
        latestOldestTs.current = props.oldestTs

        // Open at the bottom on first render of a group, then either hold position
        // across a prepend (loadingOlder) or pin to the bottom if the user is there.
        // Pre-paint so corrections are never visible.
        useLayoutEffect(items.size, props.resetKey) {
            val node = el.current ?: return@useLayoutEffect
            val firstKey = if (items.isNotEmpty()) props.keyOf(items[0]) else null

            if (openedFor.current != props.resetKey) {
                // First render of this group: open at the bottom. Anchor OFF — while
                // pinning to the bottom the browser's scroll-anchoring would re-anchor
                // to a row above and drift us off the bottom as avatars/images resolve.
                node.asDynamic().style.overflowAnchor = "none"
                node.scrollTop = node.scrollHeight.toDouble()
                openedFor.current = props.resetKey
                prevHeight.current = node.scrollHeight.toDouble()
                prevFirstKey.current = firstKey
                loadingOlder.current = false
                stalled.current = false
                noGrowthStreak.current = 0
                if (atBottom.current != true) {
                    atBottom.current = true
                    props.onAtBottomChange(true)
                }
                return@useLayoutEffect
            }

            val frontGrew = prevFirstKey.current != null && firstKey != null && firstKey != prevFirstKey.current

            if (atBottom.current == true) {
                // At the bottom (just opened / following the feed): always stay at the
                // bottom as content streams in. Anchor OFF so scroll-anchoring doesn't
                // fight the pin and drift us up.
                node.asDynamic().style.overflowAnchor = "none"
                node.scrollTop = node.scrollHeight.toDouble()
            } else if (frontGrew) {
                // Reading history: older content was prepended above the viewport, which
                // grew scrollHeight by exactly that amount; add the delta to scrollTop
                // so the visible rows stay put.
                val delta = node.scrollHeight.toDouble() - (prevHeight.current ?: node.scrollHeight.toDouble())
                if (delta != 0.0) node.scrollTop = node.scrollTop.toDouble() + delta
            }
            prevHeight.current = node.scrollHeight.toDouble()
            prevFirstKey.current = firstKey
        }

        // Release the pagination latch once the streaming page settles; decide stall.
        useEffect(props.isLoadingMore, items.size) {
            if (props.isLoadingMore) {
                wasLoading.current = true
                return@useEffect
            }
            if (wasLoading.current != true) return@useEffect
            delay(400)
            wasLoading.current = false
            if (loadingOlder.current == true) {
                // Progress = the OLDEST message actually got older (smaller timestamp).
                // Size growth is the wrong signal: a re-serving relay (or mux overlap)
                // grows the count by filling the middle while the oldest stays put,
                // which left the user pinned at the top paginating forever. If the
                // oldest didn't advance, count it as no-progress and stop after a few.
                val firedTs = firedAtOldestTs.current ?: 0.0
                val nowTs = latestOldestTs.current ?: 0.0
                val advanced = firedTs <= 0.0 || (nowTs in 1.0..(firedTs - 1.0))
                loadingOlder.current = false
                prevHeight.current = -1.0
                firedAtOldestTs.current = 0.0
                // Restore scroll-anchoring to the parked-reading default — but only
                // 'auto' if the user is still reading history; if they're back at the
                // bottom, keep it off so the pin isn't fought.
                el.current?.let { it.asDynamic().style.overflowAnchor = if (atBottom.current == true) "none" else "auto" }
                // oldest-not-advancing is a STRONG signal (with until=oldest, real
                // older history would have come back), so stop after a single such
                // round — no need to tolerate several like the old size-based check.
                if (advanced) {
                    noGrowthStreak.current = 0
                    stalled.current = false
                } else {
                    stalled.current = true
                }
            }
        }

        // Stay glued to the bottom when content grows there WITHOUT an items.size
        // change — an image/video/reply-preview resolving height, a reaction landing
        // on the newest message (issue #74). Those don't trip the layout effect, so a
        // ResizeObserver on the inner content re-pins to the bottom. Guarded to ONLY
        // act while following (at bottom, not paginating); when reading history,
        // overflow-anchor handles above-viewport growth instead.
        useEffect(Unit) {
            val inner = innerEl.current ?: return@useEffect
            val onResize: () -> Unit = {
                if (atBottom.current == true && loadingOlder.current != true) {
                    el.current?.let { it.scrollTop = it.scrollHeight.toDouble() }
                }
            }
            val factory =
                js(
                    "(function(node, cb){ var ro = new ResizeObserver(function(){ cb(); }); ro.observe(node); return function(){ ro.disconnect(); }; })",
                )
            val disconnect = factory(inner, onResize)
            try {
                awaitCancellation()
            } finally {
                disconnect()
            }
        }

        // Deep-link / reply jump: scroll the row with the given DOM id into view.
        useEffect(props.scrollToKey, items.size) {
            val key = props.scrollToKey ?: return@useEffect
            val target = document.getElementById(key) ?: return@useEffect
            target.asDynamic().scrollIntoView(js("({ behavior: 'auto', block: 'center' })"))
            props.onScrolledToKey()
        }

        // Jump-to-bottom (FAB).
        useEffect(props.jumpNonce) {
            if ((props.jumpNonce ?: 0) <= 0) return@useEffect
            val node = el.current ?: return@useEffect
            node.asDynamic().scrollTo(js("({ top: node.scrollHeight, behavior: 'smooth' })"))
        }

        div {
            className = ClassName("chat-messages-list")
            ref = el
            onScroll = { ev ->
                val node = ev.currentTarget
                val sh = node.scrollHeight.toDouble()
                val st = node.scrollTop.toDouble()
                val ch = node.clientHeight.toDouble()
                val isAtBottom = (sh - st - ch) < 48.0
                if (atBottom.current != isAtBottom) {
                    atBottom.current = isAtBottom
                    props.onAtBottomChange(isAtBottom)
                    // Scroll-anchoring is wanted ONLY while parked reading history (so
                    // a reaction/media above the viewport doesn't jump the view). At the
                    // bottom it fights the pin; while paginating the JS restore owns it.
                    if (loadingOlder.current != true) {
                        node.asDynamic().style.overflowAnchor = if (isAtBottom) "none" else "auto"
                    }
                }
                // Load older history as the user nears the top. The scrollHeight
                // captured here is restored by the layout effect after the prepend,
                // so the view never jumps; once a page lands, scrollTop has grown
                // past the trigger, so it won't re-fire until the user scrolls up.
                //
                // Do NOT paginate while at the bottom: on a short group the bottom is
                // already within ch*1.5 of the top, so without this guard it would
                // paginate immediately on open and the streaming page would drift the
                // user up off the newest. The exception is a feed that doesn't fill the
                // viewport (sh <= ch) — then the user can't scroll up to ask for more,
                // so allow a cold-start fill.
                val mayPaginate = atBottom.current != true || sh <= ch + 4.0
                if (st < ch * 1.5 &&
                    mayPaginate &&
                    props.hasMore &&
                    !props.isLoadingMore &&
                    loadingOlder.current != true &&
                    stalled.current != true
                ) {
                    loadingOlder.current = true
                    prevHeight.current = sh
                    firedAtOldestTs.current = props.oldestTs
                    // Disable browser scroll-anchoring for the prepend so it doesn't
                    // double-adjust against our scrollHeight-delta restore; re-enabled
                    // at settle so reactions/media/replies above the viewport stay
                    // anchored normally.
                    node.asDynamic().style.overflowAnchor = "none"
                    props.onStartReached()
                }
                // Mark-as-read: bottom-most fully-visible row (debounced).
                markDebounce.current?.let { window.clearTimeout(it) }
                markDebounce.current =
                    window.setTimeout(
                        {
                            val containerBottom = (node.getBoundingClientRect().bottom as Number).toDouble()
                            val kids = (innerEl.current?.asDynamic()?.children) ?: node.asDynamic().children
                            val n = kids.length as Int
                            var lastVisible = -1
                            for (i in 0 until n) {
                                val kb = (kids[i].getBoundingClientRect().bottom as Number).toDouble()
                                if (kb <= containerBottom + 1.0) lastVisible = i
                            }
                            if (lastVisible >= 0) props.onRangeChange(lastVisible)
                        },
                        400,
                    )
            }
            div {
                ref = innerEl
                className = ClassName("chat-messages-inner")
                items.forEach { item -> props.renderRow(this, item) }
            }
        }
    }
