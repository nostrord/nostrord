package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.utils.ChatSearch

/**
 * Everything the chat screen needs to wire in-chat search: the bits passed to MessagesList
 * ([active], [currentHitId], [scrollNonce], [hitIds]), the header toggle ([onToggle]), and the
 * search [bar] overlay composable. Produced by [rememberChatSearchState] so the desktop and mobile
 * screens share one definition of the matcher, cursor, prefetch, and "search older" dig instead of
 * keeping two copies in sync.
 */
@Stable
class ChatSearchState(
    val active: Boolean,
    val currentHitId: String?,
    val scrollNonce: Int,
    val hitIds: Set<String>,
    val onToggle: () -> Unit,
    val bar: @Composable () -> Unit,
)

/**
 * Drives in-chat search for one group. UI-local state (not in the ViewModel); resets per [groupId].
 * matchIds recompute only when the message list or query changes; the cursor walks them with
 * wraparound, anchored by message id so "search older" prepends don't shift the selection.
 */
@Composable
fun rememberChatSearchState(
    groupId: String,
    messages: List<NostrGroupClient.NostrMessage>,
    userMetadata: Map<String, UserMetadata>,
    hasMoreMessages: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
): ChatSearchState {
    var searchActive by remember(groupId) { mutableStateOf(false) }
    var searchQuery by remember(groupId) { mutableStateOf("") }
    // The cursor is anchored to a message id, not a position. "Search older messages" prepends older
    // matches to searchMatchIds (oldest-first order), which would shift a positional index onto a
    // different message; tracking by id keeps the selection on the row the user picked.
    var anchoredHitId by remember(groupId) { mutableStateOf<String?>(null) }
    // Bumped on every prev/next press so re-pressing scrolls back to the current match even when the
    // anchored id does not change (e.g. a single match, or re-centering after scrolling away).
    var searchScrollNonce by remember(groupId) { mutableStateOf(0) }
    // True while paginating older history on demand looking for a match.
    var searchingOlder by remember(groupId) { mutableStateOf(false) }
    // Match count captured when a "search older" dig starts, so the loop can tell when a new older
    // match has appeared and stop there.
    var searchOlderBaseline by remember(groupId) { mutableStateOf(0) }
    // Pages loaded in the current dig. Bounded so one click never drains all history (which would
    // flip hasMore to false and hide the affordance for good); the user can just click again.
    var searchOlderPages by remember(groupId) { mutableStateOf(0) }

    // searchTextById enriches each message with what the user SEES: mentions resolved to @names and
    // nevent/note quotes resolved to the referenced note's text (in-memory only). It is the expensive
    // step, so it recomputes only on messages / metadata / cache changes while search is open; the
    // cheap per-keystroke match below reuses it via the extractor.
    val cachedEventsForSearch by AppModule.nostrRepository.cachedEvents.collectAsState()
    val searchTextById = remember(messages, userMetadata, cachedEventsForSearch, searchActive) {
        if (!searchActive) {
            emptyMap()
        } else {
            ChatSearch.buildSearchTextById(
                messages,
                resolveMention = { pubkey -> userMetadata[pubkey]?.let { it.displayName ?: it.name }?.takeIf(String::isNotBlank) },
                resolveCachedQuote = { id -> cachedEventsForSearch[id]?.content },
            )
        }
    }
    val searchMatchIds = remember(searchTextById, searchQuery) {
        ChatSearch.matchingIds(messages, searchQuery) { searchTextById[it.id] ?: it.content }
    }
    // Cursor: anchored hit position, current id, and inverted 1-based display number (1 = newest).
    val searchCursor = ChatSearch.cursor(searchMatchIds, anchoredHitId)
    val clampedIndex = searchCursor.index
    val currentSearchHitId = searchCursor.currentId
    val searchHitIdSet = remember(searchMatchIds) { searchMatchIds.toSet() }

    // Lock the anchor onto the resolved hit (the first match for a new query) so later list changes
    // keep the cursor by identity. No-op once they agree, so this can't loop.
    LaunchedEffect(currentSearchHitId) {
        if (currentSearchHitId != null && currentSearchHitId != anchoredHitId) anchoredHitId = currentSearchHitId
    }

    val closeSearch = {
        searchActive = false
        searchQuery = ""
        anchoredHitId = null
        searchingOlder = false
    }

    // Prefetch quoted events referenced in loaded messages so their text is searchable even for
    // messages not yet scrolled into view (a quote is otherwise cached only once its message renders;
    // web matched the welcome-inside-a-nevent only because that message happened to be on screen).
    LaunchedEffect(searchActive, messages) {
        if (!searchActive) return@LaunchedEffect
        val cached = AppModule.nostrRepository.cachedEvents.value
        for (ref in ChatSearch.quotedEventRefs(messages)) {
            if (ref.eventId !in cached) AppModule.nostrRepository.requestEventById(ref.eventId, ref.relays, ref.author)
        }
    }

    // On-demand "search older messages": page back through history ONLY until the next older match
    // appears, then stop and jump to it. Detection is by the ANCHOR's index: older matches prepend
    // at the front, pushing the anchor right, so `cursor.index > baseline` means one was found —
    // whereas a new live match appends at the TAIL (growing the count without moving the anchor),
    // which must NOT end the dig. No viewport pinning while it digs: the list's own pagination
    // scroll-restore (MessagesList) holds position on prepend, so unlike web we must NOT force a
    // scroll each page (that fights the restore and makes the feed bounce).
    LaunchedEffect(searchingOlder, hasMoreMessages, isLoadingMore, searchCursor.index) {
        if (!searchingOlder) return@LaunchedEffect
        when {
            searchCursor.index > searchOlderBaseline -> {
                // The newest of the newly-found older matches sits just before the anchor's old slot.
                anchoredHitId = searchMatchIds[searchCursor.index - searchOlderBaseline - 1]
                searchScrollNonce++
                searchingOlder = false
            }
            !hasMoreMessages -> searchingOlder = false
            searchOlderPages >= ChatSearch.MAX_SEARCH_OLDER_PAGES -> searchingOlder = false
            !isLoadingMore -> {
                // Pace the dig so the quote prefetch can resolve nevent content of the page just
                // loaded before the next page loads; otherwise a fast relay exhausts all history
                // (hasMore -> false, hiding the affordance) before an older match inside a quote is
                // even detected. If a match appears during the wait, this effect restarts and stops.
                delay(300)
                searchOlderPages++
                onLoadMore()
            }
        }
    }
    // Watchdog: never leave the dig (and its spinner) stuck if pagination stops progressing — stop it
    // after a cap so the affordance comes back. Cancelled the moment the dig ends on its own.
    LaunchedEffect(searchingOlder) {
        if (!searchingOlder) return@LaunchedEffect
        delay(10_000)
        searchingOlder = false
    }

    // The bar renders as an OVERLAY inside MessagesList (over the top of the scroller), not in-flow,
    // so toggling search never resizes the message list and the first scroll-to-match doesn't race a
    // relayout (web parity).
    val bar: @Composable () -> Unit = {
        ChatSearchBar(
            query = searchQuery,
            onQueryChange = {
                searchQuery = it
                anchoredHitId = null
                searchingOlder = false
            },
            matchCount = searchMatchIds.size,
            currentPosition = searchCursor.position,
            onPrev = {
                if (searchMatchIds.isNotEmpty()) {
                    anchoredHitId = searchMatchIds[ChatSearch.step(clampedIndex, searchMatchIds.size, -1)]
                    searchScrollNonce++
                }
            },
            onNext = {
                if (searchMatchIds.isNotEmpty()) {
                    anchoredHitId = searchMatchIds[ChatSearch.step(clampedIndex, searchMatchIds.size, +1)]
                    searchScrollNonce++
                }
            },
            onClose = closeSearch,
            canSearchOlder = searchQuery.trim().length >= 2 && hasMoreMessages && !searchingOlder,
            isSearchingOlder = searchingOlder,
            onSearchOlder = {
                searchOlderBaseline = searchCursor.index
                searchOlderPages = 0
                searchingOlder = true
            },
            onCancelSearchOlder = { searchingOlder = false },
        )
    }

    return ChatSearchState(
        active = searchActive,
        currentHitId = currentSearchHitId,
        scrollNonce = searchScrollNonce,
        hitIds = searchHitIdSet,
        onToggle = { if (searchActive) closeSearch() else searchActive = true },
        bar = bar,
    )
}
