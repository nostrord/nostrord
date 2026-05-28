package org.nostr.nostrord.network.managers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.nostr.nostrord.network.CachedEvent
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.utils.LruCache
import org.nostr.nostrord.utils.epochMillis

class MetadataManager(
    private val connectionManager: ConnectionManager,
    private val outboxManager: OutboxManager,
    private val scope: CoroutineScope,
) {
    /** Set by NostrRepository so batchFetch can reconnect bootstrap relays when all are offline. */
    var messageHandler: ((String, NostrGroupClient) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val MAX_METADATA_CACHE_SIZE = 500
        const val MAX_EVENTS_CACHE_SIZE = 500

        /** Metadata older than this is considered stale and will be re-fetched. */
        const val STALE_THRESHOLD_MS = 30 * 60 * 1000L

        /**
         * How long to suppress retries for a pubkey whose previous batch finished
         * without returning any kind:0 event. Without this, every UI render that
         * triggers requestUserMetadata refires the same single-author REQ to every
         * bootstrap relay every few seconds (observed: ~60 duplicate REQs per relay
         * on cold start, dominating the profile-fetch fan-out).
         */
        const val NEGATIVE_CACHE_TTL_MS = 5 * 60 * 1000L

        /** Number of retry attempts before giving up on a metadata fetch. */
        const val MAX_FETCH_ATTEMPTS = 3

        /** Max authors per REQ filter — keeps relay filter sizes reasonable. */
        const val BATCH_SIZE = 50

        /** Coalesce window for metadata StateFlow emissions (ms). */
        const val METADATA_FLUSH_DELAY_MS = 100L

        /**
         * How long to accumulate pubkeys from concurrent callers before firing the
         * outgoing REQ. Event handlers fire one [requestUserMetadata] per incoming
         * kind:9 / kind:7 / kind:0 event; without this, 50 mux events become 50
         * separate batches × 4 bootstrap relays = ~200 REQs in a single burst.
         * 100 ms is large enough to catch the post-EOSE burst and small enough
         * that interactive flows (open profile modal) don't feel laggy.
         */
        const val COALESCE_WINDOW_MS = 100L

        /** Wall-clock fallback if a relay never emits EOSE for a metadata batch. */
        const val EOSE_FALLBACK_MS = 5_000L
    }

    /**
     * Cached kind:0 entry. [createdAt] is the event's own timestamp (seconds, NIP-01) and
     * gates stale-echo rejection; [fetchedAt] is the wall-clock time we last received it
     * and gates the 30-min refresh on incoming messages.
     */
    data class CachedMetadata(
        val metadata: UserMetadata,
        val createdAt: Long,
        val fetchedAt: Long,
    )

    private val metadataCache = LruCache<String, CachedMetadata>(MAX_METADATA_CACHE_SIZE)
    private val eventsCache = LruCache<String, CachedEvent>(MAX_EVENTS_CACHE_SIZE)

    /**
     * Pubkey → wall-clock time we last finished a batch fetch that returned no
     * kind:0 for that pubkey. Suppresses repeat fetches for [NEGATIVE_CACHE_TTL_MS]
     * so a non-existent profile doesn't keep getting re-asked from every UI render.
     */
    private val negativeMetadataCache = LruCache<String, Long>(MAX_METADATA_CACHE_SIZE)

    private val inFlightPubkeys = mutableSetOf<String>()
    private val inFlightMutex = Mutex()
    private val inFlightEvents = mutableSetOf<String>()
    private val inFlightEventsMutex = Mutex()

    /**
     * Pubkeys waiting to be flushed into a single batched REQ. Populated by
     * [requestUserMetadata] under [pendingMutex]; drained by the flush coroutine
     * scheduled when the set transitions from empty to non-empty.
     */
    private val pendingPubkeys = mutableSetOf<String>()
    private val pendingMutex = Mutex()
    private var pendingForceStale = false

    /**
     * Per-batch state for EOSE-driven completion of [batchFetch]. Each batch
     * waits on its [deferred] until every relay in [pendingRelays] has emitted
     * EOSE for the batch subscription id, or the wall-clock fallback fires.
     */
    private data class MetadataBatch(
        val pendingRelays: MutableSet<String>,
        val deferred: CompletableDeferred<Unit>,
    )

    private val metadataBatches = mutableMapOf<String, MetadataBatch>()
    private val metadataBatchesMutex = Mutex()
    private var metadataBatchCounter = 0L

    private val _userMetadata = MutableStateFlow<Map<String, UserMetadata>>(emptyMap())
    val userMetadata: StateFlow<Map<String, UserMetadata>> = _userMetadata.asStateFlow()

    private val _cachedEvents = MutableStateFlow<Map<String, CachedEvent>>(emptyMap())
    val cachedEvents: StateFlow<Map<String, CachedEvent>> = _cachedEvents.asStateFlow()

    fun requestUserMetadata(
        pubkeys: Set<String>,
        messageHandler: (String, NostrGroupClient) -> Unit,
        forceStale: Boolean = false,
    ) {
        if (pubkeys.isEmpty()) return

        scope.launch {
            val now = epochMillis()
            val toEnqueue =
                inFlightMutex.withLock {
                    pubkeys
                        .filter { pk ->
                            if (pk in inFlightPubkeys) return@filter false
                            if (metadataCache.get(pk) != null && !(forceStale && isStale(pk))) {
                                return@filter false
                            }
                            // Skip pubkeys whose previous batch returned no kind:0
                            // within the TTL — relays don't suddenly start hosting
                            // metadata for an unknown user inside 5 minutes.
                            val negAt = negativeMetadataCache.get(pk)
                            if (!forceStale && negAt != null && now - negAt < NEGATIVE_CACHE_TTL_MS) {
                                return@filter false
                            }
                            true
                        }.also { inFlightPubkeys.addAll(it) }
                }
            if (toEnqueue.isEmpty()) return@launch

            // Enqueue and (only on empty → non-empty transition) schedule the flush.
            // Concurrent callers within COALESCE_WINDOW_MS land in the same pending
            // set, so a burst of N event handlers becomes one batched REQ per relay
            // instead of N separate ones.
            val shouldSchedule = pendingMutex.withLock {
                val wasEmpty = pendingPubkeys.isEmpty()
                pendingPubkeys.addAll(toEnqueue)
                pendingForceStale = pendingForceStale || forceStale
                wasEmpty
            }
            if (shouldSchedule) {
                scope.launch { flushPending() }
            }
        }
    }

    private suspend fun flushPending() {
        delay(COALESCE_WINDOW_MS)
        val toFetch = pendingMutex.withLock {
            val snapshot = pendingPubkeys.toList()
            pendingPubkeys.clear()
            pendingForceStale = false
            snapshot
        }
        if (toFetch.isEmpty()) return

        val fetchStartedAt = epochMillis()
        try {
            batchFetch(toFetch)
        } finally {
            val finishedAt = epochMillis()
            inFlightMutex.withLock {
                inFlightPubkeys.removeAll(toFetch.toSet())
                // Mark pubkeys whose batch returned nothing fresh so the next
                // caller doesn't refetch immediately. "Nothing fresh" = no
                // cache entry OR an entry that predates this batch.
                toFetch.forEach { pk ->
                    val fetchedAt = metadataCache.get(pk)?.fetchedAt
                    if (fetchedAt == null || fetchedAt < fetchStartedAt) {
                        negativeMetadataCache.put(pk, finishedAt)
                    } else {
                        negativeMetadataCache.remove(pk)
                    }
                }
            }
        }
    }

    private suspend fun batchFetch(pubkeys: List<String>) {
        // Record the fetch start so we can distinguish "fetched before this call"
        // from "fetched during this call" for stale refresh scenarios.
        val fetchStartedAt = epochMillis()

        // Source the active account's relay list from OutboxManager (in-memory,
        // pubkey-scoped) instead of SecureStorage. The on-disk slot used to be
        // global and would leak another account's relays into this filter.
        val nip29Relays =
            outboxManager.kind10009Relays.value +
                connectionManager.currentRelayUrl.value

        val candidates =
            outboxManager.bootstrapRelays
                .filter { it !in nip29Relays }

        repeat(MAX_FETCH_ATTEMPTS) { attempt ->
            val missing =
                pubkeys.filter { pk ->
                    val fetchedAt = metadataCache.get(pk)?.fetchedAt
                    fetchedAt == null || fetchedAt < fetchStartedAt
                }
            if (missing.isEmpty()) return

            // Register the batch BEFORE sending any REQ so a fast relay's EOSE
            // is not dropped between dispatch and registration.
            val deferred = CompletableDeferred<Unit>()
            val (subId, pendingRelays) = metadataBatchesMutex.withLock {
                val id = "metadata_batch_${++metadataBatchCounter}_$attempt"
                val relays = mutableSetOf<String>()
                metadataBatches[id] = MetadataBatch(relays, deferred)
                id to relays
            }

            suspend fun trySendOn(client: NostrGroupClient?, relayUrl: String): Boolean {
                if (client == null || !client.isConnected()) return false
                metadataBatchesMutex.withLock { pendingRelays.add(relayUrl) }
                return try {
                    missing.chunked(BATCH_SIZE).forEach { chunk ->
                        client.requestMetadata(chunk, subId)
                    }
                    true
                } catch (_: Exception) {
                    val complete = metadataBatchesMutex.withLock {
                        pendingRelays.remove(relayUrl) && pendingRelays.isEmpty()
                    }
                    if (complete) deferred.complete(Unit)
                    false
                }
            }

            // Dispatch in parallel so we don't pay per-relay round-trip latency
            // serially — all REQs go in flight before we start waiting on EOSE.
            coroutineScope {
                candidates.map { relayUrl ->
                    async { trySendOn(connectionManager.getClientForRelay(relayUrl), relayUrl) }
                }.awaitAll()
            }

            // All bootstrap relays offline — try to reconnect one.
            val anySent = metadataBatchesMutex.withLock { pendingRelays.isNotEmpty() }
            if (!anySent) {
                val handler = messageHandler
                if (handler != null) {
                    candidates.firstOrNull { relayUrl ->
                        runCatching {
                            trySendOn(connectionManager.getOrConnectRelay(relayUrl, handler), relayUrl)
                        }.getOrDefault(false)
                    }
                }
            }

            val finalSent = metadataBatchesMutex.withLock { pendingRelays.isNotEmpty() }
            if (finalSent) {
                // Wait for EOSE from every relay we sent to; bounded fallback in
                // case a relay never EOSEs so we don't hang the next attempt.
                withTimeoutOrNull(EOSE_FALLBACK_MS) { deferred.await() }
                metadataBatchesMutex.withLock { metadataBatches.remove(subId) }

                val allFresh =
                    pubkeys.all { pk ->
                        val at = metadataCache.get(pk)?.fetchedAt
                        at != null && at >= fetchStartedAt
                    }
                if (allFresh) return
            } else {
                metadataBatchesMutex.withLock { metadataBatches.remove(subId) }
                if (attempt < MAX_FETCH_ATTEMPTS - 1) {
                    // No bootstrap relay was reachable — back off briefly before retry.
                    delay(2_000L)
                }
            }
        }
    }

    /**
     * Marks [sourceRelayUrl] as done for the given batch [subId]. When every
     * relay we dispatched the batch to has EOSEd, the batch's deferred
     * completes and [batchFetch] proceeds to the next attempt (or returns).
     */
    suspend fun notifyMetadataEose(subId: String, sourceRelayUrl: String) {
        if (!subId.startsWith("metadata_batch_")) return
        val toComplete = metadataBatchesMutex.withLock {
            val batch = metadataBatches[subId] ?: return@withLock null
            batch.pendingRelays.remove(sourceRelayUrl)
            if (batch.pendingRelays.isEmpty()) batch.deferred else null
        }
        toComplete?.complete(Unit)
    }

    suspend fun requestEventById(
        eventId: String,
        relayHints: List<String> = emptyList(),
        author: String? = null,
        messageHandler: (String, NostrGroupClient) -> Unit,
    ) {
        // Skip if already cached or already in-flight
        if (_cachedEvents.value.containsKey(eventId)) return
        val shouldFetch =
            inFlightEventsMutex.withLock {
                if (inFlightEvents.contains(eventId)) {
                    false
                } else {
                    inFlightEvents.add(eventId)
                    true
                }
            }
        if (!shouldFetch) return

        try {
            // Use outbox model for relay selection
            val relaysToTry =
                outboxManager.selectConnectedOutboxRelays(
                    authors = if (author != null) listOf(author) else emptyList(),
                    explicitRelays = relayHints,
                )

            // All relays in relaysToTry are already connected (pre-filtered).
            var sent =
                relaysToTry.count { relayUrl ->
                    try {
                        connectionManager.getClientForRelay(relayUrl)!!.requestEventById(eventId)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

            // Always also try primary NIP-29 relay — it hosts the events being quoted.
            val primary = connectionManager.getPrimaryClient()
            if (primary != null && primary.isConnected()) {
                try {
                    primary.requestEventById(eventId)
                    sent++
                } catch (_: Exception) {
                }
            }
        } finally {
            // Remove after a delay to allow the response to arrive and be cached.
            // If not cached after 10s, subsequent requests can try again.
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(eventId) }
            }
        }
    }

    suspend fun requestAddressableEvent(
        kind: Int,
        pubkey: String,
        identifier: String,
        relayHints: List<String> = emptyList(),
        messageHandler: (String, NostrGroupClient) -> Unit,
    ) {
        // Create composite key for caching
        val addressKey = "$kind:$pubkey:$identifier"

        // Skip if already cached or already in-flight
        if (_cachedEvents.value.containsKey(addressKey)) return
        val shouldFetch =
            inFlightEventsMutex.withLock {
                if (inFlightEvents.contains(addressKey)) {
                    false
                } else {
                    inFlightEvents.add(addressKey)
                    true
                }
            }
        if (!shouldFetch) return

        try {
            // Use outbox model for relay selection
            val relaysToTry =
                outboxManager.selectConnectedOutboxRelays(
                    authors = listOf(pubkey),
                    explicitRelays = relayHints,
                )

            // All relays in relaysToTry are already connected (pre-filtered).
            var sent =
                relaysToTry.count { relayUrl ->
                    try {
                        connectionManager.getClientForRelay(relayUrl)!!.requestAddressableEvent(kind, pubkey, identifier)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }

            // Always also try primary NIP-29 relay — it hosts the addressable events.
            val primary = connectionManager.getPrimaryClient()
            if (primary != null && primary.isConnected()) {
                try {
                    primary.requestAddressableEvent(kind, pubkey, identifier)
                    sent++
                } catch (_: Exception) {
                }
            }
        } finally {
            scope.launch {
                delay(10_000)
                inFlightEventsMutex.withLock { inFlightEvents.remove(addressKey) }
            }
        }
    }

    private var metadataFlushJob: Job? = null

    /**
     * Accepts an incoming kind:0 unless it is a stale or empty echo that would clobber a
     * better cached entry. Without this guard, idle clients gradually lose all profiles:
     * relays occasionally re-emit older or empty-content kind:0 events for a pubkey, and
     * the periodic stale-refresh would accept them blindly.
     */
    fun handleMetadataEvent(
        pubkey: String,
        metadata: UserMetadata,
        createdAt: Long,
    ) {
        val cached = metadataCache.get(pubkey)
        if (cached != null) {
            // Older event: reject — we already have a newer one.
            if (createdAt < cached.createdAt) return
            if (createdAt == cached.createdAt) {
                // Identical echo (same ts, same content): refresh fetchedAt only, no flush.
                if (metadata == cached.metadata) {
                    metadataCache.put(pubkey, cached.copy(fetchedAt = epochMillis()))
                    return
                }
                // Same ts, content disagrees: prefer the non-empty one.
                if (metadata.isEmpty() && !cached.metadata.isEmpty()) return
            }
            // createdAt > cached.createdAt falls through and overwrites — a strictly newer
            // empty event is treated as a deliberate profile wipe and is allowed.
        }
        metadataCache.put(pubkey, CachedMetadata(metadata, createdAt, epochMillis()))
        scheduleMetadataFlush()
    }

    private fun publishMetadataSnapshot() {
        val snapshot = HashMap<String, UserMetadata>(metadataCache.size())
        metadataCache.toMap().forEach { (pubkey, cached) -> snapshot[pubkey] = cached.metadata }
        _userMetadata.value = snapshot
    }

    private fun scheduleMetadataFlush() {
        metadataFlushJob?.cancel()
        metadataFlushJob =
            scope.launch {
                delay(METADATA_FLUSH_DELAY_MS)
                publishMetadataSnapshot()
            }
    }

    private fun flushMetadataNow() {
        metadataFlushJob?.cancel()
        publishMetadataSnapshot()
    }

    fun updateLocalMetadata(
        pubkey: String,
        metadata: UserMetadata,
        createdAt: Long,
    ) {
        metadataCache.put(pubkey, CachedMetadata(metadata, createdAt, epochMillis()))
        flushMetadataNow()
    }

    private fun UserMetadata.isEmpty(): Boolean = name.isNullOrBlank() &&
        displayName.isNullOrBlank() &&
        picture.isNullOrBlank() &&
        about.isNullOrBlank() &&
        nip05.isNullOrBlank() &&
        banner.isNullOrBlank() &&
        lud16.isNullOrBlank() &&
        lud06.isNullOrBlank() &&
        website.isNullOrBlank()

    fun handleCachedEvent(event: CachedEvent) {
        eventsCache.put(event.id, event)
        _cachedEvents.value = eventsCache.toMap()
    }

    fun parseAndCacheEvent(eventJson: JsonObject): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null

            eventsCache.get(eventId)?.let { return it }

            val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
            val createdAt = eventJson["created_at"]?.jsonPrimitive?.long ?: 0L
            val kind = eventJson["kind"]?.jsonPrimitive?.int ?: 1
            val tags =
                eventJson["tags"]?.jsonArray?.map { tagArray ->
                    tagArray.jsonArray.map { it.jsonPrimitive.content }
                } ?: emptyList()

            val cachedEvent =
                CachedEvent(
                    id = eventId,
                    pubkey = pubkey,
                    kind = kind,
                    content = content,
                    createdAt = createdAt,
                    tags = tags,
                )

            eventsCache.put(eventId, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            cachedEvent
        } catch (e: Exception) {
            null
        }
    }

    fun parseAndCacheAddressableEvent(eventJson: JsonObject): CachedEvent? {
        return try {
            val eventId = eventJson["id"]?.jsonPrimitive?.content ?: return null
            val pubkey = eventJson["pubkey"]?.jsonPrimitive?.content ?: return null
            val content = eventJson["content"]?.jsonPrimitive?.content ?: ""
            val createdAt = eventJson["created_at"]?.jsonPrimitive?.long ?: 0L
            val kind = eventJson["kind"]?.jsonPrimitive?.int ?: 1
            val tags =
                eventJson["tags"]?.jsonArray?.map { tagArray ->
                    tagArray.jsonArray.map { it.jsonPrimitive.content }
                } ?: emptyList()

            val identifier = tags.find { it.firstOrNull() == "d" }?.getOrNull(1) ?: ""
            val addressKey = "$kind:$pubkey:$identifier"
            eventsCache.get(addressKey)?.let { return it }

            val cachedEvent =
                CachedEvent(
                    id = eventId,
                    pubkey = pubkey,
                    kind = kind,
                    content = content,
                    createdAt = createdAt,
                    tags = tags,
                )

            eventsCache.put(eventId, cachedEvent)
            eventsCache.put(addressKey, cachedEvent)
            _cachedEvents.value = eventsCache.toMap()
            cachedEvent
        } catch (_: Exception) {
            null
        }
    }

    fun hasMetadata(pubkey: String): Boolean = metadataCache.containsKey(pubkey)

    fun isStale(pubkey: String): Boolean {
        val cached = metadataCache.get(pubkey) ?: return true
        return (epochMillis() - cached.fetchedAt) > STALE_THRESHOLD_MS
    }

    fun hasCachedEvent(eventId: String): Boolean = eventsCache.containsKey(eventId)

    fun getMetadata(pubkey: String): UserMetadata? = metadataCache.get(pubkey)?.metadata

    fun getCachedEvent(eventId: String): CachedEvent? = eventsCache.get(eventId)

    fun clear() {
        metadataCache.clear()
        eventsCache.clear()
        _userMetadata.value = emptyMap()
        _cachedEvents.value = emptyMap()
    }
}
