package org.nostr.nostrord.utils

import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.nostr.Nip27

/**
 * Shared, platform-agnostic message-search matching. Both render trees (Compose native and
 * the web React/DOM UI) call this so a query matches the exact same messages everywhere.
 *
 * Matching is accent/style-insensitive via [normalizeForSearch] and limited to chat messages
 * (kind 9) — system / moderation events are not searchable text.
 */
object ChatSearch {
    /** Below this length the query is ignored (returns no matches). */
    const val MIN_QUERY_LENGTH = 2

    /**
     * Max pages a single "search older messages" dig loads before yielding (keeps the affordance
     * from draining all history in one click). Shared so every platform caps the dig the same way.
     */
    const val MAX_SEARCH_OLDER_PAGES = 12

    /**
     * Move a match cursor by [delta] (typically +1 / -1) over a list of [size] with wraparound.
     * Returns 0 for an empty list. Shared so every platform's next/previous navigation wraps the
     * same way instead of re-deriving the modulo math.
     */
    fun step(index: Int, size: Int, delta: Int): Int = if (size <= 0) 0 else ((index + delta) % size + size) % size

    /**
     * The resolved search cursor for a set of [matchIds] (chronological, oldest-first) anchored to
     * [anchorId]. [index] is where the cursor sits (the anchor's position, or the LAST match — most
     * recent — as the fresh-query default); [currentId] is the message there; [position] is the
     * 1-based display number using INVERTED numbering (1 = most recent match, growing toward older).
     * Centralised so the anchor fallback and inverted-numbering invariant stay identical everywhere.
     */
    data class Cursor(val index: Int, val currentId: String?, val position: Int)

    fun cursor(matchIds: List<String>, anchorId: String?): Cursor {
        if (matchIds.isEmpty()) return Cursor(index = -1, currentId = null, position = 0)
        val index = matchIds.indexOf(anchorId).let { if (it >= 0) it else matchIds.lastIndex }
        return Cursor(index = index, currentId = matchIds[index], position = matchIds.size - index)
    }

    /**
     * O(1) cursor resolution: [indexById] is a precomputed `id -> position` map for [matchIds] (build
     * it once per result set with [indexById]). Prev/next navigation calls this on every recomposition,
     * so the [List.indexOf] scan in the list-only overload is replaced by a map lookup. Behaviour is
     * identical to [cursor]: an unknown / null anchor falls back to the LAST (most recent) match, and
     * [Cursor.position] uses the same inverted 1-based numbering (1 = most recent).
     */
    fun cursor(matchIds: List<String>, indexById: Map<String, Int>, anchorId: String?): Cursor {
        if (matchIds.isEmpty()) return Cursor(index = -1, currentId = null, position = 0)
        val index = (anchorId?.let { indexById[it] } ?: -1).let { if (it >= 0) it else matchIds.lastIndex }
        return Cursor(index = index, currentId = matchIds[index], position = matchIds.size - index)
    }

    /** `id -> position` map for a [matchIds] list, so [cursor] can resolve an anchor in O(1). */
    fun indexById(matchIds: List<String>): Map<String, Int> {
        val out = HashMap<String, Int>(matchIds.size)
        matchIds.forEachIndexed { i, id -> out[id] = i }
        return out
    }

    /**
     * Ids of the kind-9 messages whose searchable text matches [query], preserving the input list
     * order (chronological). Returns an empty list when the trimmed, normalized query is shorter
     * than [MIN_QUERY_LENGTH].
     *
     * [searchableText] yields the string actually matched for a message. It defaults to the raw
     * [content], but each UI passes an enriched extractor so the query also matches what the user
     * SEES rather than the raw wire text: `nostr:npub1…` mentions resolved to the @display name,
     * and `nostr:nevent1…` / `nostr:note1…` quotes resolved to the referenced note's text (only
     * for events already in memory — search never triggers a network fetch). Keeping the resolver
     * platform-side keeps this matcher pure and identical across native and web.
     */
    fun matchingIds(
        messages: List<NostrGroupClient.NostrMessage>,
        query: String,
        searchableText: (NostrGroupClient.NostrMessage) -> String = { it.content },
    ): List<String> {
        val q = query.trim().normalizeForSearch()
        if (q.length < MIN_QUERY_LENGTH) return emptyList()
        return messages.asSequence()
            .filter { it.kind == 9 && searchableText(it).normalizeForSearch().contains(q) }
            .map { it.id }
            .toList()
    }

