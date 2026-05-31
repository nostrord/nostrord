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
 * Non-virtualized chat list: renders every row as real DOM (history is bounded).
 * Pins to the bottom while following the feed; while reading history it relies on
 * the browser's overflow-anchor to hold the view across prepends and late media
 * layout, so older pages load without the scroll position jumping.
 */
val ChatMessageList =
    FC<ChatMessageListProps> { props ->
        val el = useRef<HTMLDivElement>(null)
        val innerEl = useRef<HTMLDivElement>(null)
        val items = props.items
        val loadingOlder = useRef(false)
        val firedSize = useRef(0)
        val atBottom = useRef(true)
        val openedFor = useRef<String>(null)
        val wasLoading = useRef(false)
        val markDebounce = useRef<Int>(null)

        // Following the feed: pin to bottom, scroll-anchoring OFF. Reading history:
        // anchoring ON and never touch scrollTop, so the browser holds position across
        // prepends and late image/avatar layout (a manual scrollTop delta could not).
        useLayoutEffect(items.size, props.resetKey) {
            val node = el.current ?: return@useLayoutEffect

            if (openedFor.current != props.resetKey) {
                // First render of this group: open at the bottom, anchoring off.
                node.asDynamic().style.overflowAnchor = "none"
                node.scrollTop = node.scrollHeight.toDouble()
                openedFor.current = props.resetKey
                loadingOlder.current = false
                if (atBottom.current != true) {
                    atBottom.current = true
                    props.onAtBottomChange(true)
                }
                return@useLayoutEffect
            }

            if (atBottom.current == true) {
                // Following: stay pinned to the bottom as content streams in. Anchor OFF
                // so it doesn't re-anchor to a row above and drift us up.
                node.asDynamic().style.overflowAnchor = "none"
                node.scrollTop = node.scrollHeight.toDouble()
            } else {
                // Reading history: let the browser anchor the visible content. Do NOT
                // touch scrollTop — overflow-anchor holds the position across the prepend
                // and across late image/avatar layout, so there is no jump.
                node.asDynamic().style.overflowAnchor = "auto"
                // Release the latch the instant the page rendered (items grew past the
                // fire-time count) so continued scrolling loads the next page without
                // waiting out the settle timer. isLoadingMore still guards a double fire.
                if (loadingOlder.current == true && items.size > (firedSize.current ?: 0)) {
                    loadingOlder.current = false
                }
            }
        }

        // Fallback latch release: the layout effect frees the latch as soon as the page
        // renders; this only covers a page that returns nothing new (no growth), so the
        // latch can't get stuck. Pagination just trusts hasMore/isLoadingMore (like native).
        useEffect(props.isLoadingMore, items.size) {
            if (props.isLoadingMore) {
                wasLoading.current = true
                return@useEffect
            }
            if (wasLoading.current != true) return@useEffect
            delay(400)
            wasLoading.current = false
            if (loadingOlder.current == true) {
                loadingOlder.current = false
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
            // At the very top (scrollTop 0) the browser fires no scroll event for further
            // wheel-up, so onScroll-based pagination would stall there. The wheel event
            // still fires, so it loads the next page; the loadingOlder latch keeps it to
            // one page per load cycle, and stopping the wheel stops loading (no burst).
            onWheel = { ev ->
                if ((ev.deltaY as Double) < 0.0) {
                    val node = ev.currentTarget
                    val sh = node.scrollHeight.toDouble()
                    val st = node.scrollTop.toDouble()
                    val ch = node.clientHeight.toDouble()
                    val mayPaginate = atBottom.current != true || sh <= ch + 4.0
                    if (st < ch * 2.5 &&
                        mayPaginate &&
                        props.hasMore &&
                        !props.isLoadingMore &&
                        loadingOlder.current != true
                    ) {
                        loadingOlder.current = true
                        firedSize.current = items.size
                        props.onStartReached()
                    }
                }
            }
            onScroll = { ev ->
                val node = ev.currentTarget
                val sh = node.scrollHeight.toDouble()
                val st = node.scrollTop.toDouble()
                val ch = node.clientHeight.toDouble()
                val isAtBottom = (sh - st - ch) < 48.0
                if (atBottom.current != isAtBottom) {
                    atBottom.current = isAtBottom
                    props.onAtBottomChange(isAtBottom)
                    // Anchoring ON while reading history (holds position across prepends and
                    // late image layout), OFF at the bottom where it would fight the pin.
                    node.asDynamic().style.overflowAnchor = if (isAtBottom) "none" else "auto"
                }
                // Load older history as the user nears the top (prefetch ~2.5 viewports
                // ahead so the page lands before they reach the top). Don't paginate while
                // at the bottom, except when the feed doesn't fill the viewport (sh <= ch)
                // and the user can't scroll up to ask for more.
                val mayPaginate = atBottom.current != true || sh <= ch + 4.0
                if (st < ch * 2.5 &&
                    mayPaginate &&
                    props.hasMore &&
                    !props.isLoadingMore &&
                    loadingOlder.current != true
                ) {
                    loadingOlder.current = true
                    firedSize.current = items.size
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
