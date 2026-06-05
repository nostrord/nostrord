package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
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
// Debounce before a new query commits to a scan. Matches the web ChatScreen's searchDebounce so both
// platforms feel identical while typing; the scan also runs off the main thread (see produceState).
private const val SEARCH_DEBOUNCE_MS = 160L

// Snapshot of everything a match scan reads. Compared by value so the scan only re-runs when one of
// these actually changes (an unrelated reaction / zap emission re-renders the screen but leaves these
// equal, so distinctUntilChanged drops it).
private data class SearchInputs(
    val messages: List<NostrGroupClient.NostrMessage>,
    val userMetadata: Map<String, UserMetadata>,
    val cachedEvents: Map<String, org.nostr.nostrord.network.CachedEvent>,
    val query: String,
)

// Result of one scan: the ordered match ids plus the derived lookups the cursor and row highlight
// need, all built once on the background dispatcher instead of per recomposition on the main thread.
private data class SearchResult(
    val matchIds: List<String>,
    val indexById: Map<String, Int>,
    val hitIdSet: Set<String>,
)

private val EMPTY_SEARCH_RESULT = SearchResult(emptyList(), emptyMap(), emptySet())

// The heavy step: enrich reference-bearing messages (buildSearchTextById) and scan every message for
// the query. Runs on Dispatchers.Default via produceState. Reference-free messages still match on raw
// content via the `?: it.content` fallback, so the enrich map stays tiny (memory optimization intact).
private fun computeSearch(inputs: SearchInputs): SearchResult {
    val searchTextById =
        ChatSearch.buildSearchTextById(
            inputs.messages,
            resolveMention = { pubkey ->
                inputs.userMetadata[pubkey]?.let { it.displayName ?: it.name }?.takeIf(String::isNotBlank)
            },
            resolveCachedQuote = { id -> inputs.cachedEvents[id]?.content },
        )
    val matchIds =
        ChatSearch.matchingIds(inputs.messages, inputs.query) { searchTextById[it.id] ?: it.content }
    return SearchResult(matchIds, ChatSearch.indexById(matchIds), matchIds.toSet())
}

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
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    // The whole match scan (enriching reference-bearing messages, normalizing + scanning every
    // message) runs OFF the main thread on Dispatchers.Default and DEBOUNCED, so typing never blocks
    // composition. produceState below holds the latest result; recomposition just reads it. Keep the
    // cachedEvents flow / inputs as snapshot reads so snapshotFlow re-emits when they change.
    val cachedEventsForSearch by AppModule.nostrRepository.cachedEvents.collectAsState()
    // rememberUpdatedState so the produceState coroutine (started once per searchActive flip) always
    // computes against the freshest inputs without restarting on every emission.
    val currentMessages by rememberUpdatedState(messages)
    val currentMetadata by rememberUpdatedState(userMetadata)
    val currentCachedEvents by rememberUpdatedState(cachedEventsForSearch)
    val currentQuery by rememberUpdatedState(searchQuery)
    val matchResult by produceState(EMPTY_SEARCH_RESULT, searchActive) {
        if (!searchActive) {
            value = EMPTY_SEARCH_RESULT
            return@produceState
        }
        // Recompute whenever the messages, the enrichment inputs, or the query change. Snapshot reads
        // inside snapshotFlow track those State objects; debounce coalesces fast keystrokes; mapLatest
        // cancels an in-flight scan when a newer input arrives. flowOn moves the build + scan to a
        // background dispatcher so the main thread never normalizes thousands of messages per keystroke.
        snapshotFlow {
            SearchInputs(currentMessages, currentMetadata, currentCachedEvents, currentQuery)
        }
            .distinctUntilChanged()
            .debounce(SEARCH_DEBOUNCE_MS)
            .mapLatest { inputs -> computeSearch(inputs) }
            .flowOn(Dispatchers.Default)
            .collect { value = it }
    }
    val searchMatchIds = matchResult.matchIds
    // Cursor: anchored hit position, current id, and inverted 1-based display number (1 = newest).
    // indexById makes the anchor lookup O(1) so prev/next (which recomposes the screen every press)
    // never re-scans the match list to find the cursor.
    val searchCursor = ChatSearch.cursor(searchMatchIds, matchResult.indexById, anchoredHitId)
    val clampedIndex = searchCursor.index
    val currentSearchHitId = searchCursor.currentId
    val searchHitIdSet = matchResult.hitIdSet

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
    // quotedEventRefs scans content with regex, which is O(messages) and froze the UI as the list
    // grew because a LaunchedEffect body runs on the main dispatcher: run the scan on Default, and
    // only over messages not scanned yet this session so a busy feed (or a dig prepending pages) does
    // not re-scan the whole list per append. The id set also dedups requestEventById across runs.
    val prefetchedForSearch = remember(groupId) { mutableSetOf<String>() }
    LaunchedEffect(searchActive, messages) {
        if (!searchActive) return@LaunchedEffect
        val fresh = messages.filterNot { it.id in prefetchedForSearch }
        if (fresh.isEmpty()) return@LaunchedEffect
        fresh.forEach { prefetchedForSearch.add(it.id) }
        val refs = withContext(Dispatchers.Default) { ChatSearch.quotedEventRefs(fresh) }
        val cached = AppModule.nostrRepository.cachedEvents.value
        for (ref in refs) {
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