    /** A note/nevent reference found in message content, with the hints needed to fetch it. */
    data class QuoteRef(val eventId: String, val relays: List<String>, val author: String?)

    /**
     * Distinct note/nevent references appearing in kind-9 message content. Search resolves a quote's
     * text only if the quoted event is in memory, which normally happens lazily when the message is
     * rendered. Prefetching these (when search opens) makes quoted text searchable even for messages
     * the user hasn't scrolled to yet, matching what the user would see if they had.
     */
    fun quotedEventRefs(messages: List<NostrGroupClient.NostrMessage>): List<QuoteRef> {
        val out = LinkedHashMap<String, QuoteRef>()
        for (m in messages) {
            if (m.kind != 9 || !Nip27.containsReferences(m.content)) continue
            for ((_, ref) in Nip27.findReferenceMatches(m.content)) {
                when (val e = ref.entity) {
                    is Nip19.Entity.Nevent -> out.getOrPut(e.eventId) { QuoteRef(e.eventId, e.relays, e.author) }
                    is Nip19.Entity.Note -> out.getOrPut(e.eventId) { QuoteRef(e.eventId, emptyList(), null) }
                    else -> {}
                }
            }
        }
        return out.values.toList()
    }

    /**
     * The text a search query should match for a message: what the user SEES, not the raw wire
     * content. Walks the canonical NIP-27 reference parser ([Nip27.findReferenceMatches], the same
     * one the renderers use, so search text and rendered text can't drift) and rewrites each
     * reference via the resolvers: a profile mention becomes the @display name, an event quote
     * becomes the referenced note's text. Resolvers return null when nothing is available (a quote
     * not yet in memory, an unknown profile) and that reference contributes nothing — search never
     * triggers a fetch. Group refs keep their %id form. Both render trees pass platform resolvers
     * (userMetadata / cachedEvents lookups) so this single definition stays the source of truth.
     */
    fun searchableText(
        content: String,
        resolveMention: (pubkey: String) -> String?,
        resolveQuote: (eventId: String) -> String?,
    ): String {
        val refs = Nip27.findReferenceMatches(content)
        if (refs.isEmpty()) return content
        val out = StringBuilder(content.length)
        var cursor = 0
        for ((range, ref) in refs) {
            if (range.first > cursor) out.append(content, cursor, range.first)
            out.append(
                when (val entity = ref.entity) {
                    is Nip19.Entity.Npub -> resolveMention(entity.pubkey)?.let { "@$it" } ?: ""
                    is Nip19.Entity.Nprofile -> resolveMention(entity.pubkey)?.let { "@$it" } ?: ""
                    is Nip19.Entity.Note -> resolveQuote(entity.eventId) ?: ""
                    is Nip19.Entity.Nevent -> resolveQuote(entity.eventId) ?: ""
                    is Nip19.Entity.Naddr -> if (entity.kind == 39000) "%${entity.identifier}" else ""
                    else -> ""
                },
            )
            cursor = range.last + 1
        }
        if (cursor < content.length) out.append(content, cursor, content.length)
        return out.toString()
    }

    /**
     * Enriched searchable text per message id, built ONLY for kind-9 messages that contain a nostr:
     * reference. Reference-free messages (the vast majority) match on their raw content via the
     * caller's `map[id] ?: it.content` fallback, so storing nothing for them keeps this map tiny (a
     * handful of entries instead of one per message) and skips the per-message NIP-27 parse + string
     * build for the rest. That is the difference between rebuilding N strings on every metadata /
     * cache emission and rebuilding only the few reference-bearing ones.
     *
     * [messages] is also the source for resolving a quote that is a local group message; that lookup
     * map is built lazily here, so a group with no quotes never allocates it. [resolveCachedQuote]
     * resolves quotes already fetched into the platform cache.
     */
    fun buildSearchTextById(
        messages: List<NostrGroupClient.NostrMessage>,
        resolveMention: (pubkey: String) -> String?,
        resolveCachedQuote: (eventId: String) -> String?,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var localById: Map<String, NostrGroupClient.NostrMessage>? = null
        val resolveQuote: (String) -> String? = { eventId ->
            val local = localById ?: messages.associateBy { it.id }.also { localById = it }
            local[eventId]?.content ?: resolveCachedQuote(eventId)
        }
        for (m in messages) {
            if (m.kind != 9 || !Nip27.containsReferences(m.content)) continue
            out[m.id] = searchableText(m.content, resolveMention, resolveQuote)
        }
        return out
    }
}
